package jvm

import sast.*
import sast.Trees.*
import sast.Types.Type

import jvm.ClassFile.*
import jvm.JVMTypes.*
import jvm.JVMTypes.JType.*

/** Lowers primitive and String operations to JVM instructions. */
final class PrimitiveOps(
  runtime: JVMRuntime,
  jvmType: Type => JType,
  operands: PrimitiveOps.Operands
)(using Definitions):
  private type MethodCtx = MethodContext

  private def compile(word: Word)(using MethodContext): Unit =
    operands.compile(word)
    ()
  private def boolFromBranch(branch: Label => Unit)(using MethodContext): Unit =
    operands.boolFromBranch(branch)
  private def compileStaticCall(symbol: Symbols.Symbol, args: List[Word])(using MethodContext): Unit =
    operands.compileStaticCall(symbol, args)

  // Primitive numeric/boolean operators (Int/Bool/Byte/Char intrinsics)
  //----------------------------------------------------------------------------

  def compilePrimitive(qual: Word, name: String, args: List[Word])(using ctx: MethodCtx): Unit =
    if jvmType(qual.tpe) == J then
      compileLongOp(qual, name, args)
    else
      compileIntCatPrimitiveOp(qual, name, args)

  /** Operations whose values share the JVM category-1 integer
    * representation. Erasure has already normalized their operands.
    */
  private def compileIntCatPrimitiveOp(qual: Word, name: String, args: List[Word])(using ctx: MethodCtx): Unit =
    val cw = ctx.cw
    val qt = jvmType(qual.tpe)

    def binIntOp(emit: () => Unit): Unit =
      compile(qual)
      compile(args.head)
      emit()

    // A Jo `Byte` is stored as its signed 8-bit pattern (see
    // `JVMTypes.descOf`), so every operation that reads one *as a number*
    // has to zero-extend first: -56 and 200 are the same byte, but only one
    // of them converts, prints, and orders the way Jo's unsigned [0, 255]
    // `Byte` requires.
    def unsigned(): Unit =
      if qt == B then
        cw.iconst(0xFF)
        cw.iand()

    def cmpOp(cond: String): Unit =
      compile(qual)
      compile(args.head)
      boolFromBranch(l => cw.ifIcmp(cond, l))

    /** `<`, `>`, `<=`, `>=` — unlike `==`/`!=`, these read a `Byte` as a
      * number, and `if_icmp*` reads it as a *signed* one: a `Byte` of 200 is
      * stored as -56 and would order below 100. Equality needs no such
      * bridge, because zero-extension is a bijection on the 8-bit patterns a
      * `Byte` slot can hold — equal patterns stay equal either way.
      */
    def orderOp(cond: String): Unit =
      compile(qual)
      unsigned()
      compile(args.head)
      unsigned()
      boolFromBranch(l => cw.ifIcmp(cond, l))

    name match
      case "+" => binIntOp(cw.iadd)
      case "-" if args.nonEmpty => binIntOp(cw.isub)
      case "-" | "~-" => compile(qual); cw.ineg()
      case "~~" => compile(qual); cw.iconst(-1); cw.ixor()
      case "*" => binIntOp(cw.imul)
      case "/" => binIntOp(cw.idiv)
      case "%" => binIntOp(cw.irem)
      case "&" => binIntOp(cw.iand)
      case "|" => binIntOp(cw.ior)
      case "^" => binIntOp(cw.ixor)
      case "<<" => binIntOp(cw.ishl)
      case ">>" => binIntOp(cw.ishr)
      case "==" => cmpOp("eq")
      case "!=" => cmpOp("ne")
      case ">" => orderOp("gt")
      case "<" => orderOp("lt")
      case ">=" => orderOp("ge")
      case "<=" => orderOp("le")
      case "!" | "~!" => compile(qual); boolNot()
      case "&&" =>
        // short-circuiting and/or are already lowered to If by earlier phases
        // in practice, but handle directly in case they reach here.
        compile(qual)
        val elseL = cw.newLabel(); val endL = cw.newLabel()
        cw.ifeq(elseL)
        compile(args.head)
        cw.gotoL(endL)
        cw.mark(elseL); cw.iconst(0)
        cw.mark(endL)
      case "||" =>
        compile(qual)
        val elseL = cw.newLabel(); val endL = cw.newLabel()
        cw.ifeq(elseL)
        cw.iconst(1)
        cw.gotoL(endL)
        cw.mark(elseL)
        compile(args.head)
        cw.mark(endL)
      case "toChar" | "toInt" =>
        // Char shares Int's full range in this backend (no truncation to
        // 16 bits — Jo's Char is a full Unicode code point, not a UTF-16
        // code unit), so Char->Int and Int->Char are genuine no-ops.
        compile(qual)
        unsigned()
      case "toByte" =>
        // The one producer of a `Byte`: keep the low 8 bits as the signed
        // pattern every `Byte` slot holds. `iand 0xFF` would be wrong here
        // — it leaves 200 as 200, which a `B` return or field would then
        // narrow behind our back.
        compile(qual)
        cw.i2b()
      case "toLong" =>
        compile(qual); unsigned(); cw.i2l()
      case "toString" =>
        val (owner, desc) =
          // Character.toString(char) truncates to 16 bits — wrong for Jo's
          // Char, a full Unicode code point (up to 0x10FFFF, e.g. emoji).
          // Character.toString(int codePoint) (Java 11+) handles the full
          // range correctly, including encoding a supplementary character
          // as a surrogate pair.
          if qt == C then ("java/lang/Character", "(I)Ljava/lang/String;")
          else if qt == Z then ("java/lang/Boolean", "(Z)Ljava/lang/String;")
          // A `Byte` renders through `Integer` on its zero-extended value:
          // `Byte.toString` reads the pattern as signed and prints 200
          // as -56.
          else ("java/lang/Integer", "(I)Ljava/lang/String;")
        compile(qual)
        unsigned()
        cw.invokestatic(owner, "toString", desc)
      case other =>
        throw new Exception("JVM backend: unsupported primitive operator " + other)

  /** `Long`'s intrinsics — a genuine category-2 JVM value (2 operand-stack
    * words, 2 local-variable slots), so unlike `Int`/`Bool`/`Byte`/`Char`
    * (all sharing the `I` representation, see `compileIntCatPrimitiveOp`)
    * it needs its own opcodes throughout, not just a wider range of `I`.
    */
  private def compileLongOp(qual: Word, name: String, args: List[Word])(using ctx: MethodCtx): Unit =
    val cw = ctx.cw

    def binLongOp(emit: () => Unit): Unit =
      compile(qual)
      compile(args.head)
      emit()

    // `lshl`/`lshr`'s shift-*amount* operand must be a plain `int` (JVMS),
    // but `Long.<<`/`Long.>>` declare their count parameter as `Long`
    // (lib/Long.jo) — compile it as `Long` like any other argument, then
    // narrow with `l2i` right before the shift opcode (only the low 6 bits
    // matter for a 64-bit shift count anyway, so truncation is harmless).
    def shiftLongOp(emit: () => Unit): Unit =
      compile(qual)
      compile(args.head); cw.l2i()
      emit()

    // No `if_lcmp<cond>` branch family exists — `lcmp` reduces the
    // comparison to a category-1 int (-1/0/1), then an ordinary
    // int-vs-zero branch (`ifCond`) reads off the result.
    def cmpOp(cond: String): Unit =
      compile(qual)
      compile(args.head)
      cw.lcmp()
      boolFromBranch(l => cw.ifCond(cond, l))

    name match
      case "+" => binLongOp(cw.ladd)
      case "-" if args.nonEmpty => binLongOp(cw.lsub)
      case "-" | "~-" => compile(qual); cw.lneg()
      case "~~" => compile(qual); cw.lconst(-1L); cw.lxor()
      case "*" => binLongOp(cw.lmul)
      case "/" => binLongOp(cw.ldiv)
      case "%" => binLongOp(cw.lrem)
      case "&" => binLongOp(cw.land)
      case "|" => binLongOp(cw.lor)
      case "^" => binLongOp(cw.lxor)
      case "<<" => shiftLongOp(cw.lshl)
      case ">>" => shiftLongOp(cw.lshr)
      case "==" => cmpOp("eq")
      case "!=" => cmpOp("ne")
      case ">" => cmpOp("gt")
      case "<" => cmpOp("lt")
      case ">=" => cmpOp("ge")
      case "<=" => cmpOp("le")
      case "toInt" =>
        compile(qual); cw.l2i()
      case "toLong" =>
        compile(qual)
      case "toString" =>
        compile(qual)
        cw.invokestatic("java/lang/Long", "toString", "(J)Ljava/lang/String;")
      case other =>
        throw new Exception("JVM backend: unsupported Long operator " + other)

  private def boolNot()(using ctx: MethodCtx): Unit =
    boolFromBranch(l => ctx.cw.ifeq(l))

  /** `String`'s `@intrinsic` methods.
    *
    * `+`/`==`/`toLower`/`toUpper` have a direct 1:1 `java.lang.String`
    * counterpart (no semantic gap), so they're compiled the same way
    * Int/Bool arithmetic is: a thin, direct translation, right here.
    *
    * `size`/`get`/`substring`/`indexOf` don't: Jo's contract is explicit
    * that string indices and lengths are Unicode *code point* units, but
    * `java.lang.String` is UTF-16 *code unit* indexed. Bridging that gap is
    * API-level behavior, not language semantics, so it's pushed out to
    * `jo.jvm.runtime.StringOps` (ordinary Jo code over thin `@extern`
    * primitives, in runtime/jvm/Runtime.jo) — compiled here as nothing more
    * than an ordinary static call with `qual` prepended as the first
    * argument, the same as any other function call.
    *
    * `iterator` constructs a real `Iterator[Char]`-implementing object
    * (`jo.jvm.runtime.StringOps.StringIterator`, an ordinary Jo class), so
    * it's dispatched the same way as `size`/`get`/`substring`/`indexOf`.
    */
  def compileString(qual: Word, name: String, args: List[Word])(using ctx: MethodCtx): Unit =
    val cw = ctx.cw

    // Erasure has normalized both operands to String.
    def receiver(): Unit = compile(qual)
    def stringArg(w: Word): Unit = compile(w)

    name match
      case "size" => compileStaticCall(runtime.String_size, qual :: Nil)
      case "get" => compileStaticCall(runtime.String_get, qual :: args)
      case "substring" => compileStaticCall(runtime.String_substring, qual :: args)
      case "indexOf" =>
        val from = if args.size > 1 then args(1) else IntLit(0)(args.head.span)
        compileStaticCall(runtime.String_indexOf, qual :: args.head :: from :: Nil)

      case "+" =>
        receiver(); stringArg(args.head)
        cw.invokevirtual(StringClass, "concat", "(Ljava/lang/String;)Ljava/lang/String;")

      case "==" =>
        receiver(); stringArg(args.head)
        cw.invokevirtual(StringClass, "equals", "(Ljava/lang/Object;)Z")

      case "toLower" =>
        receiver(); cw.invokevirtual(StringClass, "toLowerCase", "()Ljava/lang/String;")

      case "toUpper" =>
        receiver(); cw.invokevirtual(StringClass, "toUpperCase", "()Ljava/lang/String;")

      case "iterator" => compileStaticCall(runtime.String_iterator, qual :: Nil)

      case other =>
        throw new Exception("JVM backend: unsupported String operator " + other)

object PrimitiveOps:
  trait Operands:
    def compile(word: Word)(using MethodContext): MethodBuilder.Flow
    def boolFromBranch(branch: Label => Unit)(using MethodContext): Unit
    def compileStaticCall(symbol: Symbols.Symbol, args: List[Word])(using MethodContext): Unit

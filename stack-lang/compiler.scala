/************************************************************************
 *                                                                      *
 * The compiler implementation of the stack-oriented language.          *
 *                                                                      *
 ************************************************************************/

//> using file ast.scala
//> using file sast.scala
//> using file namer.scala
//> using file parser.scala
//> using file context.scala
//> using file platform.scala
//> using file io.scala
//> using file linux.scala
//> using file x86.scala
//> using file elf32.scala

import scala.collection.mutable


/***********************************************************************
 *
 * Assembly Language Definition
 *
 ***********************************************************************/

object Assembly:
  /** A constant 32-bit integer */
  case class Int32(value: Int)

  /** A register */
  case class Reg(index: Int)

  /** An address relative to the value in the register */
  case class Rel(reg: Int, offset: Byte)

  /** A normal class uses referential equality for better performance. */
  class Label(val name: String):
    override def toString() = "Label(" + name + ")"

  /** An alignment requirement */
  case class Align(n: Int)

  type Operand  = Int32 | Reg
  type Addr     = Label | Reg | Rel
  type CodeAddr = Label | Reg
  type Constant = Int32 | Label
  type Value    = Int32 | Label | Reg
  type Attr     = Label | Align

  enum BiOp:
    case Add, Sub, Mul, Div, Mod, And, Or, Xor, Sll, Srl, Gt, Lt, Ge, Le, Eq

  object Instr:
    def Add(v1: Operand, v2: Operand, destReg: Int) = BinaryOperation(BiOp.Add, v1, v2, destReg)
    def Sub(v1: Operand, v2: Operand, destReg: Int) = BinaryOperation(BiOp.Sub, v1, v2, destReg)
    def Mul(v1: Operand, v2: Operand, destReg: Int) = BinaryOperation(BiOp.Mul, v1, v2, destReg)
    def Div(v1: Operand, v2: Operand, destReg: Int) = BinaryOperation(BiOp.Div, v1, v2, destReg)
    def Mod(v1: Operand, v2: Operand, destReg: Int) = BinaryOperation(BiOp.Mod, v1, v2, destReg)

    def And(v1: Operand, v2: Operand, destReg: Int) = BinaryOperation(BiOp.And, v1, v2, destReg)
    def Or (v1: Operand, v2: Operand, destReg: Int) = BinaryOperation(BiOp.Or,  v1, v2, destReg)
    def Xor(v1: Operand, v2: Operand, destReg: Int) = BinaryOperation(BiOp.Xor, v1, v2, destReg)
    def Sll(v1: Operand, v2: Operand, destReg: Int) = BinaryOperation(BiOp.Sll, v1, v2, destReg)
    def Srl(v1: Operand, v2: Operand, destReg: Int) = BinaryOperation(BiOp.Srl, v1, v2, destReg)

    def Gt(v1: Operand, v2: Operand, destReg: Int) = BinaryOperation(BiOp.Gt, v1, v2, destReg)
    def Lt(v1: Operand, v2: Operand, destReg: Int) = BinaryOperation(BiOp.Lt, v1, v2, destReg)
    def Ge(v1: Operand, v2: Operand, destReg: Int) = BinaryOperation(BiOp.Ge, v1, v2, destReg)
    def Le(v1: Operand, v2: Operand, destReg: Int) = BinaryOperation(BiOp.Le, v1, v2, destReg)
    def Eq(v1: Operand, v2: Operand, destReg: Int) = BinaryOperation(BiOp.Eq, v1, v2, destReg)

  enum Instr:
    case Not(v: Operand, destReg: Int)
    case Const(v: Constant, destReg: Int)
    case BinaryOperation(op: BiOp, v1: Operand, v2: Operand, destReg: Int)

    case Store(v: Value, addr: Addr)
    case Load(addr: Addr, destReg: Int)

    case Jump(addr: CodeAddr)
    case JZero(reg: Reg, label: Label)

  enum Type:
    case Int8, Int32, Int64

  enum Data:
    case String(v: Array[Byte])
    case Int8(v: Byte)
    case Int32(v: Int)
    case Int64(v: Long)
    case Uninit(tp: Type)

  case class Prog(data: List[Data | Attr], instrs: List[Instr | Label], entry: Label):
    def show() =
      val sb = new StringBuilder

      for item <- data do
        item match
          case Data.String(v)   => sb.append("  " + new String(v) + "\n")
          case Data.Int8(v)     => sb.append("  " + v  + "\n")
          case Data.Int32(v)    => sb.append("  " + v + "\n")
          case Data.Int64(v)    => sb.append("  " + v + "\n")
          case Data.Uninit(tp)  => sb.append("  " + tp + "\n")
          case Align(n)         => sb.append(".align " + n + "\n")
          case l: Label         => sb.append(l.name + ":" + "\n")

      for instr <- instrs do
        instr match
          case l: Label =>
            sb.append(l.name + ":" + "\n")

          case _ =>
            sb.append("  " + instr + "\n")
      end for

      sb.toString()

/***********************************************************************
 *
 * Data Definitions for Compilation
 *
 ***********************************************************************/
import Assembly.*

enum Denotation:
  case Data(addr: Label)
  case Fun(addr: Label)
  case Prim(fun: Context => Unit)

enum Scope:
  case RootScope()
  case NestedScope(outer: Scope)

  private val map: mutable.Map[Sast.Symbol, Denotation] = mutable.Map.empty

  def resolve(sym: Sast.Symbol): Option[Denotation] =
    map.get(sym) match
      case None =>
        this match
          case NestedScope(outer) => outer.resolve(sym)
          case _ => None

      case res  => res

  def bind(sym: Sast.Symbol, denot: Denotation): Unit =
    map.get(sym) match
      case None =>
        map(sym) = denot

      case Some(d) =>
        throw new Exception(sym.name + " is already bound to " + d)

object Scope:
  def createRootScope() =
    val rootScope = new Scope.RootScope()
    for (k, v) <- Primitive.operators do
      rootScope.bind(k, Denotation.Prim(v))

    rootScope


object Primitive:
  def int2(fn: (Int, Int, Int) => Instr)(ctx: Context) =
    // TODO: check type of value
    ctx.useTwoReg: (r1, r2) =>
      ctx.pop(r2)
      ctx.pop(r1)
      ctx.add(fn(r1, r2, r1))
      ctx.push(Reg(r1))

  def add(ctx: Context) =
    int2((r1, r2, d) => Instr.Add(Reg(r1), Reg(r2), d))(ctx)

  def sub(ctx: Context) =
    int2((r1, r2, d) => Instr.Sub(Reg(r1), Reg(r2), d))(ctx)

  def mul(ctx: Context) =
    int2((r1, r2, d) => Instr.Mul(Reg(r1), Reg(r2), d))(ctx)

  def div(ctx: Context) =
    int2((r1, r2, d) => Instr.Div(Reg(r1), Reg(r2), d))(ctx)

  def mod(ctx: Context) =
    int2((r1, r2, d) => Instr.Mod(Reg(r1), Reg(r2), d))(ctx)

  def lt(ctx: Context) =
    int2((r1, r2, d) => Instr.Lt(Reg(r1), Reg(r2), d))(ctx)

  def gt(ctx: Context) =
    int2((r1, r2, d) => Instr.Gt(Reg(r1), Reg(r2), d))(ctx)

  def le(ctx: Context) =
    int2((r1, r2, d) => Instr.Le(Reg(r1), Reg(r2), d))(ctx)

  def ge(ctx: Context) =
    int2((r1, r2, d) => Instr.Ge(Reg(r1), Reg(r2), d))(ctx)

  def sll (ctx: Context) =
    int2((r1, r2, d) => Instr.Sll(Reg(r1), Reg(r2), d))(ctx)

  def srl (ctx: Context) =
    int2((r1, r2, d) => Instr.Srl(Reg(r1), Reg(r2), d))(ctx)

  def land(ctx: Context) =
    int2((r1, r2, d) => Instr.And(Reg(r1), Reg(r2), d))(ctx)

  def lor (ctx: Context) =
    int2((r1, r2, d) => Instr.Or(Reg(r1), Reg(r2), d))(ctx)

  def lxor(ctx: Context) =
    int2((r1, r2, d) => Instr.Xor(Reg(r1), Reg(r2), d))(ctx)

  def band(ctx: Context) =
    int2((r1, r2, d) => Instr.And(Reg(r1), Reg(r2), d))(ctx)

  def bor (ctx: Context) =
    int2((r1, r2, d) => Instr.Or(Reg(r1), Reg(r2), d))(ctx)

  def bnot(ctx: Context) =
    ctx.useReg: r =>
      ctx.pop(r)
      ctx.add(Instr.Not(Reg(r), r))
      ctx.add(Instr.And(Reg(r), Int32(1), r))
      ctx.push(Reg(r))

  def run(ctx: Context) =
    // TODO: check type of value
    ctx.useReg: r =>
      ctx.pop(r)
      ctx.call(Reg(r))

  def eql(ctx: Context) =
    ctx.useTwoReg: (r1, r2) =>
      ctx.pop(r1)
      ctx.pop(r2)
      ctx.add(Instr.Eq(Reg(r1), Reg(r2), r2))
      ctx.push(Reg(r2))

  def dup(ctx: Context) = ctx.duplicate()

  def swap(ctx: Context) = ctx.swap()

  def pop(ctx: Context) = ctx.pop()

  def peek(ctx: Context) = ctx.peek()

  def choose(ctx: Context) = ctx.choose()

  def print(ctx: Context) = ctx.print()

  import Sast.{ predefs => defn }

  val operators: Map[Sast.Symbol, Context => Unit] = Map(
      defn.add    ->    add,
      defn.sub    ->    sub,
      defn.mul    ->    mul,
      defn.div    ->    div,
      defn.mod    ->    mod,
      defn.gt     ->    gt,
      defn.lt     ->    lt,
      defn.ge     ->    ge,
      defn.le     ->    le,
      defn.srl    ->    srl,
      defn.sll    ->    sll,
      defn.land   ->    land,
      defn.lor    ->    lor,
      defn.lxor   ->    lxor,
      defn.band   ->    band,
      defn.bor    ->    bor,
      defn.bnot   ->    bnot,
      defn.run    ->    run,
      defn.eql    ->    eql,
      defn.dup    ->    dup,
      defn.swap   ->    swap,
      defn.peek   ->    peek,
      defn.pop    ->    pop,
      defn.choose ->    choose,
      defn.p      ->    print,
    )

/***********************************************************************
 *
 * Compiler
 *
 ***********************************************************************/

object Compiler:
  def compile(prog: Sast.Prog)(using ctx: Context): Unit =
    val startLabel = Label(ctx.freshName("_start"))

    // initialize runtime
    ctx.addCodeLabel(ctx.entry)
    ctx.initCode(startLabel)

    // Create labels for all definitions to support recursive definitions
    for defn <- prog.defs do
      val label = Label(ctx.freshName(defn.name))
      defn match
        case _: Sast.Def.ValDef =>
          ctx.bind(defn.symbol, Denotation.Data(label))
          ctx.addDataLabel(label)
          ctx.add(Data.Uninit(Type.Int32))

        case _: Sast.Def.FunDef =>
          ctx.bind(defn.symbol, Denotation.Fun(label))
    end for

    // Compile functions
    for case Sast.Def.FunDef(sym, words) <- prog.defs do
      val Some(Denotation.Fun(label)) = ctx.resolve(sym): @unchecked
      ctx.addCodeLabel(label)
      compile(words)
      summon[Context].ret()
    end for

    // User code execution starts from here

    // Create initializer for value definitions
    ctx.addCodeLabel(startLabel)
    for case Sast.Def.ValDef(sym, words) <- prog.defs do
      compile(words)
      val Some(Denotation.Data(label)) = ctx.resolve(sym): @unchecked
      ctx.useReg: r =>
        ctx.pop(r)
        ctx.add(Instr.Store(Reg(r), label))
    end for

    // Compile the main phrase
    val mainLabel = Label(ctx.freshName("_main"))
    ctx.addCodeLabel(mainLabel)
    compile(prog.main)

    ctx.exitCode()

  def compile(words: List[Sast.Word])(using Context): Unit =
    for word <- words do compile(word)

  def compile(word: Sast.Word)(using ctx: Context): Unit =
    word match
      case Sast.Word.IntLit(v)  => ctx.push(Int32(v))

      case Sast.Word.BoolLit(v) => ctx.push(Int32(if v then 1 else 0))

      case Sast.Word.Proc(words) =>
        // case class Patch(index: Int, code: => Int)

        val labelStart = Label(ctx.freshName("proc_start"))
        val labelEnd = Label(ctx.freshName("proc_end"))

        ctx.add(Instr.Jump(labelEnd))
        ctx.addCodeLabel(labelStart)
        compile(words)
        ctx.ret()
        ctx.addCodeLabel(labelEnd)

        ctx.push(labelStart)

      case Sast.Word.Ident(sym) =>
        ctx.resolve(sym) match
          case Some(d) =>
            d match
              case Denotation.Fun(addr)  => ctx.call(addr)

              case Denotation.Prim(fun)  => fun(ctx)

              case Denotation.Data(addr) =>
                ctx.useReg: r =>
                  ctx.add(Instr.Load(addr, r))
                  ctx.push(Reg(r))


          case None =>
            throw new Exception("Undefined identifier " + sym)


/***********************************************************************
 *
 * Main entry point
 *
 ***********************************************************************/
@main
def run(sourceFile: String) =
  val platform: Linux.X86Platform = new Linux.X86Platform
  val ctx: Context = Context.createContext(platform)
  val ast = Parsing.parse(IO.fileContent(sourceFile))
  val sast = Namer.transform(ast)

  Compiler.compile(sast)(using ctx)
  val asm = ctx.getResult()

  val tokens = sourceFile.split("\\.(?=[^\\.]+$)")
  IO.withExeFile(tokens(0)): bb =>
    platform.lower(asm)(using bb)

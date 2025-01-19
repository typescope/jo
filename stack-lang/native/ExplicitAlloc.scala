package native

import ast.Positions.Source
import sast.*
import sast.Sast.*
import sast.Symbols.*
import sast.Types.*

import native.runtime.NativeRuntime

import scala.collection.mutable

/** The compiler phase translate context parameters to runtime calls
  *
  * This phase assumes the following support functions defined in
  * runtime/native/Core.stk:
  *
  *     fun alloc(size: Int): Addr = ...
  *     fun addAddr(arr: Addr, offset: Int): Addr = ...
  */
class ExplicitAlloc(runtime: NativeRuntime) extends SastOps.TreeMap:
  class FunContext(val funSymbol: Symbol, val locals: mutable.ArrayBuffer[Symbol])

  type Context = FunContext

  def transform(nss: List[Namespace]): List[Namespace] =
    for ns <- nss yield transformNamespace(ns)

  def transformNamespace(ns: Namespace): Namespace =
    val funs =
      for case fdef: FunDef <- ns.defs
      yield
        val locals2 = mutable.ArrayBuffer.from(fdef.locals)
        given Context = FunContext(fdef.symbol, locals2)
        val body2 = this(fdef.body)
        fdef.copy(body = body2)(locals2.toList, fdef.span)

    Namespace(ns.symbol, ns.imports, funs)(ns.span)

  override def apply(word: Word)(using ctx: Context): Word =
    word match
      case RecordLit(args) =>
        val stats = new mutable.ArrayBuffer[Word]
        val allocFun = Ident(runtime.GC_alloc)(word.span)
        val addrType = TypeRef(runtime.Core_Addr)

        val recordType = word.tpe.asRecordType
        val size = Memory.size(recordType)
        val sizeLit = IntLit(size)(word.span)
        val allocApply = Apply(allocFun, sizeLit :: Nil)(addrType, word.span)

        val refSym =
          given Source = ctx.funSymbol.sourcePos.source
          Symbol.createValueSymbol("ref", addrType, ctx.funSymbol, word.pos)
        ctx.locals += refSym
        val ref = Ident(refSym)(word.span)

        stats += Assign(ref, allocApply)(word.span)

        for (name, rhs) <- args do
          stats += Memory.writeField(recordType, name, ref, this(rhs), runtime)

        stats += ref
        Encoded(Block(stats.toList)(ref.tpe, word.span))(word.tpe)

      case select @ Select(qual, _) =>
        // readInt (addAddr qual offset)
        val recordType =
          if qual.tpe.isRecordType then qual.tpe.asRecordType
          else Memory.toRecordType(qual.tpe.asObjectType)

        val select2 = select.copy(qual = this(qual))(select.tpe, select.span)

        Memory.readField(recordType, select2, runtime)

      case FieldAssign(qual, name, rhs) =>
        // Only object is mutable
        val recordType = Memory.toRecordType(qual.tpe.asObjectType)
        Memory.writeField(recordType, name, this(qual), this(rhs), runtime)

      case _ => recur(word)

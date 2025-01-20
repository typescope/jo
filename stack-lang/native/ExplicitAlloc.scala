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

  val Predef_array = Definitions.instance.Predef_array

  def transform(nss: List[Namespace]): List[Namespace] =
    for ns <- nss yield transformNamespace(ns)

  def transformNamespace(ns: Namespace): Namespace =
    val defs = ns.defs.map:
      case fdef: FunDef =>
        val locals2 = mutable.ArrayBuffer.from(fdef.locals)
        given Context = FunContext(fdef.symbol, locals2)
        val body2 = this(fdef.body)
        fdef.copy(body = body2)(locals2.toList, fdef.span)

      case defn => defn

    Namespace(ns.symbol, ns.imports, defs)(ns.span)

  override def apply(word: Word)(using ctx: Context): Word =
    word match
      case encode @ Encoded(rc: RecordLit) if encode.tpe.isObjectType =>
        val encoding = this(Memory.encodeObject(rc))
        Encoded(encoding)(encode.tpe)

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
        val select2 = select.copy(qual = this(qual))(select.tpe, select.span)

        if qual.tpe.isRecordType then
          val recordType = qual.tpe.asRecordType
          Memory.readField(recordType, select2, runtime)
        else
          Memory.readObjectMember(qual.tpe.asObjectType, select2, runtime)

      case FieldAssign(qual, name, rhs) =>
        // Only object is mutable
        val objectType = qual.tpe.asObjectType
        Memory.writeObjectField(objectType, name, this(qual), this(rhs), runtime)

      case Apply(TypeApply(fun @ Ident(sym), tpt :: Nil), arg :: Nil) if sym == Predef_array  =>
        val fun2 = Ident(runtime.Core_arrayCreate)(fun.span)
        Encoded(Apply(fun2, this(arg) :: Nil)(AnyType, word.span))(word.tpe)

      case _ => recur(word)

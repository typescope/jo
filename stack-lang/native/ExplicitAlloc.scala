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
class ExplicitAlloc(runtime: NativeRuntime) extends phases.Phase:

  class FunContext(val funSymbol: Symbol)
  type Context = FunContext
  def createContext(fdef: FunDef) = FunContext(fdef.symbol)

  override def transformEncoded(word: Encoded)(using ctx: Context): Word =
    word match
      case encode @ Encoded(rc: RecordLit) if encode.tpe.isObjectType =>
        val encoding = this(Memory.encodeObject(rc))
        Encoded(encoding)(encode.tpe)

      case _ =>
        super.transformEncoded(word)

  override def transformRecord(word: RecordLit)(using ctx: Context): Word =
    val RecordLit(args) = word
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
    val ref = Ident(refSym)(word.span)

    stats += Assign(ref, allocApply)(word.span)

    for (name, rhs) <- args do
      stats += Memory.writeField(recordType, name, ref, this(rhs), runtime)

    stats += ref
    Encoded(Block(stats.toList)(ref.tpe, word.span))(word.tpe)

  override def transformSelect(select: Select)(using ctx: Context): Word =
    val qual = select.qual
    val select2 = select.copy(qual = this(qual))(select.tpe, select.span)

    if qual.tpe.isRecordType then
      val recordType = qual.tpe.asRecordType
      Memory.readField(recordType, select2, runtime)
    else
      Memory.readObjectMember(qual.tpe.asObjectType, select2, runtime)

  override def transformFieldAssign(word: FieldAssign)(using ctx: Context): Word =
    val FieldAssign(qual, name, rhs) = word
    // Only object is mutable
    val objectType = qual.tpe.asObjectType
    Memory.writeObjectField(objectType, name, this(qual), this(rhs), runtime)

package native

import ast.Positions.Source
import sast.*
import sast.Trees.*
import sast.Symbols.*
import sast.Types.*

import native.runtime.NativeRuntime

import scala.collection.mutable

/** The compiler phase makes allocation of records explicit
  *
  * This phase assumes the following support functions defined in
  * runtime/native/Core.jo:
  *
  *     fun alloc(size: Int): Addr = ...
  *     fun addAddr(arr: Addr, offset: Int): Addr = ...
  */
class ExplicitAlloc(runtime: NativeRuntime)(using defn: Definitions) extends phases.Phase[Symbol]:
  val contextObject = phases.Phase.OwnerContext

  val memory = new Memory(runtime)

  override def transformEncoded(word: Encoded)(using ctx: Context): Word =
    word match
      case encode @ Encoded(rc: RecordLit) if encode.tpe.isObjectType =>
        val encoding = this(ObjectEncoding.encodeObject(rc))
        Encoded(encoding)(encode.tpe)

      case _ =>
        super.transformEncoded(word)

  override def transformRecord(word: RecordLit)(using ctx: Context): Word =
    val RecordLit(args) = word
    val stats = new mutable.ArrayBuffer[Word]
    val allocFun = Ident(runtime.GC_alloc)(word.span)
    val addrType = StaticRef(runtime.Core_Addr)

    given Source = ctx.sourcePos.source

    val recordType = word.tpe.asRecordType
    val size = memory.size(recordType)
    val sizeLit = Literal(Constant.Int(size))(defn.IntType, word.span)
    val allocApply = allocFun.appliedTo(sizeLit)

    val refSym =
      TermSymbol.create("ref", addrType, Flags.Synthetic, Visibility.Default, ctx, word.pos)
    val ref = Ident(refSym)(word.span)

    stats += Assign(ref, allocApply)

    for (name, rhs) <- args do
      stats += memory.writeField(recordType, name, ref, this(rhs))

    stats += ref
    Encoded(Block(stats.toList)(word.span))(word.tpe)

  private def getEncodedRecordType(qual: Word): RecordType =
    if qual.tpe.isRecordType then
      qual.tpe.asRecordType

    else if qual.tpe.isClassType then
      ObjectEncoding.encodeClassType(qual.tpe.asClassInfo)

    else
      throw new Exception("Unexpect qualifier type in selection: " + qual.tpe)


  override def transformSelect(select: Select)(using ctx: Context): Word =
    val qual = select.qual
    val select2 = select.copy(qual = this(qual))(select.tpe, select.span)

    given Source = ctx.sourcePos.source

    if qual.tpe.isObjectType then
      memory.readObjectMember(qual.tpe.asObjectType, select2)

    else
      val recordType = getEncodedRecordType(qual)
      memory.readField(recordType, select2)

  override def transformFieldAssign(word: FieldAssign)(using ctx: Context): Word =
    val FieldAssign(Select(qual, name), rhs) = word

    given Source = ctx.sourcePos.source

    if qual.tpe.isObjectType then
      val objectType = qual.tpe.asObjectType
      memory.writeObjectField(objectType, name, this(qual), this(rhs))

    else
      val recordType = getEncodedRecordType(qual)
      memory.writeField(recordType, name, this(qual), this(rhs))

package native

import sast.Constant
import sast.Types.*
import sast.Sast.*
import sast.Definitions

import native.runtime.NativeRuntime

/** Runtime memory representation of records and objects
  *
  * An object is encoded as follows:
  *
  *     {
  *         vtable = { foo = ..., bar = ... },
  *         ftable = { a = ..., b = ... }
  *     }
  */
class Memory(runtime: NativeRuntime)(using defn: Definitions):
  private val VTABLE = "vtable"
  private val FTABLE = "ftable"

  val IntType = defn.IntType
  val AddrType = StaticRef(runtime.Core_Addr)

  /** Size of the recrod in bytes */
  def size(recordType: RecordType): Int =
    recordType.fields.size << 2

  /** Offset relative to the start of the record in bytes */
  def fieldOffset(recordType: RecordType, field: String): Int =
    val index = recordType.fields.toList.indexWhere(_.name == field)
    assert(index >= 0, field + " not found in " + recordType.show)
    index << 2

  /** Convert object type to record type for native platform */
  def toRecordType(objType: ObjectType): RecordType =
    val ftable = RecordType(objType.fields)
    val vtable = RecordType(objType.methods)
    RecordType(NamedInfo(VTABLE, vtable) :: NamedInfo(FTABLE, ftable) :: Nil)

  def encodeObject(obj: RecordLit): Word =
    val fields = obj.args.filter { case (name, rhs) => rhs.tpe.isValueType }
    val fieldTypes = fields.map { case (name, rhs) => NamedInfo(name, rhs.tpe) }
    val ftableType = RecordType(fieldTypes)
    val ftable = RecordLit(fields)(ftableType, obj.span)

    val methods = obj.args.filter { case (name, rhs) => rhs.tpe.isProcType }
    val methodTypes = methods.map { case (name, rhs) => NamedInfo(name, rhs.tpe) }

    val vtableType = RecordType(methodTypes)
    val vtable = RecordLit(methods)(vtableType, obj.span)

    val closureType = RecordType(
        NamedInfo(VTABLE, vtableType) :: NamedInfo(FTABLE, ftableType) :: Nil)

    RecordLit(List(VTABLE -> vtable, FTABLE -> ftable))(closureType, obj.span)

  def readObjectMember(objType: ObjectType, select: Select): Word =
    val recordType = toRecordType(objType)
    if select.tpe.isValueType then
      val tableType = recordType.termMember(FTABLE).asRecordType
      val tableSelect = Select(select.qual, FTABLE)(tableType, select.span)
      val table = readField(recordType, tableSelect)
      val fieldSelect = Select(table, select.name)(select.tpe, select.span)
      readField(tableType, fieldSelect)
    else
      val tableType = recordType.termMember(VTABLE).asRecordType
      val tableSelect = Select(select.qual, VTABLE)(tableType, select.span)
      val table = readField(recordType, tableSelect)
      val methodSelect = Select(table, select.name)(select.tpe, select.span)
      readField(tableType, methodSelect)

  def writeObjectField(objType: ObjectType, field: String, ref: Word, rhs: Word): Word =
    val recordType = toRecordType(objType)
    val tableType = recordType.termMember(FTABLE).asRecordType
    val tableSelect = Select(ref, FTABLE)(tableType, rhs.span)
    val table = readField(recordType, tableSelect)
    writeField(tableType, field, table, rhs)

  def writeField(recordType: RecordType, field: String, ref: Word, rhs: Word): Word =
    val offset = fieldOffset(recordType, field)
    var addr: Word = Encoded(ref)(AddrType)
    if offset != 0 then
      val offsetLit = Literal(Constant.Int(offset))(IntType, rhs.span)
      val addAddrFun = Ident(runtime.Core_addAddr)(rhs.span)
      addr = Apply(addAddrFun, Encoded(ref)(AddrType) :: offsetLit :: Nil)(AddrType, rhs.span)

    val writeIntFun = Ident(runtime.Core_writeInt)(rhs.span)
    Apply(writeIntFun, addr :: Encoded(rhs)(IntType) :: Nil)(IntType, rhs.span).dropValue

  def readField(recordType: RecordType, select: Select): Word =
    val Select(qual, field) = select
    val offset = fieldOffset(recordType, field)
    var addr: Word = Encoded(qual)(AddrType)
    if offset != 0 then
      val offsetLit = Literal(Constant.Int(offset))(IntType, select.span)
      val addAddrFun = Ident(runtime.Core_addAddr)(select.span)
      addr = Apply(addAddrFun, Encoded(qual)(AddrType) :: offsetLit :: Nil)(AddrType, select.span)

    val readIntFun = Ident(runtime.Core_readInt)(select.span)
    Encoded(Apply(readIntFun, addr :: Nil)(IntType, select.span))(select.tpe)

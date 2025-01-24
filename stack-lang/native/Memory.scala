package native

import sast.Constant
import sast.Types.*
import sast.Sast.*

import native.runtime.NativeRuntime

/** Code related to runtime memory
  */
object Memory:
  private val VTABLE = "vtable"
  private val FTABLE = "ftable"

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

  def readObjectMember(objType: ObjectType, select: Select, runtime: NativeRuntime): Word =
    val recordType = toRecordType(objType)
    if select.tpe.isValueType then
      val tableType = recordType.termMember(FTABLE).asRecordType
      val tableSelect = Select(select.qual, FTABLE)(tableType, select.span)
      val table = readField(recordType, tableSelect, runtime)
      val fieldSelect = Select(table, select.name)(select.tpe, select.span)
      readField(tableType, fieldSelect, runtime)
    else
      val tableType = recordType.termMember(VTABLE).asRecordType
      val tableSelect = Select(select.qual, VTABLE)(tableType, select.span)
      val table = readField(recordType, tableSelect, runtime)
      val methodSelect = Select(table, select.name)(select.tpe, select.span)
      readField(tableType, methodSelect, runtime)

  def writeObjectField(objType: ObjectType, field: String, ref: Word, rhs: Word, runtime: NativeRuntime): Word =
    val recordType = toRecordType(objType)
    val tableType = recordType.termMember(FTABLE).asRecordType
    val tableSelect = Select(ref, FTABLE)(tableType, rhs.span)
    val table = readField(recordType, tableSelect, runtime)
    writeField(tableType, field, table, rhs, runtime)

  def writeField(recordType: RecordType, field: String, ref: Word, rhs: Word, runtime: NativeRuntime): Word =
    val IntType = Definitions.instance.IntType
    val offset = Memory.fieldOffset(recordType, field)
    val addr =
      if offset == 0 then
        ref
      else
        val offsetLit = Literal(Constant.Int(offset))(IntType, rhs.span)
        val addAddrFun = Ident(runtime.Core_addAddr)(rhs.span)
        Apply(addAddrFun, ref :: offsetLit :: Nil)(TypeRef(runtime.Core_Addr), rhs.span)

    val writeIntFun = Ident(runtime.Core_writeInt)(rhs.span)
    Apply(writeIntFun, addr :: rhs :: Nil)(VoidType, rhs.span)

  def readField(recordType: RecordType, select: Select, runtime: NativeRuntime): Word =
    val IntType = Definitions.instance.IntType

    val Select(qual, field) = select
    val offset = Memory.fieldOffset(recordType, field)
    val addr =
      if offset == 0 then
        qual
      else
        val offsetLit = Literal(Constant.Int(offset))(IntType, select.span)
        val addAddrFun = Ident(runtime.Core_addAddr)(select.span)
        Apply(addAddrFun, qual :: offsetLit :: Nil)(TypeRef(runtime.Core_Addr), select.span)

    val readIntFun = Ident(runtime.Core_readInt)(select.span)
    Encoded(Apply(readIntFun, addr :: Nil)(IntType, select.span))(select.tpe)

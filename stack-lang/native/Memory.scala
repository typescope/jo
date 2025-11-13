package native

import sast.Constant
import sast.Types.*
import sast.Trees.*
import sast.Definitions

import ast.Positions.Source
import native.runtime.NativeRuntime

/** Runtime memory representation of records */
class Memory(runtime: NativeRuntime)(using defn: Definitions):
  val IntType = defn.IntType
  val AddrType = StaticRef(runtime.Core_Addr)

  /** Size of the recrod in bytes */
  def size(recordType: RecordType): Int =
    recordType.fields.size << 2

  /** Offset relative to the start of the record in bytes */
  def fieldOffset(recordType: RecordType, field: String): Int =
    val index = recordType.fields.indexWhere(_.name == field)
    assert(index >= 0, field + " not found in " + recordType.show)
    index << 2

  def writeField(recordType: RecordType, field: String, ref: Word, rhs: Word)(using Source): Word =
    val offset = fieldOffset(recordType, field)
    var addr: Word = Encoded(ref)(AddrType)
    if offset != 0 then
      val offsetLit = Literal(Constant.Int(offset))(IntType, rhs.span)
      val addAddrFun = Ident(runtime.Core_addAddr)(rhs.span)
      addr = addAddrFun.appliedTo(Encoded(ref)(AddrType), offsetLit)

    val writeIntFun = Ident(runtime.Core_writeInt)(rhs.span)
    writeIntFun.appliedTo(addr, Encoded(rhs)(IntType)).dropValue

  def readField(recordType: RecordType, select: Select)(using Source): Word =
    val Select(qual, field) = select
    val offset = fieldOffset(recordType, field)
    var addr: Word = Encoded(qual)(AddrType)
    if offset != 0 then
      val offsetLit = Literal(Constant.Int(offset))(IntType, select.span)
      val addAddrFun = Ident(runtime.Core_addAddr)(select.span)
      addr = addAddrFun.appliedTo(Encoded(qual)(AddrType), offsetLit)

    val readIntFun = Ident(runtime.Core_readInt)(select.span)
    Encoded(readIntFun.appliedTo(addr))(select.tpe)

  def readObjectMember(objType: ObjectType, select: Select)(using Source): Word =
    val recordType = ObjectEncoding.encodeObjectType(objType)
    if select.tpe.isValueType then
      val tableType = recordType.termMember(ObjectEncoding.FTABLE).asRecordType
      val tableSelect = Select(select.qual, ObjectEncoding.FTABLE)(tableType, select.span)
      val table = readField(recordType, tableSelect)
      val fieldSelect = Select(table, select.name)(select.tpe, select.span)
      readField(tableType, fieldSelect)

    else
      val tableType = recordType.termMember(ObjectEncoding.VTABLE).asRecordType
      val tableSelect = Select(select.qual, ObjectEncoding.VTABLE)(tableType, select.span)
      val table = readField(recordType, tableSelect)
      val methodSelect = Select(table, select.name)(select.tpe, select.span)
      readField(tableType, methodSelect)

  def writeObjectField(objType: ObjectType, field: String, ref: Word, rhs: Word)(using Source): Word =
    val recordType = ObjectEncoding.encodeObjectType(objType)
    val tableType = recordType.termMember(ObjectEncoding.FTABLE).asRecordType
    val tableSelect = Select(ref, ObjectEncoding.FTABLE)(tableType, rhs.span)
    val table = readField(recordType, tableSelect)
    writeField(tableType, field, table, rhs)

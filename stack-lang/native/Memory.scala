package native

import sast.Types.*
import sast.Sast.*

import native.runtime.NativeRuntime

/** Code related to runtime memory
  */
object Memory:
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
    RecordType(objType.members)


  def writeField(recordType: RecordType, field: String, ref: Word, rhs: Word, runtime: NativeRuntime): Word =
    val offset = Memory.fieldOffset(recordType, field)
    val offsetLit = IntLit(offset)(rhs.span)
    val addAddrFun = Ident(runtime.Core_addAddr)(rhs.span)
    val addr = Apply(addAddrFun, ref :: offsetLit :: Nil)(TypeRef(runtime.Core_Addr), rhs.span)

    val writeIntFun = Ident(runtime.Core_writeInt)(rhs.span)
    Apply(writeIntFun, addr :: rhs :: Nil)(VoidType, rhs.span)

  def readField(recordType: RecordType, select: Select, runtime: NativeRuntime): Word =
    val Select(qual, field) = select
    val offset = Memory.fieldOffset(recordType, field)
    val offsetLit = IntLit(offset)(select.span)
    val addAddrFun = Ident(runtime.Core_addAddr)(select.span)
    val addr = Apply(addAddrFun, qual :: offsetLit :: Nil)(TypeRef(runtime.Core_Addr), select.span)

    val readIntFun = Ident(runtime.Core_readInt)(select.span)
    Encoded(Apply(readIntFun, addr :: Nil)(IntType, select.span))(select.tpe)

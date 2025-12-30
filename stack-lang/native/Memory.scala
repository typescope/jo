package native

import sast.*
import sast.Types.*
import sast.Trees.*

import ast.Positions.Source
import native.runtime.NativeRuntime

import scala.collection.mutable

/** Runtime memory representation of records and objects
  *
  * Class instances are encoded as follows:
  *
  *     {
  *         cid = ...,
  *         a = ... ,
  *         b = ... ,
  *     }
  *
  * A closure object is encoded as follows:
  *
  *     {
  *         vtable = { foo = ..., bar = ... },
  *         ftable = { a = ..., b = ... }
  *     }
  *
  * An interface object is encoded as follows:
  *
  *     {
  *         vtable = { foo = ..., bar = ... },
  *         underlying = ...
  *     }
  *
  * A lambda closure is encoded as follows:
  *
  *     {
  *         apply = ...,
  *         underlying = ...
  *     }
  *
  * The encoding is implementation details and is subject to change.
  */
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

  def writeMember(recordType: RecordType, field: String, ref: Word, rhs: Word)(using Source): Word =
    val offset = fieldOffset(recordType, field)
    var addr: Word = Encoded(ref)(AddrType)
    if offset != 0 then
      val offsetLit = Literal(Constant.Int(offset))(IntType, rhs.span)
      val addAddrFun = Ident(runtime.Core_addAddr)(rhs.span)
      addr = addAddrFun.appliedTo(Encoded(ref)(AddrType), offsetLit)

    val writeIntFun = Ident(runtime.Core_writeInt)(rhs.span)
    writeIntFun.appliedTo(addr, Encoded(rhs)(IntType)).dropValue

  def readMember(recordType: RecordType, select: Select)(using Source): Word =
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
    val recordType = Memory.encodeObjectType(objType)
    if select.tpe.isValueType then
      val tableType = recordType.termMember(Memory.FTable).asRecordType
      val tableSelect = Select(Encoded(select.qual)(recordType), Memory.FTable)(select.span)
      val table = readMember(recordType, tableSelect)
      val fieldSelect = Select(table, select.name)(select.span)
      readMember(tableType, fieldSelect)

    else
      val tableType = recordType.termMember(Memory.VTable).asRecordType
      val tableSelect = Select(Encoded(select.qual)(recordType), Memory.VTable)(select.span)
      val table = readMember(recordType, tableSelect)
      val methodSelect = Select(table, select.name)(select.span)
      readMember(tableType, methodSelect)

  def writeObjectMember(objType: ObjectType, field: String, ref: Word, rhs: Word)(using Source): Word =
    val recordType = Memory.encodeObjectType(objType)
    val tableType = recordType.termMember(Memory.FTable).asRecordType
    val tableSelect = Select(Encoded(ref)(recordType), Memory.FTable)(rhs.span)
    val table = readMember(recordType, tableSelect)
    writeMember(tableType, field, table, rhs)

  def writeClassMember(classInfo: ClassInfo, member: String, ref: Word, rhs: Word)(using Source): Word =
    val recordType = Memory.encodeClassType(classInfo)
    assert(classInfo.termMember(member).isValueType, "Expect value type, found = " + classInfo.termMember(member).show)
    writeMember(recordType, member, ref, rhs)

  def readClassMember(classInfo: ClassInfo, select: Select)(using Source): Word =
    val recordType = Memory.encodeClassType(classInfo)
    assert(select.tpe.isValueType, "Expect value type, found = " + select.tpe.show)
    readMember(recordType, select)

  def readInterfaceMember(interfaceInfo: ClassInfo, select: Select)(using Source): Word =
    val recordType = Memory.encodeInterfaceType(interfaceInfo)
    assert(select.tpe.isProcType, "Expect proc type, found = " + select.tpe.show)

    val tableType = recordType.termMember(Memory.VTable).asRecordType
    val tableSelect = Select(Encoded(select.qual)(recordType), Memory.VTable)(select.span)
    val table = readMember(recordType, tableSelect)
    val methodSelect = Select(table, select.name)(select.span)
    readMember(tableType, methodSelect)

object Memory:
  val VTable = "vtable"
  val FTable = "ftable"
  val Underlying = "underlying"
  val ClassID = "cid"
  val Apply = "apply"

  def encodeObjectType(objType: ObjectType): RecordType =
    val ftable = RecordType(objType.fields)
    val vtable = RecordType(objType.methods)
    RecordType(NamedInfo(VTable, vtable) :: NamedInfo(FTable, ftable) :: Nil)

  def encodeClassType(classInfo: ClassInfo)(using defn: Definitions): RecordType =
    val memberTypes = new mutable.ArrayBuffer[NamedInfo[Type]]
    memberTypes += NamedInfo(ClassID, defn.IntType)

    for field <- classInfo.fields do
      memberTypes += field.toNamedInfo

    RecordType(memberTypes.toList)

  def encodeInterfaceType(interfaceInfo: ClassInfo)(using Definitions): RecordType =
    val memberTypes = new mutable.ArrayBuffer[NamedInfo[Type]]
    for meth <- interfaceInfo.methods if meth.is(Flags.Defer) do
      memberTypes += meth.toNamedInfo

    val vtable = RecordType(memberTypes.toList)
    RecordType(NamedInfo(VTable, vtable) :: NamedInfo(Underlying, AnyType) :: Nil)

  def encodeLambdaType(lambdaType: LambdaType)(using Definitions): RecordType =
    val apply = NamedInfo(Memory.Apply, lambdaType.toProcType)
    val underlying = NamedInfo(Memory.Underlying, AnyType)
    RecordType(apply :: underlying :: Nil)

  def encodeObject(obj: RecordLit)(using Definitions): Word =
    val fields = obj.args.filter { case (name, rhs) => rhs.tpe.isValueType }
    val ftable = RecordLit(fields)(obj.span)

    val methods = obj.args.filter { case (name, rhs) => rhs.tpe.isProcType }
    val vtable = RecordLit(methods)(obj.span)

    RecordLit(List(Memory.VTable -> vtable, Memory.FTable -> ftable))(obj.span)

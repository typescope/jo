package llvm

import sast.*
import sast.Types.*
import sast.Denotations.ClassInfo

import scala.collection.mutable

/** Runtime memory representation of records and objects
  *
  * Every heap object is a sequence of 8-byte slots. A record field takes one
  * slot. The first field is always the class id, an `i32` in the low half of
  * slot 0. The high half of slot 0 is reserved for the garbage collector.
  *
  * Class instances are encoded as follows:
  *
  *     {
  *         cid = ...,
  *         itable = ...,
  *         a = ... ,
  *         b = ... ,
  *     }
  *
  * A lambda closure is encoded as follows:
  *
  *     {
  *         cid = ...,
  *         apply = ...,
  *         underlying = ...
  *     }
  *
  * The class id of a closure is that of `ClosureTag` in the runtime.
  *
  * The encoding is implementation details and is subject to change.
  */
object Layout:
  val Underlying = "underlying"
  val ClassID = "cid"
  val ITable = "itable"
  val Apply = "apply"

  /** The size of a slot in bytes */
  val SlotSize = 8

  def encodeClassType(classInfo: ClassInfo)(using defn: Definitions): RecordType =
    val memberTypes = new mutable.ArrayBuffer[NamedInfo[Type]]
    memberTypes += NamedInfo(ClassID, defn.IntType)
    memberTypes += NamedInfo(ITable, AnyType)

    for field <- classInfo.fields do
      memberTypes += NamedInfo(field.name, field.tpe)

    RecordType(memberTypes.toList)

  def encodeLambdaType(lambdaType: LambdaType)(using defn: Definitions): RecordType =
    val classId = NamedInfo(ClassID, defn.IntType)
    val apply = NamedInfo(Apply, lambdaType.toProcType)
    val underlying = NamedInfo(Underlying, AnyType)
    RecordType(classId :: apply :: underlying :: Nil)

  /** Offset of a field relative to the start of the record in bytes */
  def fieldOffset(recordType: RecordType, field: String): Int =
    val index = recordType.fields.indexWhere(_.name == field)
    assert(index >= 0, field + " not found in " + recordType)
    index * SlotSize

  /** Type of a field */
  def fieldType(recordType: RecordType, field: String): Type =
    val index = recordType.fields.indexWhere(_.name == field)
    assert(index >= 0, field + " not found in " + recordType)
    recordType.fields(index).info

  /** Size of the record in bytes */
  def size(recordType: RecordType): Int =
    recordType.fields.size * SlotSize

  def classInstanceSize(cls: Symbols.Symbol)(using Definitions): Int =
    size(encodeClassType(cls.classInfo))

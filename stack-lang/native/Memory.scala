package native

import sast.Types.*

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

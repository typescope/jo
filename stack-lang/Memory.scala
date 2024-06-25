import Types.*

/** Code related to runtime memory
  */
object Memory:
  /** Size of the recrod in bytes */
  def size(recordType: Type.Record): Int =
    recordType.fields.size << 2

  /** Offset relative to the start of the record in bytes */
  def fieldOffset(recordType: Type.Record, field: String): Int =
    val index = recordType.fields.toList.indexWhere:
      case (k, v) => k == field
    assert(index >= 0, index)
    index << 2

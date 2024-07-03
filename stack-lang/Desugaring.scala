import Symbols.*
import Types.*
import Sast.*
import Reporter.Span

import scala.collection.mutable

/** Desugaring logic
  *
  * - Union types are desugared to record types
  */
object Desugaring:
  def encodeUnionType(tagTypes: List[Type]): RecordType =
    val fieldTypes = new mutable.ArrayBuffer[(String, Type)]
    fieldTypes += "tag" -> IntType
    for (tagType, i) <- tagTypes.zipWithIndex do
      fieldTypes += s"v$i" -> tagType

    RecordType(fieldTypes.toList)

  def encodeVariant(
      tagIndex: Int, values: List[Word], tagTypes: List[Type],
      tagPos: Span, variantPos: Span
    ): Word =

    val encodeType = encodeUnionType(tagTypes)
    val tagValue = IntLit(tagIndex)(tagPos)

    val fields = new mutable.ArrayBuffer[(String, Word)]
    fields += "tag" -> tagValue
    for (value, index) <- values.zipWithIndex do
      fields += s"v$index" -> value

    RecordLit(fields.toList)(encodeType, variantPos)

  def selectVariantArg(value: Word, argIndex: Int, pos: Span): Word =
    val fieldName = s"v$argIndex"
    val recordType = value.tpe.asRecordType
    val fieldType = recordType.fieldType(fieldName)
    Select(value, fieldName)(fieldType, pos)

  def testVariantTag(value: Word, tagIndex: Int, pos: Span): Word =
    val tagSelect = Select(value, "tag")(IntType, pos)
    val words =
      tagSelect
      :: IntLit(tagIndex)(pos)
      :: Ident(predef.eql)(pos)
      :: Nil
    Phrase(words)(BoolType, pos)

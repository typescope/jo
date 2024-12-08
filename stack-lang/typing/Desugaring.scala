package typing

import sast.Predef
import sast.Types.*
import sast.Sast.*
import pos.Positions.Span

import scala.collection.mutable

/** Desugaring logic
  *
  * - Union types are desugared to record types
  */
object Desugaring:
  def encodeUnionType(tagTypes: List[Type]): RecordType =
    val fieldTypes = new mutable.ArrayBuffer[NamedInfo[Type]]
    fieldTypes += NamedInfo("tag", IntType)
    for (tagType, i) <- tagTypes.zipWithIndex do
      fieldTypes += NamedInfo(s"v$i", tagType)

    RecordType(fieldTypes.toList)

  def encodeVariant(
      tagIndex: Int, values: List[Word], tagTypes: List[Type],
      tagSpan: Span, variantSpan: Span
    ): Word =

    val encodeType = encodeUnionType(tagTypes)
    val tagValue = IntLit(tagIndex)(tagSpan)

    val fields = new mutable.ArrayBuffer[(String, Word)]
    fields += "tag" -> tagValue
    for (value, index) <- values.zipWithIndex do
      fields += s"v$index" -> value

    RecordLit(fields.toList)(encodeType, variantSpan)

  def selectVariantArg(value: Word, argIndex: Int, span: Span): Word =
    val fieldName = s"v$argIndex"
    val recordType = value.tpe.asRecordType
    val fieldType = recordType.fieldType(fieldName)
    Select(value, fieldName)(fieldType, span)

  def testVariantTag(value: Word, tagIndex: Int, span: Span): Word =
    val tagSelect = Select(value, "tag")(IntType, span)
    val args =  tagSelect :: IntLit(tagIndex)(span) :: Nil
    val fun = Ident(Predef.eql)(span)
    Apply(fun, args)(BoolType, span)

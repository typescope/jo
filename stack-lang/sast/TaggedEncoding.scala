package sast

import sast.Types.*
import sast.Trees.*
import ast.Positions.{ Span, Source }

import scala.collection.mutable

/** Encoding of tagged values
  *
  * Tagged values are desugared to record types
  */
object TaggedEncoding:
  def getTagCode(tag: String): Int =
    // Take the first 2 char and the last 2 chars
    var code = 0
    val len = tag.size
    if len > 3 then
      code += tag(0).toByte << 21
      code += tag(1).toByte << 14
      code += tag(len - 2).toByte << 7
      code += tag(len - 1).toByte
    else
      for c <- tag do
        code = (code << 7) + c.toByte
    end if
    code

  def encodeTagType(tagType: TagType)(using defn: Definitions): RecordType =
    val fieldTypes = new mutable.ArrayBuffer[NamedInfo[Type]]
    fieldTypes += NamedInfo("tag", defn.IntType)
    for (tagType, i) <- tagType.paramTypes.zipWithIndex do
      fieldTypes += NamedInfo(s"v$i", tagType)
    RecordType(fieldTypes.toList)

  def encodeVariant(tagType: TagType, values: List[Word], tagSpan: Span, variantSpan: Span)(using defn: Definitions): Word =
    val tag = tagType.tag

    val tagCode = getTagCode(tag)
    val tagValue = Literal(Constant.Int(tagCode))(defn.IntType, tagSpan)

    val fields = new mutable.ArrayBuffer[(String, Word)]
    fields += "tag" -> tagValue
    for (value, i) <- values.zipWithIndex do
      fields += ("v" + i) -> value

    RecordLit(fields.toList)(variantSpan)

  def selectVariantField(value: Word, tagType: TagType, field: String, span: Span)(using defn: Definitions): Word =
    val fieldIndex = tagType.paramIndex(field)
    selectVariantField(value, tagType, fieldIndex, span)

  def selectVariantField(value: Word, tagType: TagType, argIndex: Int, span: Span)(using defn: Definitions): Word =
    val encodeType = TaggedEncoding.encodeTagType(tagType)
    val qualEncoded = Encoded(value)(encodeType)

    val fieldName = "v" + argIndex
    Select(qualEncoded, fieldName)(span)

  def testTagValue(tagValue: Word, tag: String, span: Span)(using defn: Definitions, source: Source): Word =
    val IntType = defn.IntType
    val tagCode = getTagCode(tag)
    val testTagValue = Literal(Constant.Int(tagCode))(IntType, span)
    val fun = Ident(defn.Int_eql)(span)
    fun.appliedTo(tagValue, testTagValue)

  def testVariantTag(ref: Word, tag: String, span: Span)(using defn: Definitions, source: Source): Word =
    val tagSelect = Select(ref, "tag")(span)
    testTagValue(tagSelect, tag, span)

  def testTagValues(tagValue: Ident, tags: List[String], span: Span)(using defn: Definitions, source: Source): Word =
    val tag :: rest = tags: @unchecked
    // ASTs are immutable thus can be shared
    //
    // Use non-short-cutting `either` for better CPU performance (no jumps)
    val fun = Ident(defn.Bool_either)(span)
    val cond = testTagValue(tagValue, tag, span)
    rest.foldLeft(cond): (acc, tag) =>
      val cond2 = testTagValue(tagValue, tag, span)
      fun.appliedTo(acc, cond2)

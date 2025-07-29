package sast

import sast.Types.*
import sast.Trees.*
import ast.Positions.{ Span, SourcePosition }

import reporting.Reporter

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

  def checkUnionType(unionType: UnionType, pos: SourcePosition)(using Reporter): Unit =
    val hashCodes = mutable.Map.empty[Int, String]
    for tag <- unionType.tags do
      val code = getTagCode(tag)
      if hashCodes.contains(code) then
        val tag2 = hashCodes(code)
        Reporter.error(s"Conflict between tag $tag and $tag2 in union type. The first 2 and last 2 chars should not be all the same.", pos)
      else
        hashCodes(code) = tag

  def encodeTagType(tagType: TagType)(using defn: Definitions): RecordType =
    val fieldTypes = new mutable.ArrayBuffer[NamedInfo[Type]]
    fieldTypes += NamedInfo("tag", defn.IntType)
    for (tagType, i) <- tagType.paramTypes.zipWithIndex do
      fieldTypes += NamedInfo(s"v$i", tagType)
    RecordType(fieldTypes.toList)

  def encodeVariant(tagType: TagType, values: List[Word], tagSpan: Span, variantSpan: Span)(using defn: Definitions): Word =
    val tag = tagType.tag

    val encodeType = encodeTagType(tagType)
    val tagCode = getTagCode(tag)
    val tagValue = Literal(Constant.Int(tagCode))(defn.IntType, tagSpan)

    val fields = new mutable.ArrayBuffer[(String, Word)]
    fields += "tag" -> tagValue
    for (value, i) <- values.zipWithIndex do
      fields += ("v" + i) -> value

    RecordLit(fields.toList)(encodeType, variantSpan)

  def selectVariantField(value: Word, tagType: TagType, field: String, span: Span)(using defn: Definitions): Word =
    val fieldIndex = tagType.paramIndex(field)
    selectVariantField(value, tagType, fieldIndex, span)

  def selectVariantField(value: Word, tagType: TagType, argIndex: Int, span: Span)(using defn: Definitions): Word =
    val encodeType = TaggedEncoding.encodeTagType(tagType)
    val qualEncoded = Encoded(value)(encodeType)

    val fieldName = "v" + argIndex
    val fieldType = encodeType.fieldType(fieldName)
    Select(qualEncoded, fieldName)(fieldType, span)

  def testTagValue(tagValue: Word, tag: String, span: Span)(using defn: Definitions): Word =
    val IntType = defn.IntType
    val tagCode = getTagCode(tag)
    val testTagValue = Literal(Constant.Int(tagCode))(IntType, span)
    val args =  tagValue :: testTagValue :: Nil
    val fun = Ident(defn.Int_eql)(span)
    Apply(fun, args, autos = Nil)(defn.BoolType)

  def testVariantTag(ref: Word, tag: String, span: Span)(using defn: Definitions): Word =
    val IntType = defn.IntType
    val tagSelect = Select(ref, "tag")(IntType, span)
    testTagValue(tagSelect, tag, span)

  def testTagValues(tagValue: Ident, tags: List[String], span: Span)(using defn: Definitions): Word =
    val tag :: rest = tags: @unchecked
    // ASTs are immutable thus can be shared
    //
    // Use non-short-cutting `either` for better CPU performance (no jumps)
    val fun = Ident(defn.Bool_either)(span)
    val tp = defn.BoolType
    val cond = testTagValue(tagValue, tag, span)
    rest.foldLeft(cond): (acc, tag) =>
      val cond2 = testTagValue(tagValue, tag, span)
      Apply(fun, acc :: cond2 :: Nil, autos = Nil)(tp)

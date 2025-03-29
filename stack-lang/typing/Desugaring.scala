package typing

import sast.Definitions
import sast.Constant
import sast.Types.*
import sast.Sast.*
import ast.Positions.{ Span, SourcePosition }

import reporting.Reporter

import scala.collection.mutable

/** Desugaring logic
  *
  * Tagged values are desugared to record types
  */
object Desugaring:
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

  def encodeTagType(tagType: TagType): RecordType =
    val IntType = Definitions.instance.IntType
    val fieldTypes = new mutable.ArrayBuffer[NamedInfo[Type]]
    fieldTypes += NamedInfo("tag", IntType)
    for (tagType, i) <- tagType.paramTypes.zipWithIndex do
      fieldTypes += NamedInfo(s"v$i", tagType)
    RecordType(fieldTypes.toList)

  def encodeVariant(tagType: TagType, values: List[Word], tagSpan: Span, variantSpan: Span): Word =
    val tag = tagType.tag
    val IntType = Definitions.instance.IntType

    val encodeType = encodeTagType(tagType)
    val tagCode = getTagCode(tag)
    val tagValue = Literal(Constant.Int(tagCode))(IntType, tagSpan)

    val fields = new mutable.ArrayBuffer[(String, Word)]
    fields += "tag" -> tagValue
    for (value, i) <- values.zipWithIndex do
      fields += ("v" + i) -> value

    RecordLit(fields.toList)(encodeType, variantSpan)

  def selectVariantField(value: Word, tagType: TagType, field: String, span: Span): Word =
    val fieldIndex = tagType.paramIndex(field)
    selectVariantField(value, tagType, fieldIndex, span)

  def selectVariantField(value: Word, tagType: TagType, argIndex: Int, span: Span): Word =
    val encodeType = Desugaring.encodeTagType(tagType)
    val qualEncoded = Encoded(value)(encodeType)

    val fieldName = "v" + argIndex
    val fieldType = encodeType.fieldType(fieldName)
    Select(qualEncoded, fieldName)(fieldType, span)

  def testVariantTag(ref: Word, tag: String, span: Span): Word =
    val tagCode = getTagCode(tag)
    val IntType = Definitions.instance.IntType
    val tagSelect = Select(ref, "tag")(IntType, span)
    val tagValue = Literal(Constant.Int(tagCode))(IntType, span)
    val args =  tagSelect :: tagValue :: Nil
    val fun = Ident(Definitions.instance.Predef_eql)(span)
    val tp = Definitions.instance.BoolType
    Apply(fun, args)(tp, span)

  def testVariantTags(ref: Word, tags: List[String], span: Span): Word =
    val tag :: rest = tags: @unchecked
    // ASTs are immutable thus can be shared
    val fun = Ident(Definitions.instance.Predef_or)(span)
    val tp = Definitions.instance.BoolType
    val cond = testVariantTag(ref, tag, span)
    rest.foldLeft(cond): (acc, tag) =>
      val cond2 = testVariantTag(ref, tag, span)
      Apply(fun, acc :: cond2 :: Nil)(tp, span)

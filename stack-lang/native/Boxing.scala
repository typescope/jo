package native

import phases.Phase

import sast.*
import sast.Trees.*
import sast.Symbols.*
import sast.Types.*

import native.runtime.NativeRuntime

import ast.Positions.Span

/**
  * Insert boxing/unboxing for numeric types in union types.
  *
  * This phase is only needed for native backends (and Java backend in future).
  * JavaScript backend doesn't need boxing since all values are tagged.
  *
  * Boxing is needed when:
  * - A numeric value (Byte/Char/Int/Float) is casted to a union type (by Typer)
  * - A union type value is casted to a numeric type (by PatternMatcher)
  *
  * This phase runs after pattern translation, so patterns should not exist.
  *
  * Thanks to Encoded nodes inserted during type checking and pattern translation,
  * we only need to override transformEncoded to inspect these markers.
  */
class Boxing(runtime: NativeRuntime)(using defn: Definitions) extends Phase[Symbol]:
  val contextObject = Phase.OwnerContext

  override def transformEncoded(encoded: Encoded)(using Context): Word =
    val Encoded(repr) = encoded
    val repr2 = transform(repr)

    val reprType = repr2.tpe
    val targetType = encoded.tpe

    if needsBoxing(reprType, targetType) then
      // Create boxed value
      boxValue(repr2, targetType, encoded.span)
    else if needsUnboxing(reprType, targetType) then
      // Extract numeric from union
      unboxValue(repr2, targetType, encoded.span)
    else
      // No boxing/unboxing needed, keep the encoding
      if repr2 eq repr then encoded
      else Encoded(repr2)(targetType)

  /** Check if boxing is needed: numeric -> union containing that numeric */
  private def needsBoxing(reprType: Type, targetType: Type): Boolean =
    if !targetType.isUnionType then
      false
    else if !defn.isNumericType(reprType) then
      false
    else
      // Check if the union contains this numeric type
      val unionType = targetType.asUnionType
      unionType.branches.exists(branch => Subtyping.conforms(reprType, branch))

  /** Check if unboxing is needed: union -> numeric */
  private def needsUnboxing(reprType: Type, targetType: Type): Boolean =
    if !reprType.isUnionType then
      false
    else if !defn.isNumericType(targetType) then
      false
    else
      // Check if the union contains this numeric type
      val unionType = reprType.asUnionType
      unionType.branches.exists(branch => Subtyping.conforms(targetType, branch))

  /** Box a numeric value into a union type */
  private def boxValue(word: Word, unionType: Type, span: Span): Word =
    // Determine which box constructor to use based on numeric type
    val boxConstructor = word.tpe match
      case tpe if tpe == defn.ByteType => runtime.Core_ByteBox_fun
      case tpe if tpe == defn.CharType => runtime.Core_CharBox_fun
      case tpe if tpe == defn.IntType => runtime.Core_IntBox_fun
      case tpe if tpe == defn.FloatType => runtime.Core_FloatBox_fun
      case _ => throw new Exception(s"Unexpected numeric type for boxing: ${word.tpe}")

    // Create: BoxClass(word)
    val constructorCall = Ident(boxConstructor)(span).appliedTo(word)
    Encoded(constructorCall)(unionType)

  /** Unbox a union value to extract numeric */
  private def unboxValue(word: Word, numericType: Type, span: Span): Word =
    // Determine which box class to extract from based on target numeric type
    val boxClass = numericType match
      case tpe if tpe == defn.ByteType => runtime.Core_ByteBox
      case tpe if tpe == defn.CharType => runtime.Core_CharBox
      case tpe if tpe == defn.IntType => runtime.Core_IntBox
      case tpe if tpe == defn.FloatType => runtime.Core_FloatBox
      case _ => throw new Exception(s"Unexpected numeric type for unboxing: ${numericType}")

    // Extract: Encoded(word)(BoxType).select("value")
    val boxClassType = StaticRef(boxClass)
    val encodedAsBox = Encoded(word)(boxClassType)
    Select(encodedAsBox, "value")(span)
end Boxing

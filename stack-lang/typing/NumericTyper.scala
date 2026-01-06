package typing

import ast.Positions.{Span, Source}
import ast.Trees as Ast
import reporting.Reporter
import sast.{Constant, Definitions}
import sast.Trees.Literal
import Inference.TargetType

object NumericTyper:
  /** Type an integer literal from AST to SAST Literal
    *
    * Creates a polymorphic numeric literal based on the expected type:
    * - Byte: if value in [-128, 127]
    * - Char: if value in [0, 65535]
    * - Float: always valid
    * - Int: default
    */
  def typeIntLit(lit: Ast.IntLit)(using tt: TargetType, defn: Definitions, rp: Reporter, src: Source): Literal =
    val intValue = parseIntLiteral(lit.value, lit.isHex, lit.span)

    // Determine the literal type based on expected type
    tt.knownType match
      case Some(expectedType) if expectedType.isSubtype(defn.ByteType) =>
        if intValue >= -128 && intValue <= 127 then
          Literal(Constant.Int(intValue))(defn.ByteType, lit.span)
        else
          rp.error(s"Integer literal $intValue out of range for Byte [-128, 127]", lit.span.toPos)
          Literal(Constant.Int(intValue))(defn.ByteType, lit.span)

      case Some(expectedType) if expectedType.isSubtype(defn.CharType) =>
        if intValue >= 0 && intValue <= 65535 then
          Literal(Constant.Int(intValue))(defn.CharType, lit.span)
        else
          rp.error(s"Integer literal $intValue out of range for Char [0, 65535]", lit.span.toPos)
          Literal(Constant.Int(intValue))(defn.CharType, lit.span)

      case Some(expectedType) if expectedType.isSubtype(defn.FloatType) =>
        Literal(Constant.Float(intValue.toDouble))(defn.FloatType, lit.span)

      case _ =>
        // Default to Int
        Literal(Constant.Int(intValue))(defn.IntType, lit.span)

  /** Type a character literal from AST to SAST Literal
    *
    * Creates a polymorphic character literal based on the expected type:
    * - Byte: if character code in [-128, 127] (for signed byte compatibility)
    * - Int: widening conversion
    * - Float: widening conversion
    * - Char: default
    */
  def typeCharLit(lit: Ast.CharLit)(using tt: TargetType, defn: Definitions, rp: Reporter, src: Source): Literal =
    val charValue = lit.value.toInt

    // Determine the literal type based on expected type
    tt.knownType match
      case Some(expectedType) if expectedType.isSubtype(defn.ByteType) =>
        // Char to Byte: only if character code fits in signed byte range
        if charValue >= -128 && charValue <= 127 then
          Literal(Constant.Int(charValue))(defn.ByteType, lit.span)
        else
          rp.error(s"Character literal '${Character.toString(lit.value)}' (code $charValue) out of range for Byte [-128, 127]", lit.span.toPos)
          Literal(Constant.Int(charValue))(defn.ByteType, lit.span)

      case Some(expectedType) if expectedType.isSubtype(defn.IntType) =>
        // Widening: Char to Int
        Literal(Constant.Int(charValue))(defn.IntType, lit.span)

      case Some(expectedType) if expectedType.isSubtype(defn.FloatType) =>
        // Widening: Char to Float
        Literal(Constant.Float(charValue.toDouble))(defn.FloatType, lit.span)

      case _ =>
        // Default to Char
        Literal(Constant.Int(charValue))(defn.CharType, lit.span)

  /** Type a float literal from AST to SAST Literal */
  def typeFloatLit(lit: Ast.FloatLit)(using defn: Definitions, rp: Reporter, src: Source): Literal =
    val floatValue = parseFloatLiteral(lit.value, lit.span)
    Literal(Constant.Float(floatValue))(defn.FloatType, lit.span)

  /** Parse integer literal string to Int value
    *
    * Handles both decimal (e.g., "42", "-123") and hexadecimal (e.g., "0xFF", "-0x10")
    * with proper overflow detection.
    */
  private def parseIntLiteral(str: String, isHex: Boolean, span: Span)(using rp: Reporter, src: Source): Int =
    if isHex then
      hexStr2Int(str, span)
    else
      str2Int(str, span)

  /** Parse hexadecimal integer literal with overflow detection
    *
    * str is like "0x1F" or "-0xFF" or "0xF_F_F"
    */
  private def hexStr2Int(str: String, span: Span)(using rp: Reporter, src: Source): Int =
    // Strip underscores first
    val cleaned = str.replace("_", "")

    val isNegative = cleaned(0) == '-'
    val prefixLen = if isNegative then 3 else 2 // Skip "-0x" or "0x"
    val hexDigits = cleaned.substring(prefixLen)
    val length = hexDigits.size

    if length > 8 then
      rp.error(s"Hexadecimal literal too long (max 8 hex digits): $hexDigits", span.toPos)
      return 0

    var sum: Int = 0
    var i = 0
    while i < length do
      val c = hexDigits(i)
      val v = if c >= '0' && c <= '9' then c - '0'
              else if c >= 'a' && c <= 'f' then c - 'a' + 10
              else if c >= 'A' && c <= 'F' then c - 'A' + 10
              else 0
      sum = (sum << 4) | v
      i += 1

    if isNegative then -sum else sum
  end hexStr2Int

  /** Parse decimal integer literal with overflow detection */
  private def str2Int(str: String, span: Span)(using rp: Reporter, src: Source): Int =
    // Strip underscores first
    val cleaned = str.replace("_", "")

    val first = cleaned(0)
    val length = cleaned.size
    val isNegative = first == '-'

    var sum: Int = 0
    if !isNegative then sum = first - '0'
    var overflow = false

    var i = 1
    while i < length do
      val c = cleaned(i)
      val v = c - '0'
      sum = sum * 10 + (if isNegative then -v else v)

      if !isNegative && sum < 0 then overflow = true
      if isNegative && sum > 0 then overflow = true

      i += 1

    if overflow then
      rp.error(s"Integer literal overflow: $str", span.toPos)

    sum
  end str2Int

  /** Parse float literal string to Double value */
  private def parseFloatLiteral(str: String, span: Span)(using rp: Reporter, src: Source): Double =
    // Strip underscores first
    val cleaned = str.replace("_", "")

    try
      val value = java.lang.Double.parseDouble(cleaned)

      // Check for overflow (infinity)
      if value.isInfinite then
        rp.error(s"Float literal out of range: $str", span.toPos)
        0.0
      else
        value
    catch
      case _: NumberFormatException =>
        rp.error(s"Invalid float literal: $str", span.toPos)
        0.0

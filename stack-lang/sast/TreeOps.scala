package sast

import Trees.*
import Symbols.Symbol
import Types.*


import scala.collection.mutable

object TreeOps:
  class AdaptionFailure(word: Word, targetType: Type) extends Exception:
    override def toString(): String =
      "Unable to adapt " + word + " of type " + word.tpe + " to " + targetType

  /** Adapt the word to the target type.
    *
    * It makes drop of values in if/match expressions explicit.
    */
  def adapt(word: Word, targetType: Type)
    (using defn: Definitions)
  : Word =

    val unitType = defn.UnitType

    val curType = word.tpe
    if Subtyping.conforms(curType, targetType) then
      word

    else if targetType.isVoidType && curType.isValueType then
      word.dropValue

    else

      val isNumeric = defn.isNumericType(word.tpe) && defn.isNumericType(targetType)

      if isNumeric && !Subtyping.conforms(word.tpe, targetType) then
        // Numeric coercion
        word match
          case Literal(Constant.Int(n)) =>
            val tp2 = coerceIntLiteral(n, word.tpe, targetType)
            val word2 = Literal(Constant.Int(n))(tp2, word.span)
            word2

          case _ =>
            // Only widening coercion is allowed for non-literals
            coerceNumeric(word, targetType)

      else if Subtyping.conforms(unitType, targetType) then
        val unit = unitValue(word.span.endPoint)
        Block(word.ensureDropValue :: unit :: Nil)(unitType, word.span)

      else
        throw new AdaptionFailure(word, targetType)

  private def coerceIntLiteral(n: Int, origType: Type, targetType: Type)
    (using defn: Definitions)
  : Type =

    if
      targetType.refers(defn.Predef_Byte) && n < 128 && n >= -128
      || targetType.refers(defn.Predef_Char) && n < 65536 && n >= 0
      || targetType.refers(defn.Int_Int)
    then
      targetType

    else
      origType

  /** Adapt the word to the target type
    *
    *     Byte ==> Int
    *     Char ==> Int
    *
    * Assumption: The tye of the word does not conform to the target type.
    */
  private def coerceNumeric(word: Word, targetType: Type)(using defn: Definitions): Word =
    def fail() = throw new AdaptionFailure(word, targetType)

    val origType = word.tpe
    if origType.refers(defn.Predef_Byte) then
      if targetType.refers(defn.Int_Int) then
        val byteToInt = Ident(defn.Predef_byteToInt)(word.span)
        Apply(byteToInt, word :: Nil, autos = Nil)(targetType)

      else
        fail()

    else if origType.refers(defn.Predef_Char) then
      if targetType.refers(defn.Int_Int) then
        val charToInt = Ident(defn.Predef_charToInt)(word.span)
        Apply(charToInt, word :: Nil, autos = Nil)(targetType)

      else
        fail()

    else
      fail()

  /** Returns (locals, free) */
  def variableCensus(fdef: FunDef)(using Definitions): (List[Symbol], List[Symbol]) =
    val census = new VariableCensus
    census(fdef.body)(using ())
    val locals = census.locals.distinct.toList
    val masked = fdef.allParams ++ locals
    val free = census.free.filter(sym => !masked.contains(sym)).distinct.toList
    (locals.filter(_.info.isValueType), free)

  class VariableCensus(using Definitions) extends TreeTraverser:
    val locals = new mutable.ArrayBuffer[Symbol]
    val free = new mutable.ArrayBuffer[Symbol]

    type Context = Unit

    override def apply(pat: Pattern)(using Context): Unit =
      pat match
        case AliasPattern(id, nested) =>
          locals += id.symbol
          this(nested)

        case SeqPattern(pats) =>
          pats.foreach:
            case AtomPattern(pattern) => this(pattern)

            case SkipToPattern(pattern) => this(pattern)

            case RestPattern(pattern) => this(pattern)

            case star @ StarPattern(pattern) =>
              locals ++= star.bindings.map(_._1)
              this(pattern)

        case _ =>
          recur(pat)

    def apply(word: Word)(using Context): Unit =
      word match
        case Ident(sym) =>
          // can be a global name
          free += sym

        case ValDef(sym, rhs) =>
          if !sym.isField then locals += sym
          this(rhs)

        case Assign(Ident(sym), rhs) =>
          locals += sym
          this(rhs)

        case obj: Object =>
          locals += obj.self
          recur(obj)

        case fdef: FunDef =>
          locals += fdef.symbol
          free ++= fdef.freeVariables

        case _ => recur(word)

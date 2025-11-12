package sast

import sast.Trees.*
import sast.Types.*
import sast.Symbols.*

import ast.Positions.Source
import common.Debug

object Adaptation:
  type Adapter = (Word, Type) => Option[Word]

  /** Use exception because we do not want to refer Reporter in sast package */
  class AdaptionFailure(word: Word, targetType: Type) extends Exception:
    override def toString(): String =
      "Unable to adapt " + word + " of type " + word.tpe + " to " + targetType

  /** Adapt the word to the target type.
    *
    * It makes drop of values in if/match expressions explicit.
    * It also tries to apply adapters if direct conformance fails.
    */
  def adapt(word: Word, targetType: Type, adapter: Adapter)(using defn: Definitions): Word
  = Debug.trace(s"adapting ${word.show} to ${targetType.show}", enable = false):

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
        Block(word.ensureDropValue :: unit :: Nil)(word.span)

      else
        // Try to apply adapters before failing
        adapter(word, targetType) match
          case Some(adapted) => adapted
          case None => throw new AdaptionFailure(word, targetType)

  private def coerceIntLiteral(n: Int, origType: Type, targetType: Type)(using defn: Definitions): Type =
    if
      targetType.isSubtype(defn.ByteType) && n < 128 && n >= -128
      || targetType.isSubtype(defn.CharType) && n < 65536 && n >= 0
      || targetType.isSubtype(defn.IntType)
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
    if origType.isSubtype(defn.ByteType) then
      if targetType.isSubtype(defn.IntType) then
        val byteToInt = Ident(defn.Predef_byteToInt)(word.span)
        byteToInt.appliedTo(word)

      else
        fail()

    else if origType.isSubtype(defn.CharType) then
      if targetType.isSubtype(defn.IntType) then
        val charToInt = Ident(defn.Predef_charToInt)(word.span)
        charToInt.appliedTo(word)

      else
        fail()

    else
      fail()

  def createSimpleAdapter(adapters: List[Symbol])(using Definitions): Adapter =
    (word, targetType) => adaptSimple(word, targetType, adapters)

  def createVarargSpliceAdapter(adapters: List[Symbol], owner: Symbol)(using defn: Definitions, source: Source): Adapter =
    (word, targetType) =>
      word.tpe.widen.dealias match
        case AppliedType(StaticRef(sym), elemType :: Nil) if sym == defn.List_type =>
          // Only try adapt if the type is List[X]
          val AppliedType(_, targetElemType :: Nil) = targetType: @unchecked
          adaptVarargSplice(word, targetElemType, elemType, adapters, owner)

        case tp =>
          None

  def adaptSimple
      (word: Word, targetType: Type, adapters: List[Symbol])
      (using defn: Definitions)
  : Option[Word] = Debug.trace(s"adapt ${word.show} to ${targetType.show} with ${adapters}", enable = false):

    adapters match
      case Nil => None

      case adapterSym :: rest =>
        val procType = adapterSym.info.asProcType
        val adapterParamType = procType.params.head.info

        // Check if the word's type conforms to the adapter's parameter type
        if Subtyping.conforms(word.tpe, adapterParamType) then
          val adapterIdent = Ident(adapterSym)(word.span)
          val adapted = adapterIdent.appliedTo(word)

          Some(adapted)

        else
          adaptSimple(word, targetType, rest)

  def adaptVarargSplice
      (word: Word, targetElemType: Type, elemType: Type, adapters: List[Symbol], owner: Symbol)
      (using Definitions, Source)
  : Option[Word] = Debug.trace(s"adapt splice ${word.show} from ${elemType.show} to ${targetElemType.show} with ${adapters}", enable = false):

    adapters match
      case Nil => None

      case adapterSym :: rest =>
        val procType = adapterSym.info.asProcType
        val adapterParamType = procType.params.head.info

        // Check if the word's type conforms to the adapter's parameter type
        if Subtyping.conforms(elemType, adapterParamType) then
          val adapterFun = TreeOps.etaExpand(adapterSym, owner, Effects.Policy.Infer, word.span)
          val adapted = word.select("map").appliedToTypes(targetElemType).appliedTo(adapterFun)

          Some(adapted)

        else
          adaptVarargSplice(word, targetElemType, elemType, rest, owner)

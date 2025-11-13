package sast

import sast.Trees.*
import sast.Types.*
import sast.Symbols.*

import ast.Positions.{Span, Source}
import common.Debug

object Adaptation:
  type Adapter = (Word, Type) => Option[Word]

  val NoAdapter: Adapter = (_, _) => None

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
    * Assumption: The type of the word does not conform to the target type.
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

  def createSimpleAdapter(adapters: List[Symbol | String])(using Definitions): Adapter =
    if adapters.isEmpty then NoAdapter
    else (word, targetType) => adaptSimple(word, targetType, adapters)

  def createVarargSpliceAdapter(adapters: List[Symbol | String], owner: Symbol)(using defn: Definitions, source: Source): Adapter =
    if adapters.isEmpty then return NoAdapter

    (word, targetType) =>
      word.tpe.widen.dealias match
        case AppliedType(StaticRef(sym), elemType :: Nil) if sym == defn.List_type =>
          // Only try adapt if the type is List[X]
          val AppliedType(_, targetElemType :: Nil) = targetType: @unchecked
          adaptVarargSplice(word, targetElemType, elemType, adapters, owner)

        case tp =>
          None

  def adaptSimple
      (word: Word, targetType: Type, adapters: List[Symbol | String])
      (using defn: Definitions)
  : Option[Word] = Debug.trace(s"adapt ${word.show} to ${targetType.show} with ${adapters}", enable = false):

    adapters match
      case Nil => None

      case (adapterSym: Symbol) :: rest =>
        val procType = adapterSym.info.asProcType
        val adapterParamType = procType.params.head.info

        // Check if the word's type conforms to the adapter's parameter type
        if Subtyping.conforms(word.tpe, adapterParamType) then
          val adapterIdent = Ident(adapterSym)(word.span)
          val adapted = adapterIdent.appliedTo(word)

          Some(adapted)

        else
          adaptSimple(word, targetType, rest)

      case (memberName: String) :: rest =>
        // Member adapter: apply if the word's type has the member
        word.tpe.getTermMember(memberName) match
          case Some(memberType) =>
            // Check if member is a parameterless method (ProcType with no params)
            // If so, extract its result type
            val effectiveType = memberType match
              case procType: ProcType if procType.params.isEmpty && procType.autos.isEmpty =>
                procType.resultType
              case tp => tp

            // Check if the effective member type conforms to the target type
            if Subtyping.conforms(effectiveType, targetType) then
              // Select the member
              val selected = word.select(memberName)

              // For parameterless methods, we need to apply them (call with no args)
              val adapted = memberType match
                case procType: ProcType if procType.params.isEmpty && procType.autos.isEmpty =>
                  // Apply parameterless method
                  selected.appliedTo()
                case _ =>
                  // For val members, just return the selection
                  selected

              Some(adapted)
            else
              // Member exists but type doesn't match, try next adapter
              adaptSimple(word, targetType, rest)

          case None =>
            // Member doesn't exist, try next adapter
            adaptSimple(word, targetType, rest)

  def adaptVarargSplice
      (word: Word, targetElemType: Type, elemType: Type, adapters: List[Symbol | String], owner: Symbol)
      (using Definitions, Source)
  : Option[Word] = Debug.trace(s"adapt splice ${word.show} from ${elemType.show} to ${targetElemType.show} with ${adapters}", enable = false):

    adapters match
      case Nil => None

      case (adapterSym: Symbol) :: rest =>
        val procType = adapterSym.info.asProcType
        val adapterParamType = procType.params.head.info

        // Check if the word's type conforms to the adapter's parameter type
        if Subtyping.conforms(elemType, adapterParamType) then
          val adapterFun = TreeOps.etaExpand(adapterSym, owner, Effects.Policy.Infer, word.span)
          val adapted = word.select("map").appliedToTypes(targetElemType).appliedTo(adapterFun)

          Some(adapted)

        else
          adaptVarargSplice(word, targetElemType, elemType, rest, owner)

      case (memberName: String) :: rest =>
        // Member adapter for vararg splice: apply .map(_.memberName) or .map(_.memberName())
        elemType.getTermMember(memberName) match
          case Some(memberType) =>
            // Check if member is a parameterless method (ProcType with no params)
            // If so, extract its result type
            val effectiveType = memberType match
              case procType: ProcType if procType.params.isEmpty && procType.autos.isEmpty =>
                procType.resultType
              case tp => tp

            // Check if the effective member type conforms to the target element type
            if Subtyping.conforms(effectiveType, targetElemType) then
              // Create a lambda function object similar to etaExpand
              val memberAccessorFun = createMemberAccessor(memberName, elemType, memberType, targetElemType, owner, word.span)

              // Apply map with the lambda
              val adapted = word.select("map").appliedToTypes(targetElemType).appliedTo(memberAccessorFun)

              Some(adapted)
            else
              // Member exists but type doesn't match, try next adapter
              adaptVarargSplice(word, targetElemType, elemType, rest, owner)

          case None =>
            // Member doesn't exist, try next adapter
            adaptVarargSplice(word, targetElemType, elemType, rest, owner)

  /** Create a lambda function object that accesses a member: x => x.memberName or x => x.memberName() */
  private def createMemberAccessor
      (memberName: String, paramType: Type, memberType: Type, resultType: Type, owner: Symbol, span: Span)
      (using defn: Definitions, source: Source)
  : Word =
    // Build the procedure type for the lambda
    val procType = ProcType(
      tparams = Nil,
      params = NamedInfo("x", paramType) :: Nil,
      adapters = Nil,
      autos = Nil,
      resultType = resultType,
      receivesInfo = () => Nil,  // Pure function
      preParamCount = 0
    )

    // Use createLambda helper to generate the lambda object
    TreeOps.createLambda(procType, owner, Effects.Policy.Infer, span) { (paramIdents, autoIdents) =>
      // Body: x.memberName or x.memberName()
      val paramIdent = paramIdents.head
      val selected = paramIdent.select(memberName)

      // For parameterless methods, apply them
      memberType match
        case procType: ProcType if procType.params.isEmpty && procType.autos.isEmpty =>
          selected.appliedTo()
        case _ =>
          selected
    }

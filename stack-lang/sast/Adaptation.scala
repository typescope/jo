package sast

import sast.Trees.*
import sast.Types.*
import sast.Symbols.*

import ast.Positions.{Span, Source}
import common.Debug

object Adaptation:
  type Adapter = (Word, Type) => Result

  enum Trial:
    case Member(tp: Type, member: String, error: Error)
    case Function(sym: Symbol)
    case View(tp: Type)

  enum Error:
    case MissingMember
    case TypeMismatch(found: Type)
    case AutoNotFound(search: AutoResolution.SearchNode.All)

  enum Result:
    case Success(word: Word)
    case Failure(trials: Seq[Trial])

  val NoAdapter: Adapter = (_, _) => Result.Failure(trials = Nil)

  /** Format trial information into a human-readable error message */
  def formatTrials(trials: Seq[Trial])(using Definitions): String =
    if trials.isEmpty then
      ""
    else
      val sb = new StringBuilder
      sb.append("\n\nTried the following adapters:")

      for trial <- trials do
        trial match
          case Trial.Function(sym) =>
            sb.append(s"\n  - fun ${sym.name}: type mismatch  ✗")

          case Trial.View(tp) =>
            sb.append(s"\n  - view ${tp.show}: type mismatch  ✗")

          case Trial.Member(tp, member, error) =>
            error match
              case Error.MissingMember =>
                sb.append(s"\n  - .$member: missing ✗")

              case Error.TypeMismatch(found) =>
                sb.append(s"\n  - .$member: type mismatch ✗")
                sb.append(s"\n    Expected: ${tp.show}")
                sb.append(s"\n    Found:    ${found.show}")

              case Error.AutoNotFound(search) =>
                sb.append(s"\n  - .$member: auto resolution failed ✗\n")
                sb.append(AutoResolution.formatSearchTree(search, baseIndent = "    "))

      sb.toString

  /** Use exception because we do not want to refer Reporter in sast package */
  class AdaptionFailure(word: Word, targetType: Type, val trials: Seq[Trial])(using defn: Definitions) extends Exception:
    override def toString(): String =
      "Unable to adapt " + word.show + " of type " + word.tpe.show + " to " + targetType.show +
        formatTrials(trials)

  /** Adapt the word to the target type.
    *
    * It makes drop of values in if/match expressions explicit.
    * It also tries to apply adapters if direct conformance fails.
    *
    */
  def adapt(word: Word, targetType: Type, adapter: Adapter)
      (using defn: Definitions)
  : Word = Debug.trace(s"adapting ${word.show} to ${targetType.show}", enable = false):

    assert(targetType.isFullyInstantiated, "not fully instantiated: " + targetType.show)
    assert(word.tpe.isFullyInstantiated, "not fully instantiated: " + word.tpe.show)

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
        val trials = new scala.collection.mutable.ArrayBuffer[Trial]()
        // First try views
        var viewTypes = word.tpe.viewTypes

        while viewTypes.nonEmpty do
          val viewType: MemberRef = viewTypes.head
          viewTypes = viewTypes.tail

          if Subtyping.conforms(viewType, targetType) then
            val name = viewType.symbol.name
            return word.select(name)
          else
            trials += Trial.View(viewType.widen)

        // Try to apply adapters before failing
        adapter(word, targetType) match
          case Result.Success(adapted) => adapted
          case Result.Failure(trials2) => throw new AdaptionFailure(word, targetType, trials.toSeq ++ trials2)

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
    def fail() = throw new AdaptionFailure(word, targetType, Nil)

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

  def createSimpleAdapter(adapters: List[Symbol | String], owner: Symbol)(using Definitions, Source): Adapter =
    if adapters.isEmpty then NoAdapter
    else (word, targetType) => adaptSimple(word, targetType, adapters, owner)

  def createVarargSpliceAdapter(adapters: List[Symbol | String], owner: Symbol)
      (using defn: Definitions, source: Source): Adapter =

    if adapters.isEmpty then return NoAdapter

    (word, targetType) =>
      word.tpe.widen.dealias match
        case AppliedType(StaticRef(sym), elemType :: Nil) if sym == defn.List_type =>
          // Only try adapt if the type is List[X]
          val AppliedType(_, targetElemType :: Nil) = targetType: @unchecked
          adaptVarargSplice(word, targetElemType, elemType, adapters, owner)

        case tp =>
          Result.Failure(Nil)

  def adaptSimple
      (word: Word, targetType: Type, adapters: List[Symbol | String], owner: Symbol)
      (using defn: Definitions, so: Source)
  : Result = Debug.trace(s"adapt ${word.show} to ${targetType.show} with ${adapters}", enable = false):
    val trials = new scala.collection.mutable.ArrayBuffer[Trial]()
    var remaining = adapters

    while remaining.nonEmpty do
      val adapter = remaining.head
      remaining = remaining.tail

      adapter match
        case adapterSym: Symbol =>
          // The validation currently is performed after checking thus invalid adapters may appear here
          if adapterSym.isFunction then
            val procType = adapterSym.info.asProcType
            val adapterParamType = procType.params.head.info

            val isValid =
              !procType.isPolyType
              && procType.paramCount == 1
              && procType.autos.isEmpty
              && Subtyping.conforms(procType.resultType, targetType)

            // Check if the word's type conforms to the adapter's parameter type
            if isValid && Subtyping.conforms(word.tpe, adapterParamType) then
              val adapterIdent = Ident(adapterSym)(word.span)
              val adapted = adapterIdent.appliedTo(word)
              return Result.Success(adapted)
            else
              trials += Trial.Function(adapterSym)

        case memberName: String =>
          // Member adapter: apply if the word's type has the member
          word.tpe.getTermMember(memberName) match
            case Some(memberType) =>
              // Widen to get underlying type (MemberRef -> ProcType)
              val widenedType = memberType.widen
              // Get effective result type (for parameterless methods, returns result type; otherwise returns type as-is)
              val effectiveType = widenedType.effectiveResultType

              // Check if the effective member type conforms to the target type
              if Subtyping.conforms(effectiveType, targetType) then
                // Select the member
                val selected = word.select(memberName)

                // For parameterless methods (may have auto parameters), apply them
                widenedType match
                  case procType: ProcType if procType.params.isEmpty =>
                    // Resolve auto parameters if present
                    if procType.autos.nonEmpty then
                      // Create SearchNode for tracking resolution
                      val all: AutoResolution.SearchNode.All = AutoResolution.SearchNode.All(scala.collection.mutable.ArrayBuffer())

                      // Resolve auto parameters, using empty having list since this is adapter context
                      AutoResolution.resolve(procType, havings = Nil, trace = Vector.empty, all, owner, word.span) match
                        case Some(resolvedAutos) =>
                          // Apply with resolved auto arguments
                          val adapted = Apply(selected, args = Nil, autos = resolvedAutos)(word.span)
                          return Result.Success(adapted)
                        case None =>
                          // Auto resolution failed - record trial and try next adapter
                          trials += Trial.Member(word.tpe, memberName, Error.AutoNotFound(all))
                    else
                      // No auto parameters, simple application
                      val adapted = selected.appliedTo()
                      return Result.Success(adapted)

                  case _ =>
                    // For val members, just return the selection
                    return Result.Success(selected)
              else
                // Member exists but type doesn't match, try next adapter
                trials += Trial.Member(word.tpe, memberName, Error.TypeMismatch(effectiveType))

            case None =>
              trials += Trial.Member(word.tpe, memberName, Error.MissingMember)
              // Member doesn't exist, try next adapter

      end match
    end while

    Result.Failure(trials.toList)

  def adaptVarargSplice
      (word: Word, targetElemType: Type, elemType: Type, adapters: List[Symbol | String], owner: Symbol)
      (using defn: Definitions, so: Source)
  : Result = Debug.trace(s"adapt splice ${word.show} from ${elemType.show} to ${targetElemType.show} with ${adapters}", enable = false):
    val trials = new scala.collection.mutable.ArrayBuffer[Trial]()
    var remaining = adapters

    while remaining.nonEmpty do
      val adapter = remaining.head
      remaining = remaining.tail

      adapter match
        case adapterSym: Symbol =>
          if adapterSym.isFunction then
            val procType = adapterSym.info.asProcType
            val adapterParamType = procType.params.head.info

            val isValid =
              !procType.isPolyType
              && procType.paramCount == 1
              && procType.autos.isEmpty
              && Subtyping.conforms(procType.resultType, targetElemType)

            // Check if the word's type conforms to the adapter's parameter type
            if isValid && Subtyping.conforms(elemType, adapterParamType) then
              val adapterFun = TreeOps.etaExpand(adapterSym, owner, Effects.Policy.Infer, word.span)
              val adapted = word.select("map").appliedToTypes(targetElemType).appliedTo(adapterFun)
              return Result.Success(adapted)
            else
              trials += Trial.Function(adapterSym)

        case memberName: String =>
          // Member adapter for vararg splice: apply .map(_.memberName) or .map(_.memberName())
          elemType.getTermMember(memberName) match
            case Some(memberType) =>
              // Widen to get underlying type (MemberRef -> ProcType)
              val widenedType = memberType.widen
              // Get effective result type (for parameterless methods, returns result type; otherwise returns type as-is)
              val effectiveType = widenedType.effectiveResultType

              // Check if the effective member type conforms to the target element type
              if Subtyping.conforms(effectiveType, targetElemType) then
                // Create member accessor - it will handle auto resolution internally
                createMemberAccessor(memberName, elemType, widenedType, targetElemType, owner, word.span) match
                  case Right(memberAccessorFun) =>
                    // Success - create the map call
                    val adapted = word.select("map").appliedToTypes(targetElemType).appliedTo(memberAccessorFun)
                    return Result.Success(adapted)
                  case Left(all) =>
                    // Auto resolution failed
                    trials += Trial.Member(elemType, memberName, Error.AutoNotFound(all))
              else
                // Member exists but type doesn't match, try next adapter
                trials += Trial.Member(elemType, memberName, Error.TypeMismatch(effectiveType))

            case None =>
              trials += Trial.Member(elemType, memberName, Error.MissingMember)
              // Member doesn't exist, try next adapter

      end match
    end while

    Result.Failure(trials.toList)

  /** Create a lambda function object that accesses a member: x => x.memberName or x => x.memberName()
    *
    * Returns Left with search node if auto resolution fails, Right with lambda if successful.
    */
  private def createMemberAccessor
      (memberName: String, paramType: Type, memberType: Type, resultType: Type, owner: Symbol, span: Span)
      (using defn: Definitions, source: Source)
  : Either[AutoResolution.SearchNode.All, Word] =
    // Build the procedure type for the lambda
    val procType = ProcType(
      tparams = Nil,
      params = NamedInfo("x", paramType) :: Nil,
      adapters = Nil,
      autos = Nil,
      candidates = Nil,
      resultType = resultType,
      receivesInfo = () => Nil,  // Pure function
      preParamCount = 0
    )

    // Check if member has auto parameters that need resolution
    memberType match
      case memberProcType: ProcType if memberProcType.autos.nonEmpty =>
        // Try to resolve auto parameters before creating lambda
        val all: AutoResolution.SearchNode.All = AutoResolution.SearchNode.All(scala.collection.mutable.ArrayBuffer())
        AutoResolution.resolve(memberProcType, havings = Nil, trace = Vector.empty, all, owner, span) match
          case Some(resolvedAutos) =>
            // Auto resolution succeeded - create lambda that applies with resolved autos
            val lambda = TreeOps.createLambda(procType, owner, Effects.Policy.Infer, span): (paramIdents, autoIdents) =>
              val paramIdent = paramIdents.head
              val selected = paramIdent.select(memberName)
              Apply(selected, args = Nil, autos = resolvedAutos)(span)

            Right(lambda)

          case None =>
            // Auto resolution failed
            Left(all)

      case _ =>
        // No auto parameters or not a parameterless method - create simple lambda
        val lambda = TreeOps.createLambda(procType, owner, Effects.Policy.Infer, span): (paramIdents, autoIdents) =>
          val paramIdent = paramIdents.head
          val selected = paramIdent.select(memberName)

          memberType match
            case memberProcType: ProcType if memberProcType.params.isEmpty =>
              // Parameterless method without autos
              selected.appliedTo()
            case _ =>
              // Field access
              selected

        Right(lambda)

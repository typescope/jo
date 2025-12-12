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
    case AmbiguousMember(candidates: List[(MemberRef, Type)])
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

              case Error.AmbiguousMember(candidates) =>
                sb.append(s"\n  - .$member: ambiguous ✗")
                sb.append(s"\n    Multiple views have this member:")
                for (viewRef, _) <- candidates do
                  sb.append(s"\n      - ${viewRef.widen.show}")

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

    word match
      case Literal(Constant.Int(n)) =>
        if
          targetType.isSubtype(defn.ByteType) && n < 128 && n >= -128
          || targetType.isSubtype(defn.CharType) && n < 65536 && n >= 0
          || targetType.isSubtype(defn.IntType)
        then
          Literal(Constant.Int(n))(targetType, word.span)

        else
          fail()

      case _ =>
        // Only widening coercion is allowed for non-literals
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

  /** Result of member adaptation through views.
    *
    * Captures all possible outcomes:
    * - Success: member found (possibly through a view), word adapted
    * - Ambiguous: multiple views have the member
    * - NotFound: member not found anywhere
    */
  enum MemberAdaptResult:
    /** Member found successfully.
      * @param word The adapted word (with view selection if needed).
      *             When selectMember=true, word has the member selected and word.tpe is the member's type.
      *             When selectMember=false, word is adapted through view if needed but member not selected.
      */
    case Success(word: Word)

    /** Multiple views have the member - ambiguous.
      * @param candidates The conflicting views that have the member
      */
    case Ambiguous(candidates: List[(MemberRef, Type)])

    /** Member not found in the type or any of its views. */
    case NotFound

  /** Adapt word to access a member, potentially through views.
    *
    * This function is used in two contexts:
    * 1. Member adaptation in duck types: When adapting via a member adapter like `.toString`
    * 2. Member selection through views: When checking if a type has a member (in Checker.adaptMember)
    *
    * The function:
    * 1. First checks if the type has the member directly
    * 2. If not, searches through views to find one that has the member
    * 3. Returns the adapted word and member type, or conflict/not found information
    *
    * @param word The word to adapt
    * @param memberName The member name to access
    * @param selectMember If true, select the member on the result word (word.select(memberName)).
    *                     If false, only adapt through view if needed but don't select the member.
    * @return MemberAdaptResult with success, ambiguous conflicts, or not found
    */
  def adaptMember(word: Word, memberName: String, selectMember: Boolean)
      (using defn: Definitions): MemberAdaptResult =
    val tpe = word.tpe

    // First try direct member access
    tpe.getTermMember(memberName) match
      case Some(memberType) =>
        val resultWord = if selectMember then word.select(memberName) else word
        return MemberAdaptResult.Success(resultWord)
      case None =>
        // Continue to search through views

    // Search through views
    var viewTypes = tpe.viewTypes
    val cands = new scala.collection.mutable.ArrayBuffer[(MemberRef, Type)]

    while viewTypes.nonEmpty do
      val viewType: MemberRef = viewTypes.head
      viewTypes = viewTypes.tail

      viewType.getTermMember(memberName) match
        case Some(memberType) =>
          cands += ((viewType, memberType))
        case None =>
          // This view doesn't have the member, continue searching
    end while

    if cands.size == 1 then
      val (viewRef, memberType) = cands.head
      // Select the view on the word
      val adaptedWord = word.select(viewRef.symbol.name)
      val resultWord = if selectMember then adaptedWord.select(memberName) else adaptedWord
      MemberAdaptResult.Success(resultWord)

    else if cands.isEmpty then
      MemberAdaptResult.NotFound

    else
      // Multiple candidates - ambiguous
      MemberAdaptResult.Ambiguous(cands.toList)

  def createSimpleAdapter(adapters: List[ParamAdapter], owner: Symbol)(using Definitions, Source): Adapter =
    if adapters.isEmpty then NoAdapter
    else (word, targetType) => adaptSimple(word, targetType, adapters, owner)

  def createVarargSpliceAdapter(adapters: List[ParamAdapter], owner: Symbol)
      (using defn: Definitions, source: Source): Adapter =

    if adapters.isEmpty then return NoAdapter

    (word, targetType) =>
      word.tpe.widen.dealias match
        case AppliedType(sym, elemType :: Nil) if sym == defn.List_type =>
          // Only try adapt if the type is List[X]
          val AppliedType(_, targetElemType :: Nil) = targetType: @unchecked
          adaptVarargSplice(word, targetElemType, elemType, adapters, owner)

        case tp =>
          Result.Failure(Nil)

  def adaptSimple
      (word: Word, targetType: Type, adapters: List[ParamAdapter], owner: Symbol)
      (using defn: Definitions, so: Source)
  : Result = Debug.trace(s"adapt ${word.show} to ${targetType.show} with ${adapters}", enable = false):
    val trials = new scala.collection.mutable.ArrayBuffer[Trial]()
    var remaining = adapters

    while remaining.nonEmpty do
      val adapter = remaining.head
      remaining = remaining.tail

      adapter match
        case ParamAdapter.Function(adapterSym) =>
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

        case ParamAdapter.Member(memberName) =>
          // Member adapter: apply if the word's type has the member (possibly through views)
          adaptMember(word, memberName, selectMember = true) match
            case MemberAdaptResult.Success(selected) =>
              // Get the member type from the selected word
              val memberType = selected.tpe
              // Widen to get underlying type (MemberRef -> ProcType)
              val widenedType = memberType.widen

              // Check that the member doesn't have normal parameters (only fields and parameterless methods are supported)
              widenedType match
                case procType: ProcType if procType.params.nonEmpty =>
                  // Member has normal parameters - not supported in member adapters
                  trials += Trial.Member(targetType, memberName, Error.TypeMismatch(widenedType))
                  // Continue to next adapter

                case _ =>
                  // OK - either a field or parameterless method
                  // Get effective result type (for parameterless methods, returns result type; otherwise returns type as-is)
                  val effectiveType = widenedType.effectiveResultType

                  // Check if the effective member type conforms to the target type
                  if Subtyping.conforms(effectiveType, targetType) then
                    // The member is already selected in the adapted word

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
                              // Note: this is double adaptation (view + member + auto), so provide context
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
                    // Note: this might be through a view, but the final type still doesn't match
                    trials += Trial.Member(word.tpe, memberName, Error.TypeMismatch(effectiveType))

            case MemberAdaptResult.NotFound =>
              // Member doesn't exist, even through views
              trials += Trial.Member(word.tpe, memberName, Error.MissingMember)

            case MemberAdaptResult.Ambiguous(candidates) =>
              // Multiple views have the member - ambiguous, skip this adapter
              trials += Trial.Member(word.tpe, memberName, Error.AmbiguousMember(candidates))

      end match
    end while

    Result.Failure(trials.toList)

  def adaptVarargSplice
      (word: Word, targetElemType: Type, elemType: Type, adapters: List[ParamAdapter], owner: Symbol)
      (using defn: Definitions, so: Source)
  : Result = Debug.trace(s"adapt splice ${word.show} from ${elemType.show} to ${targetElemType.show} with ${adapters}", enable = false):
    val trials = new scala.collection.mutable.ArrayBuffer[Trial]()
    var remaining = adapters

    while remaining.nonEmpty do
      val adapter = remaining.head
      remaining = remaining.tail

      adapter match
        case ParamAdapter.Function(adapterSym) =>
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

        case ParamAdapter.Member(memberName) =>
          // Member adapter for vararg splice: apply .map(_.memberName) or .map(_.memberName())
          // Create a dummy word with the element type to pass to adaptMember
          val dummyWord = Encoded(Block(Nil)(word.span))(elemType)

          adaptMember(dummyWord, memberName, selectMember = true) match
            case MemberAdaptResult.Success(selected) =>
              // Get the member type from the selected word
              val memberType = selected.tpe
              // Widen to get underlying type (MemberRef -> ProcType)
              val widenedType = memberType.widen
              // Get effective result type (for parameterless methods, returns result type; otherwise returns type as-is)
              val effectiveType = widenedType.effectiveResultType

              // Check that the member doesn't have normal parameters (only fields and parameterless methods are supported)
              widenedType match
                case procType: ProcType if procType.params.nonEmpty =>
                  // Member has normal parameters - not supported in member adapters
                  trials += Trial.Member(targetElemType, memberName, Error.TypeMismatch(widenedType))
                  // Continue to next adapter

                case _ =>
                  // OK - either a field or parameterless method
                  // Check if the effective member type conforms to the target element type
                  if Subtyping.conforms(effectiveType, targetElemType) then
                    // Create member accessor - it will handle auto resolution and view adaptation internally
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

            case MemberAdaptResult.Ambiguous(candidates) =>
              // Multiple views have the member - ambiguous
              trials += Trial.Member(elemType, memberName, Error.AmbiguousMember(candidates))

            case MemberAdaptResult.NotFound =>
              // Member doesn't exist
              trials += Trial.Member(elemType, memberName, Error.MissingMember)

      end match
    end while

    Result.Failure(trials.toList)

  /** Create a lambda function object that accesses a member: x => x.memberName or x => x.viewName.memberName()
    *
    * Uses adaptMember internally to handle member selection through views.
    *
    * @param memberName The member to access
    * @param paramType The parameter type that has the member (possibly through views)
    * @param memberType The type of the member
    * @param resultType The expected result type
    * @param owner The owner symbol for lambda creation
    * @param span The source span
    * @return Left with search node if auto resolution fails, Right with lambda if successful
    */
  private def createMemberAccessor
      (memberName: String, paramType: Type, memberType: Type, resultType: Type, owner: Symbol, span: Span)
      (using defn: Definitions, source: Source)
  : Either[AutoResolution.SearchNode.All, Word] =
    // Build the procedure type for the lambda - uses original paramType
    val procType = ProcType(
      tparams = Nil,
      params = NamedInfo("x", paramType) :: Nil,
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
              // Use adaptMember to select the member (handles both direct and view-based access)
              val selected = adaptMember(paramIdent, memberName, selectMember = true) match
                case MemberAdaptResult.Success(word) => word
                case _ => throw new Exception("Member should exist - already validated in caller")
              Apply(selected, args = Nil, autos = resolvedAutos)(span)

            Right(lambda)

          case None =>
            // Auto resolution failed
            Left(all)

      case _ =>
        // No auto parameters or not a parameterless method - create simple lambda
        val lambda = TreeOps.createLambda(procType, owner, Effects.Policy.Infer, span): (paramIdents, autoIdents) =>
          val paramIdent = paramIdents.head
          // Use adaptMember to select the member (handles both direct and view-based access)
          val selected = adaptMember(paramIdent, memberName, selectMember = true) match
            case MemberAdaptResult.Success(word) => word
            case _ => throw new Exception("Member should exist - already validated in caller")

          memberType match
            case memberProcType: ProcType if memberProcType.params.isEmpty =>
              // Parameterless method without autos
              selected.appliedTo()
            case _ =>
              // Field access
              selected

        Right(lambda)

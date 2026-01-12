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
    case LambdaInterface
    case NumericUnion(numericType: Type, unionType: Type)

  enum Error:
    case MissingMember
    case AmbiguousMember(views: List[Type])
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

          case Trial.LambdaInterface =>
            sb.append(s"\n  - lambda interface: not compatible  ✗")

          case Trial.NumericUnion(numericType, unionType) =>
            sb.append(s"\n  - numeric to union: ${numericType.show} is not a branch of ${unionType.show}  ✗")

          case Trial.Member(tp, member, error) =>
            error match
              case Error.MissingMember =>
                sb.append(s"\n  - .$member: missing ✗")

              case Error.AmbiguousMember(candidates) =>
                sb.append(s"\n  - .$member: ambiguous ✗")
                sb.append(s"\n    Multiple views have this member:")
                for viewType <- candidates do
                  sb.append(s"\n      - ${viewType.show}")

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

        // Try to adapt numeric types to union types
        // This is needed because we disallow subtyping from numeric types to unions
        if targetType.isUnionType && defn.isNumericType(curType) then
          val unionType = targetType.asUnionType
          // Check if the numeric type is a valid branch in the union
          val isValidBranch = unionType.branches.exists(branch => Subtyping.conforms(curType, branch))
          if isValidBranch then
            return Encoded(word)(targetType)
          else
            trials += Trial.NumericUnion(curType, targetType)

        if word.tpe.isLambdaType && targetType.isLambdaInterface then
          adaptToLambdaInterface(word, targetType) match
            case Some(adapted) => return adapted
            case None => trials += Trial.LambdaInterface

        // Try to adapt through views if target is a class/interface type
        if targetType.approx.isClassInfoType then
          adaptToView(word, targetType) match
            case Result.Success(adapted) => return adapted
            case Result.Failure(viewTrials) => trials ++= viewTrials
              // Continue to try adapters

        // Try to apply adapters before failing
        adapter(word, targetType) match
          case Result.Success(adapted) => adapted
          case Result.Failure(trials2) => throw new AdaptionFailure(word, targetType, trials.toSeq ++ trials2)

  /** Adapt the word to the target type
    *
    *     Byte ==> Int
    *     Char ==> Int
    *     Byte ==> Float
    *     Char ==> Float
    *     Int  ==> Float
    *
    * Note: Integer literals are handled polymorphically in NumericTyper.typeIntLit,
    * so they don't need adaptation here.
    *
    * Assumption: The type of the word does not conform to the target type.
    */
  private def coerceNumeric(word: Word, targetType: Type)(using defn: Definitions): Word =
    def fail() = throw new AdaptionFailure(word, targetType, Nil)

    val origType = word.tpe

    // Only handle non-literal cases (widening coercion)
    // Literals are already typed with correct type in NumericTyper
    if origType.isSubtype(defn.ByteType) then
      if targetType.isSubtype(defn.IntType) then
        word.select("toInt").appliedTo()

      else if targetType.isSubtype(defn.FloatType) then
        // Byte -> Float
        word.select("toFloat").appliedTo()

      else
        fail()

    else if origType.isSubtype(defn.CharType) then
      if targetType.isSubtype(defn.IntType) then
        word.select("toInt").appliedTo()

      else if targetType.isSubtype(defn.FloatType) then
        // Char -> Float
        word.select("toFloat").appliedTo()

      else
        fail()

    else if origType.isSubtype(defn.IntType) then
      if targetType.isSubtype(defn.FloatType) then
        // Int -> Float
        word.select("toFloat").appliedTo()

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
    case Ambiguous(views: List[Type])

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
      case Some(_) =>
        val resultWord = if selectMember then word.select(memberName) else word
        return MemberAdaptResult.Success(resultWord)
      case None =>
        // Continue to search through views

    // Collect intrinsic views
    val intrinsicViews = tpe.intrinsicViews

    val cands = new scala.collection.mutable.ArrayBuffer[MemberRef]

    // Search through intrinsic views
    for viewRef <- intrinsicViews do
      viewRef.getTermMember(memberName) match
        case Some(_) =>
          cands += viewRef
        case None =>
          // This view doesn't have the member, continue

    if cands.size == 1 then
      val viewRef = cands.head
      // Intrinsic view
      val adaptedWord = word.select(viewRef.symbol.name)
      val resultWord = if selectMember then adaptedWord.select(memberName) else adaptedWord
      MemberAdaptResult.Success(resultWord)

    else if cands.isEmpty then
      MemberAdaptResult.NotFound

    else
      // Multiple candidates - ambiguous
      val views = cands.toList.map(viewRef => viewRef.info)
      MemberAdaptResult.Ambiguous(views)


  /** Try adapt the word of a lambda type to the target lambda interface
    *
    * Assumption: The targetType must be a valid lambda interface
    */
  def adaptToLambdaInterface(word: Word, targetType: Type)(using Definitions): Option[Word] =
    val sourceLambdaType = word.tpe.asLambdaType
    targetType.getLambdaInterfaceType match
      case Some(targetLambdaType) if Subtyping.conforms(sourceLambdaType, targetLambdaType) =>
        word match
          case lambda: Lambda => Some(Encoded(lambda)(targetType))
          case _ =>
            // TODO: create eta-expansion to support the use case
            //
            // To not change semantics:
            //
            // - if word is idempotent, direct eta-expand
            // - otherwise, assign value to a variable and eta-expand the variable

            None

      case _ => None


  /** Adapt a value to a specific view type using .view[T] syntax
    *
    * Handles intrinsic views (declared in the class).
    *
    * @param word The value to adapt
    * @param viewType The view type to access
    * @return Success with the adapted word, or Failure with error information
    */
  def adaptToView(word: Word, viewType: Type)(using Definitions): Result =
    val wordType = word.tpe

    def qualify(candViewType: Type): Boolean = Subtyping.conforms(candViewType, viewType)

    // Check intrinsic views
    val intrinsicViews = wordType.intrinsicViews
    intrinsicViews.find(viewRef => qualify(viewRef)) match
      case Some(viewRef) =>
        // Intrinsic view found - select it from the word
        Result.Success(word.select(viewRef.symbol.name))
      case None =>
        // View not found
        val trials = intrinsicViews.map(viewRef => Trial.View(viewRef.info))
        Result.Failure(trials)

  def createSimpleAdapter(adapters: List[ParamAdapter], owner: Symbol, scope: typing.Scope)(using Definitions, Source): Adapter =
    if adapters.isEmpty then NoAdapter
    else (word, targetType) => adaptSimple(word, targetType, adapters, owner, scope)

  def createVarargSpliceAdapter(adapters: List[ParamAdapter], owner: Symbol, scope: typing.Scope)
      (using defn: Definitions, source: Source): Adapter =

    if adapters.isEmpty then return NoAdapter

    (word, targetType) =>
      word.tpe.widen.dealias match
        case AppliedType(sym, elemType :: Nil) if sym == defn.List_type =>
          // Only try adapt if the type is List[X]
          val AppliedType(_, targetElemType :: Nil) = targetType: @unchecked
          adaptVarargSplice(word, targetElemType, elemType, adapters, owner, scope)

        case _ =>
          Result.Failure(Nil)

  def adaptSimple
      (word: Word, targetType: Type, adapters: List[ParamAdapter], owner: Symbol, scope: typing.Scope)
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

                          // Collect local autos from the scope where adaptation happens
                          val localAutos = scope.collectLocalAutos
                          AutoResolution.resolve(procType, localAutos, Vector.empty, all, owner, word.span) match
                            case Some(autos) =>
                              // Apply with resolved auto arguments
                              val adapted = Apply(selected, args = Nil, autos = autos)(word.span)
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
      (word: Word, targetElemType: Type, elemType: Type, adapters: List[ParamAdapter], owner: Symbol, scope: typing.Scope)
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
              val adapterFun = TreeOps.etaExpand(adapterSym, owner, Nil, word.span)
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
                    createMemberAccessor(memberName, elemType, widenedType, targetElemType, owner, scope, word.span) match
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
    * @param scope The scope for auto resolution
    * @param span The source span
    * @return Left with search node if auto resolution fails, Right with lambda if successful
    */
  private def createMemberAccessor
      (memberName: String, paramType: Type, memberType: Type, resultType: Type, owner: Symbol, scope: typing.Scope, span: Span)
      (using defn: Definitions, source: Source)
  : Either[AutoResolution.SearchNode.All, Word] =
    // Build the lambda type for the lambda
    val lambdaType = LambdaType(
      params = paramType :: Nil,
      resultType = resultType,
      receives = Nil  // capture all parameters
    )

    // Check if member has auto parameters that need resolution
    memberType match
      case memberProcType: ProcType if memberProcType.autos.nonEmpty =>
        // Try to resolve auto parameters before creating lambda
        val all: AutoResolution.SearchNode.All = AutoResolution.SearchNode.All(scala.collection.mutable.ArrayBuffer())
        // Collect local autos from the scope where adaptation happens
        val localAutos = scope.collectLocalAutos
        AutoResolution.resolve(memberProcType, localAutos, Vector.empty, all, owner, span) match
          case Some(autos) =>
            // Auto resolution succeeded - create lambda that applies with resolved autos
            val lambda = TreeOps.createLambda(lambdaType, owner, span): paramIdents =>
              val paramIdent = paramIdents.head
              // Use adaptMember to select the member (handles both direct and view-based access)
              val selected = adaptMember(paramIdent, memberName, selectMember = true) match
                case MemberAdaptResult.Success(word) => word
                case _ => throw new Exception("Member should exist - already validated in caller")
              Apply(selected, args = Nil, autos = autos)(span)

            Right(lambda)

          case None =>
            // Auto resolution failed
            Left(all)

      case _ =>
        // No auto parameters or not a parameterless method - create simple lambda
        val lambda = TreeOps.createLambda(lambdaType, owner, span): paramIdents =>
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

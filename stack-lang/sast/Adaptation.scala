package sast

import sast.Trees.*
import sast.Types.*
import sast.Symbols.*

import ast.Positions.{Span, Source}
import common.Debug


object Adaptation:
  enum Adapter:
    case SimpleParam(adapters: List[ParamAdapter], owner: Symbol, scope: typing.Scope, source: Source)
    case VarargSplice(adapters: List[ParamAdapter], owner: Symbol, scope: typing.Scope, source: Source)
    case NullaryThunk(owner: Symbol, source: Source)

  enum Trial:
    case Member(tp: Type, member: String, error: Error)
    case Function(sym: Symbol)
    case LambdaInterface

  enum Error:
    case MissingMember
    case TypeMismatch(found: Type)
    case Invisible(sym: Symbol, site: Symbol)
    case AutoNotFound(search: AutoResolution.SearchNode.All)

  enum Result:
    case Success(word: Word)
    case Failure(trials: Seq[Trial])

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

          case Trial.LambdaInterface =>
            sb.append(s"\n  - lambda interface: not compatible  ✗")

          case Trial.Member(tp, member, error) =>
            error match
              case Error.MissingMember =>
                sb.append(s"\n  - .$member: missing ✗")

              case Error.TypeMismatch(found) =>
                sb.append(s"\n  - .$member: type mismatch ✗")
                sb.append(s"\n    Expected: ${tp.show}")
                sb.append(s"\n    Found:    ${found.show}")

              case _: Error.Invisible =>
                sb.append(s"\n  - .$member: invisible ✗")

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
  def adapt(word: Word, targetType: Type, adapters: List[Adapter])
      (using defn: Definitions)
  : Word = Debug.trace(s"adapting ${word.show} to ${targetType.show}", enable = false):

    assert(targetType.isFullyInstantiated, "not fully instantiated: " + targetType.show)
    assert(word.tpe.isFullyInstantiated, "not fully instantiated: " + word.tpe.show)

    val curType = word.tpe
    if Subtyping.conforms(curType, targetType) then
      word

    else if targetType.isVoidType && curType.isValueType then
      word.dropValue

    else

      val isNumeric = word.tpe.isNumericType && targetType.isNumericType

      if isNumeric && !Subtyping.conforms(word.tpe, targetType) then
        // Numeric coercion
        coerceNumeric(word, targetType)

      else
        val trials = new scala.collection.mutable.ArrayBuffer[Trial]()

        if word.tpe.isLambdaType && targetType.isLambdaInterface then
          adaptToLambdaInterface(word, targetType) match
            case Some(adapted) => return adapted
            case None => trials += Trial.LambdaInterface

        // Try to apply adapters before failing
        adaptWithAdapters(word, targetType, adapters) match
          case Result.Success(adapted) => adapted
          case Result.Failure(trials2) =>
            throw new AdaptionFailure(word, targetType, trials.toSeq ++ trials2)

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

      else if targetType.isSubtype(defn.LongType) then
        // Byte -> Long (exact)
        word.select("toLong").appliedTo()

      else if targetType.isSubtype(defn.FloatType) then
        // Byte -> Float
        word.select("toFloat").appliedTo()

      else
        fail()

    else if origType.isSubtype(defn.CharType) then
      if targetType.isSubtype(defn.IntType) then
        word.select("toInt").appliedTo()

      else if targetType.isSubtype(defn.LongType) then
        // Char -> Long (exact)
        word.select("toLong").appliedTo()

      else if targetType.isSubtype(defn.FloatType) then
        // Char -> Float
        word.select("toFloat").appliedTo()

      else
        fail()

    else if origType.isSubtype(defn.IntType) then
      if targetType.isSubtype(defn.LongType) then
        // Int -> Long (exact: 32-bit fits in 64-bit)
        word.select("toLong").appliedTo()

      else if targetType.isSubtype(defn.FloatType) then
        // Int -> Float
        word.select("toFloat").appliedTo()

      else
        fail()

    // Long -> Float is lossy (Float has a 53-bit mantissa), so it is not an
    // implicit widening; use `.toFloat` explicitly.

    else
      fail()

  /** Result of member adaptation.
    *
    * Captures all possible outcomes:
    * - Success: member found, word adapted
    * - NotFound: member not found
    */
  enum MemberAdaptResult:
    case Success(word: Word)
    case Invisible(symbol: Symbol, site: Symbol)
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
  def adaptMember(word: Word, memberName: String, site: Symbol, selectMember: Boolean)
      (using Definitions)
  : MemberAdaptResult =

    word.tpe.getTermMember(memberName) match
      case Some(ref: RefType) =>
        val sym = ref.symbol

        if sym.visibleIn(site) then
          val resultWord = if selectMember then TreeOps.smartSelect(word, memberName, word.span) else word
          MemberAdaptResult.Success(resultWord)

        else
          MemberAdaptResult.Invisible(sym, site)

      case _ =>
        MemberAdaptResult.NotFound


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

  /** Try to synthesize a nullary thunk `() => body` for a target type `() => T`.
    *
    * This is intentionally a last-resort adaptation. It keeps the definition-site
    * type explicit while allowing use sites to omit the wrapping lambda when the
    * expected type is already known to be nullary.
    */
  private def adaptToNullaryThunk(word: Word, targetType: Type, owner: Symbol, source: Source)(using defn: Definitions): Result =
    if !targetType.isLambdaType then return Result.Failure(Nil)

    val targetLambdaType = targetType.asLambdaType
    if targetLambdaType.params.nonEmpty then return Result.Failure(Nil)
    if !Subtyping.conforms(word.tpe, targetLambdaType.resultType) then return Result.Failure(Nil)

    val lambdaSym = TermSymbol.create("lambda", Flags.Fun | Flags.Synthetic, Visibility.Default, owner, word.span.toPos(using source))
    val lambda = Lambda(lambdaSym, Nil, targetLambdaType.receives, word)(word.span)
    defn.index.add(lambdaSym, lambda.tpe)
    Result.Success(lambda)


  def createSimpleAdapters(adapters: List[ParamAdapter], owner: Symbol, scope: typing.Scope)(using Source): List[Adapter] =
    if adapters.isEmpty then Nil
    else Adapter.SimpleParam(adapters, owner, scope, summon[Source]) :: Nil

  def createVarargSpliceAdapters(adapters: List[ParamAdapter], owner: Symbol, scope: typing.Scope)
      (using source: Source): List[Adapter] =
    Adapter.VarargSplice(adapters, owner, scope, source) :: Nil

  private def adaptWithAdapters(word: Word, targetType: Type, adapters: List[Adapter])
      (using defn: Definitions)
  : Result =
    val trials = new scala.collection.mutable.ArrayBuffer[Trial]()
    var success: Option[Word] = None

    val it = adapters.iterator
    while success.isEmpty && it.hasNext do
      val result =
        it.next() match
          case Adapter.SimpleParam(paramAdapters, owner, scope, source) =>
            given Source = source
            adaptSimple(word, targetType, paramAdapters, owner, scope)

          case Adapter.VarargSplice(paramAdapters, owner, scope, source) =>
            given Source = source
            word.tpe.widen.dealias match
              case AppliedType(sym, elemType :: Nil) if sym == defn.List_type =>
                val AppliedType(_, targetElemType :: Nil) = targetType: @unchecked
                if Subtyping.conforms(elemType, targetElemType) then
                  // elemType <: targetElemType but List is invariant.
                  // Safe: after erasure both become List with identical runtime representation.
                  Result.Success(Encoded(word)(AppliedType(defn.List_type, targetElemType :: Nil)))
                else
                  adaptVarargSplice(word, targetElemType, elemType, paramAdapters, owner, scope)
              case _ =>
                Result.Failure(Nil)

          case Adapter.NullaryThunk(owner, source) =>
            adaptToNullaryThunk(word, targetType, owner, source)

      result match
        case Result.Success(word) => success = Some(word)
        case Result.Failure(ts) => trials ++= ts

    success match
      case Some(word) => Result.Success(word)
      case None => Result.Failure(trials.toSeq)

  def adaptSimple
      (word: Word, targetType: Type, adapters: List[ParamAdapter], owner: Symbol, scope: typing.Scope)
      (using Definitions, Source)
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
            val procType = adapterSym.tpe.asProcType
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
          adaptMember(word, memberName, owner, selectMember = true) match
            case MemberAdaptResult.Success(selected) =>
              // Get the member type from the selected word
              val memberType = selected.tpe
              // Widen to get underlying type (MemberRef -> ProcType)
              val widenedType = memberType.widen

              // Check that the member doesn't have normal parameters (only fields and parameterless methods are supported)
              widenedType match
                case procType: ProcType if procType.postParamCount > 0 =>
                  // Member has normal post-parameters - not supported in member adapters
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
                              // Uses smartApply to flatten partial extension method applications
                              val adapted = TreeOps.smartApply(selected, args = Nil, autos = autos)(word.span)
                              return Result.Success(adapted)
                            case None =>
                              // Auto resolution failed - record trial and try next adapter
                              // Note: this is double adaptation (view + member + auto), so provide context
                              trials += Trial.Member(word.tpe, memberName, Error.AutoNotFound(all))
                        else
                          // No auto parameters, simple application
                          // Uses smartApply to flatten partial extension method applications
                          val adapted = TreeOps.smartApply(selected, args = Nil, autos = Nil)(word.span)
                          return Result.Success(adapted)

                      case _ =>
                        // For val members, just return the selection
                        return Result.Success(selected)
                  else
                    // Member exists but type doesn't match, try next adapter
                    // Note: this might be through a view, but the final type still doesn't match
                    trials += Trial.Member(word.tpe, memberName, Error.TypeMismatch(effectiveType))
              end match

            case MemberAdaptResult.NotFound =>
              trials += Trial.Member(word.tpe, memberName, Error.MissingMember)

            case MemberAdaptResult.Invisible(sym, site) =>
              trials += Trial.Member(word.tpe, memberName, Error.Invisible(sym, site))

      end match
    end while

    Result.Failure(trials.toList)

  def adaptVarargSplice
      (word: Word, targetElemType: Type, elemType: Type, adapters: List[ParamAdapter], owner: Symbol, scope: typing.Scope)
      (using Definitions, Source)
  : Result = Debug.trace(s"adapt splice ${word.show} from ${elemType.show} to ${targetElemType.show} with ${adapters}", enable = false):
    val trials = new scala.collection.mutable.ArrayBuffer[Trial]()
    var remaining = adapters

    while remaining.nonEmpty do
      val adapter = remaining.head
      remaining = remaining.tail

      adapter match
        case ParamAdapter.Function(adapterSym) =>
          if adapterSym.isFunction then
            val procType = adapterSym.tpe.asProcType
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

          adaptMember(dummyWord, memberName, owner, selectMember = true) match
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
              end match

            case MemberAdaptResult.Invisible(sym, site) =>
              trials += Trial.Member(word.tpe, memberName, Error.Invisible(sym, site))

            case MemberAdaptResult.NotFound =>
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
      (using Definitions, Source)
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
        val lambdaSym = TermSymbol.create("lambda", Flags.Fun | Flags.Synthetic, Visibility.Default, owner, span.toPos)
        val localAutos = scope.collectLocalAutos
        AutoResolution.resolve(memberProcType, localAutos, Vector.empty, all, lambdaSym, span) match
          case Some(autos) =>
            // Auto resolution succeeded - create lambda that applies with resolved autos
            val lambda = TreeOps.createLambdaWithSymbol(lambdaSym, lambdaType, span): paramIdents =>
              val paramIdent = paramIdents.head
              // Use adaptMember to select the member (handles both direct and view-based access)
              val selected = adaptMember(paramIdent, memberName, lambdaSym, selectMember = true) match
                case MemberAdaptResult.Success(word) => word
                case _ => throw new Exception("Member should exist - already validated in caller")
              TreeOps.smartApply(selected, args = Nil, autos = autos)(span)

            Right(lambda)

          case None =>
            // Auto resolution failed
            Left(all)

      case _ =>
        val lambdaSym = TermSymbol.create("lambda", Flags.Fun | Flags.Synthetic, Visibility.Default, owner, span.toPos)
        // No auto parameters or not a parameterless method - create simple lambda
        val lambda = TreeOps.createLambdaWithSymbol(lambdaSym, lambdaType, span): paramIdents =>
          val paramIdent = paramIdents.head
          // Use adaptMember to select the member (handles both direct and view-based access)
          val selected = adaptMember(paramIdent, memberName, lambdaSym, selectMember = true) match
            case MemberAdaptResult.Success(word) => word
            case _ => throw new Exception("Member should exist - already validated in caller")

          memberType match
            case memberProcType: ProcType if memberProcType.params.isEmpty =>
              // Parameterless method without autos
              TreeOps.smartApply(selected, args = Nil, autos = Nil)(span)
            case _ =>
              // Field access
              selected

        Right(lambda)

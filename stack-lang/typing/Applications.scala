package typing

import ast.{ Trees => Ast }
import ast.Positions.*

import sast.*
import sast.Trees.*
import sast.Types.*

import reporting.Reporter

import Inference.*
import scala.collection.mutable

trait Applications extends DynamicTyper:
  this: Namer =>

  /** Handles explicit call syntax f(arg1, arg2, ...) */
  def transformCall(apply: Ast.Apply)
      (using defn: Definitions, sc: Scope, rp: Reporter, so: Source, tt: TargetType, tvars: TypeVars, cs: ControlScope)
  : Word =

    apply.fun match
      case Ast.Select(qual, name) =>
        val qualTyped =
          given TargetType = TargetType.Member
          transform(qual)

        if qualTyped.tpe.isError then return errorWord(apply.span)

        val selectReporter = rp.fresh(buffer = true)
        val fun =
          given Reporter = selectReporter
          Inference.freshIsolate:
            resolveTypedSelect(qualTyped, name, apply.fun.span, allowAdapt = true)

        if !selectReporter.hasErrors then
          applyResolvedFun(fun, apply.args, apply.span)

        else
          tryDynamicCall(qualTyped, name, apply.args, apply.span) match
            case Some(result) =>
              result

            case None =>
              selectReporter.commit(rp)
              errorWord(apply.span)

      case _ =>
        val fun =
          given TargetType = TargetType.Call
          transform(apply.fun)

        applyResolvedFun(fun, apply.args, apply.span)

  /** Apply an already-typed callee to call arguments written as `f(...)`.
    *
    * Contract on `fun`:
    *   - It is already the resolved callee for the call site.
    *
    *   - It may be any callee accepted by call syntax `f(...)`, including
    *     ordinary function values, methods, lambda-interface values, and
    *     partially applied extension methods.
    *
    *   - It may still be polymorphic.
    *
    *   - It does not need to be normalized to a plain `ProcType`.
    *
    * Contract on `args`:
    *   - They are raw AST call arguments from the source program.
    *
    *   - They may contain either positional arguments only, or a mix of
    *     positional and named arguments accepted by call syntax `f(...)`.
    *
    * Behavior:
    *   - This helper instantiates a polymorphic callee before checking
    *     arguments.
    *
    *   - This helper performs named-argument checks, vararg handling,
    *     default insertion, and target-type-directed argument typing.
    *
    *   - This helper delegates final application shaping to
    *     `TreeOps.smartApply` / `Autos.resolve`.
    */
  def applyResolvedFun(fun: Word, args: List[Ast.CallArg], applySpan: Span)
      (using defn: Definitions, sc: Scope, rp: Reporter, so: Source, tt: TargetType, tvars: TypeVars, cs: ControlScope)
  : Word =

    var fun1 = fun
    val funType = fun1.tpe

    if funType.isInvokableType then
      if funType.isPolyType then
        fun1 = TreeOps.instantiatePoly(funType.asProcType, fun1)

      val invokeType = fun1.tpe.asInvokableType
      val paramSize = invokeType.paramTypes.size

      Inference.conditionalInstantiate(invokeType.resultType, tt)

      val preArgTypes = invokeType.preParamTypes
      if preArgTypes.size != 0 then
        Reporter.error(
          s"The postfix call syntax cannot be used, as the function takes prefix arguments",
          fun1.pos)
        errorWord(applySpan)

      else if args.size < invokeType.minimumArgs ||
              !invokeType.hasVararg && args.size > paramSize then
        val mod = if invokeType.hasVararg then "at least " else ""
        val size = if invokeType.hasVararg then invokeType.minimumArgs else paramSize
        Reporter.error(
          s"The function expects $mod$size argument(s), found = ${args.size}",
          applySpan.toPos)
        errorWord(applySpan)

      else
        val hasNamed = args.exists(_.isInstanceOf[Ast.NamedArg])
        val argsTypedOpt =
          if hasNamed then
            invokeType match
              case proc: ProcType =>
                if proc.hasVararg then
                  val elementType = proc.postParamTypes.last.stripVarargs
                  if elementType.isMixedType then
                    transformVarargsMixed(args, proc, applySpan)
                  else if elementType.isNamedType then
                    transformVarargsNamed(args, proc, applySpan)
                  else
                    Reporter.error("Named arguments are not supported for functions with varargs", applySpan.toPos)
                    None
                else
                  transformNamedArgs(args, proc, applySpan)
              case _ =>
                Reporter.error(
                  "Named arguments are only supported for declared functions and methods (not lambda/function-value calls)",
                  applySpan.toPos)
                None
          else
            val positional = args.asInstanceOf[List[Ast.Word]]
            val numProvided = positional.size
            if invokeType.hasVararg then
              invokeType match
                case proc: ProcType if proc.postParamTypes.last.stripVarargs.isNamedType =>
                  transformVarargsNamed(positional, proc, applySpan)
                case _ =>
                  Some(transformVarargs(positional, invokeType.paramTypes, applySpan))
            else
              Some:
                val providedArgs = transformArgs(positional, invokeType.paramTypes.take(numProvided))
                val defaultArgs = invokeType match
                  case proc: ProcType => Defaults.synthesizePostDefaults(proc, numProvided, applySpan)
                  case _ => Nil
                providedArgs ++ defaultArgs

        if argsTypedOpt.isEmpty then
          errorWord(applySpan)

        else
          val argsTyped = argsTypedOpt.get
          if invokeType.autoTypes.isEmpty then
            TreeOps.smartApply(fun1, argsTyped, autos = Nil)(applySpan).adapt
          else
            Autos.resolve(fun1, argsTyped, applySpan, config).adapt

    else
      if !fun1.tpe.isError then
        Reporter.error(s"Not a function: " + fun1.tpe.show, fun1.pos)
      errorWord(applySpan)

  /** Check a dotless call such as `str1 + str2` */
  def transformDotlessCall(call: Ast.InfixOperatorCall)
      (using defn: Definitions, sc: Scope, rp: Reporter, so: Source, tt: TargetType, tvars: TypeVars, cs: ControlScope)
  : Word =

    val Ast.InfixOperatorCall(obj, meth, arg) = call

    val objSpan = obj.span

    // Delegate member resolution to transformSelect
    val selectAst = Ast.Select(obj, meth.name)(objSpan | meth.span)
    var fun = transformSelect(selectAst)(using defn, sc, rp, so, TargetType.Call, tvars, cs)

    if fun.tpe.isError then return errorWord(call.span)

    if !fun.tpe.isProcType then
      Reporter.error(s"The member ${meth.name} is not a method", meth.pos)
      return errorWord(meth.span)

    if fun.tpe.isPolyType then
      fun = TreeOps.instantiatePoly(fun.tpe.asProcType, fun)

    val procType = fun.tpe.asProcType
    val paramSize = procType.paramTypes.size

    // Conditionally apply context instantiation
    Inference.conditionalInstantiate(procType.resultType, tt)

    if paramSize != 1 then
      Reporter.error(
        s"The method ${meth.name} takes ${paramSize} parameters. The dotless call syntax only supports methods of one parameter",
        meth.span.toPos
      )
      errorWord(meth.span)

    else
      val paramType = procType.paramTypes.head
      val argTyped = transformArg(arg, paramType)
      Autos.resolve(fun, argTyped :: Nil, call.span, config).adapt

  /** Handles infix call formed by expression typer `1 + 2` */
  def transformInfixCall(call: Ast.InfixCall)
    (using defn: Definitions, sc: Scope, rp: Reporter, so: Source, tt: TargetType, tvars: TypeVars, cs: ControlScope)
  : Word =

    val Ast.InfixCall(preArgs, funAst, postArgs) = call

    var fun =
      // infix call should not trigger apply insertion
      given TargetType = TargetType.Call
      transform(funAst)

    if !fun.tpe.isProcType then
      Reporter.error("Expect a function, found = " + fun.tpe.show, funAst.pos)
      return errorWord(call.span)

    if fun.tpe.isPolyType then
      fun = TreeOps.instantiatePoly(fun.tpe.asProcType, fun)

    val procType = fun.tpe.asProcType
    val preParamCount = procType.preParamCount
    val postParamCount = procType.postParamCount

    // Conditionally apply context instantiation
    Inference.conditionalInstantiate(procType.resultType, tt)

    assert(!procType.hasVararg, "Infix call cannot have varargs")

    if preArgs.size != preParamCount then
      Reporter.error(
        s"Function ${fun.show} expects $preParamCount pre arguments, found = ${preArgs.size}",
        fun.pos)
      errorWord(call.span)

    else if postArgs.size != procType.postParamCount then
      Reporter.error(
        s"Function ${fun.show} expects $postParamCount post argument(s), found = ${postArgs.size}",
        fun.pos)
      errorWord(call.span)

    else
      val preArgs2 = transformArgs(preArgs, procType.preParamTypes)
      val postArgs2 =
        if procType.hasVararg then
          transformVarargs(postArgs, procType.postParamTypes, call.span)

        else
          transformArgs(postArgs, procType.postParamTypes)


      Autos.resolve(fun, preArgs2 ++ postArgs2, call.span, config).adapt

  /** Assumes that the argument count requirement is satisfied */
  def transformArgs
      (args: List[Ast.Word], params: List[Type])
      (using defn: Definitions, sc: Scope, rp: Reporter, so: Source, tvars: TypeVars, cs: ControlScope)
  : List[Word] =

    for (arg, paramType) <- args.zip(params)
    yield transformArg(arg, paramType)

  def transformArg
      (arg: Ast.Word, paramType: Type)
      (using defn: Definitions, sc: Scope, rp: Reporter, so: Source, tvars: TypeVars, cs: ControlScope)
  : Word =
    if paramType.isFullyInstantiated then
      // Only propagate fully instantiated type inside, which will be used both
      // for type inference and adaptation
      given TargetType = TargetType.Known(paramType)
      transform(arg)

    else
      // If paramType is not fully initialized, we cannot use adapters, but the
      // partially known type can be used for type inference.
      given TargetType = Inference.partiallyKnown(paramType)
      val argTyped = transform(arg)
      if tvars.tryOrRevert { Subtyping.conforms(argTyped.tpe.widen, paramType) } then
        argTyped
      else
        Reporter.error(s"Expect type ${paramType.show}, found = ${argTyped.tpe.show}", arg.pos)
        errorWord(arg.span)


  /** Build an application from arguments that are already typed
    *
    * This is the error-tolerant counterpart of `Trees.appliedTo`, which
    * requires the arguments to already conform to the parameter types. Here
    * each argument is adapted to its parameter type, which also takes care of
    * inference when either side is not yet fully instantiated. The result may
    * therefore still contain unconstrained type variables --- they are
    * resolved, or reported, by the enclosing isolate.
    *
    * When the application cannot be built, a dummy word of the result type is
    * returned, so that typing of the enclosing expression can continue.
    */
  def applyTypedArgs(fun: Word, args: List[Word], applySpan: Span)
      (using defn: Definitions, sc: Scope, rp: Reporter, so: Source, tvars: TypeVars)
  : Word =

    if fun.tpe.isError then
      errorWord(applySpan)

    else if !fun.tpe.isProcType then
      Reporter.error("Not a function: " + fun.tpe.show, fun.pos)
      errorWord(applySpan)

    else
      val procType = fun.tpe.asProcType
      val paramTypes = procType.paramTypes

      assert(procType.tparams.isEmpty, "type params not supplied")
      assert(procType.autos.isEmpty, "autos not supplied")

      def dummy = dummyWord(procType.resultType, applySpan)

      if paramTypes.size != args.size then
        Reporter.error(
          s"The function expects ${paramTypes.size} argument(s), found = ${args.size}",
          applySpan.toPos)
        dummy

      else if args.exists(_.tpe.isError) then
        dummy

      else
        val args2 =
          for (arg, paramType) <- args.zip(paramTypes) yield
            Checker.adapt(arg, TargetType.Known(paramType))

        if args2.exists(_.tpe.isError) then
          dummy

        else
          val span = args2.foldLeft(fun.span)(_ | _.span)
          Apply(fun, args2, autos = Nil)(span)

  /** Assumes that the argument count requirement is satisfied */
  def transformVarargs
      (args: List[Ast.Word], paramTypes: List[Type], span: Span)
      (using defn: Definitions, sc: Scope, rp: Reporter, so: Source, tvars: TypeVars, cs: ControlScope)
  : List[Word] =

    val paramTypesFix :+ paramTypeFlex = paramTypes: @unchecked
    val (argsFix, argsFlex) = args.splitAt(paramTypesFix.size)

    val argsFixTyped = transformArgs(argsFix, paramTypesFix)

    val elementType = paramTypeFlex.stripVarargs
    val flexSpan = argsFlex.headOption.map(_.span).getOrElse(span)

    // Every vararg pack is collected the same way, whatever the callee:
    //
    //     [a, b, c]     ~>  List.builder[T](3).add(a).add(b).add(c).result
    //     [a, ..xs, b]  ~>  List.builder[T](0).add(a).addList(xs).add(b).result
    //
    // `add`/`addAll` return the builder, so the pack stays a single expression
    // and needs no local binding. The capacity is the number of plain elements,
    // and 0 once the pack splices, since the final length is then unknown.
    if argsFlex.isEmpty then
      // An empty pack is just the empty list.
      val tapply = Ident(defn.List_empty)(flexSpan).appliedToTypes(elementType)
      return argsFixTyped :+ Apply(tapply, args = Nil, autos = Nil)(flexSpan)

    val plainCount = argsFlex.count(arg => !isSplice(arg))
    val capacity = if argsFlex.exists(isSplice) then 0 else plainCount

    var pack = newPack(capacity, elementType, flexSpan)

    for arg <- argsFlex do
      arg match
        case Ast.Expr(Ast.Ident("..") :: rest) =>
          if rest.size != 1 then
            Reporter.error(".. should be followed by exact one word, found = " + rest.size, arg.pos)

          else
            pack = addAll(pack, rest.head, paramTypeFlex)

        case Ast.Apply(Ast.Ident(".."), callArgs) =>
          callArgs match
            case List(word: Ast.Word) =>
              pack = addAll(pack, word, paramTypeFlex)

            case _ =>
              Reporter.error(".. should be followed by exact one word, found = " + callArgs.size, arg.pos)

        case Ast.PrefixOperatorCall(Ast.Ident(".."), spliced) =>
          pack = addAll(pack, spliced, paramTypeFlex)

        case Ast.Ident("..") =>
          Reporter.error(".. should be followed by exact one word, found = 0", arg.pos)

        case _ =>
          val argTyped = transformArg(arg, elementType)
          if !argTyped.tpe.isError then
            pack = applyTypedArgs(pack.select("add"), argTyped :: Nil, argTyped.span)
      end match

    argsFixTyped :+ finishPack(pack, flexSpan)

  private def intLiteral(value: Int, span: Span)(using defn: Definitions): Word =
    Literal(Constant.Int(value))(defn.IntType, span)

  /** Start a vararg pack: `List.builder[T](capacity)`.
    *
    * `capacity` is what the caller expects to add; an inaccurate value only
    * costs the builder a little extra work.
    */
  private def newPack(capacity: Int, elementType: Type, span: Span)
      (using defn: Definitions, sc: Scope, rp: Reporter, so: Source, tvars: TypeVars)
  : Word =
    val newBuilder = Ident(defn.List_builder)(span).appliedToTypes(elementType)
    applyTypedArgs(newBuilder, intLiteral(capacity, span) :: Nil, span)

  /** Close a vararg pack: `.result`. */
  private def finishPack(pack: Word, span: Span)
      (using defn: Definitions, sc: Scope, rp: Reporter, so: Source, tvars: TypeVars)
  : Word =
    applyTypedArgs(pack.select("result"), Nil, span)

  /** Splice a whole list into the pack being built. */
  private def addAll(pack: Word, arg: Ast.Word, paramTypeFlex: Type)
      (using defn: Definitions, sc: Scope, rp: Reporter, so: Source, tvars: TypeVars, cs: ControlScope)
  : Word =
    val argTyped = transformArg(arg, paramTypeFlex)

    if argTyped.tpe.isError then pack
    else applyTypedArgs(pack.select("addList"), argTyped :: Nil, argTyped.span)

  private def isSplice(arg: Ast.Word): Boolean =
    arg match
      case Ast.Expr(Ast.Ident("..") :: _) => true
      case Ast.Apply(Ast.Ident(".."), _) => true
      case Ast.PrefixOperatorCall(Ast.Ident(".."), _) => true
      case Ast.Ident("..") => true
      case _ => false

  protected def transformNamedArgs(rawArgs: List[Ast.CallArg], procType: ProcType, callSpan: Span)
      (using defn: Definitions, sc: Scope, rp: Reporter, so: Source, tvars: TypeVars, cs: ControlScope)
  : Option[List[Word]] =
    val postParams = procType.params.drop(procType.preParamCount)
    val postParamTypes = procType.postParamTypes
    val postParamCount = procType.postParamCount
    val minPostArgs = procType.minimumPostArgs

    val positional = mutable.ArrayBuffer.empty[Ast.Word]
    val named = mutable.ArrayBuffer.empty[Ast.NamedArg]
    var seenNamed = false

    var ok = true

    val rawIt = rawArgs.iterator
    while ok && rawIt.hasNext do
      rawIt.next() match
        case word: Ast.Word =>
          if seenNamed then
            Reporter.error("Positional arguments cannot appear after named arguments", word.pos)
            ok = false
          else
            positional += word
        case namedArg: Ast.NamedArg =>
          seenNamed = true
          named += namedArg

    if ok && positional.size > postParamCount then
      Reporter.error(s"The function expects $postParamCount argument(s), found = ${rawArgs.size}", callSpan.toPos)
      ok = false

    val nameToIndex = postParams.zipWithIndex.map((p, i) => p.name -> i).toMap
    // Each slot carries the argument word and the named-arg key (if supplied by name)
    val slots = Array.fill[Option[(Ast.Word, Option[String])]](postParamCount)(None)

    if ok then
      for (arg, i) <- positional.zipWithIndex do
        slots(i) = Some((arg, None))

    val seenNames = mutable.HashSet.empty[String]
    val namedIt = named.iterator
    while ok && namedIt.hasNext do
      val namedArg = namedIt.next()
      val name = namedArg.name
      if seenNames(name) then
        Reporter.error(s"Parameter '$name' is specified more than once", namedArg.pos)
        ok = false
      else
        seenNames += name
        nameToIndex.get(name) match
          case None =>
            Reporter.error(s"Unknown named argument '$name'", namedArg.pos)
            ok = false
          case Some(idx) =>
            if slots(idx).nonEmpty then
              // idx < positional.size always holds here: seenNames guards same-name duplicates,
              // and two distinct names cannot share an index in nameToIndex
              Reporter.error(s"Parameter '$name' is already provided positionally", namedArg.pos)
              ok = false
            else
              slots(idx) = Some((namedArg.arg, Some(name)))

    val typed = mutable.ArrayBuffer.empty[Word]
    var i = 0
    while ok && i < postParamCount do
      slots(i) match
        case Some((arg, nameOpt)) =>
          val argTyped = transformArg(arg, postParamTypes(i))
          typed += nameOpt.fold(argTyped)(name => wrapNamedArg(name, argTyped))
        case None =>
          if i >= minPostArgs then
            typed += synthesizePostDefaultAt(procType, i, callSpan)
          else
            Reporter.error(s"Missing required parameter '${postParams(i).name}'", callSpan.toPos)
            ok = false
      i += 1

    if ok then Some(typed.toList) else None

  /** Wrap a named argument value in a `namedArg("key", value)` call.
    *
    * The typer inserts this after type-checking whenever a named argument
    * `key = expr` is resolved against a parameter, so that backends can
    * recover both the name and the value from the SAST.
    */
  private def wrapNamedArg(name: String, arg: Word)(using defn: Definitions): Word =
    val span = arg.span
    val fun = Ident(defn.compile_namedArg)(span).appliedToTypes(arg.tpe.widen)
    Apply(fun, List(StringLit(name)(span), arg), Nil)(span)

  /** Handle a call to a vararg function whose vararg element type is Mixed[T].
    *
    * Positional args before the vararg boundary fill fixed parameters as usual.
    * In the vararg portion, positional args and splices are typed normally;
    * named args are wrapped with `namedArg("key", value)` so the backend can
    * emit them as keyword arguments.
    */
  private def transformVarargsMixed(callArgs: List[Ast.CallArg], proc: ProcType, callSpan: Span)
      (using defn: Definitions, sc: Scope, rp: Reporter, so: Source, tvars: TypeVars, cs: ControlScope)
  : Option[List[Word]] =
    val paramTypesFix :+ paramTypeFlex = proc.postParamTypes: @unchecked
    val fixCount = paramTypesFix.size
    val elementType = paramTypeFlex.stripVarargs

    // Validate positional-before-named ordering and no duplicate names
    var seenNamed = false
    val seenNames = mutable.HashSet.empty[String]
    var ok = true

    for callArg <- callArgs do
      callArg match
        case word: Ast.Word if seenNamed =>
          // splices after named args are allowed (treated as positional spread)
          word match
            case Ast.Expr(Ast.Ident("..") :: _) | Ast.PrefixOperatorCall(Ast.Ident(".."), _) =>
            case Ast.Apply(Ast.Ident(".."), _) =>
            case _ =>
              Reporter.error("Positional arguments cannot appear after named arguments", word.pos)
              ok = false
        case namedArg: Ast.NamedArg =>
          seenNamed = true
          if !seenNames.add(namedArg.name) then
            Reporter.error(s"Named argument '${namedArg.name}' is specified more than once", namedArg.pos)
            ok = false
        case _ =>

    if !ok then return None

    // Since positional args precede named args, the first fixCount args are
    // positional and fill the fixed parameters; the rest go into the vararg.
    val (fixCallArgs, flexCallArgs) = callArgs.splitAt(fixCount)

    val fixedTyped = transformArgs(fixCallArgs.asInstanceOf[List[Ast.Word]], paramTypesFix)

    val flexSpan = flexCallArgs.headOption.map(_.span).getOrElse(callSpan)

    var lastFlexArg: Word = newPack(flexCallArgs.size, elementType, flexSpan)

    def checkSpliceMixed(arg: Ast.Word): Unit =
      // Splice target types as List[T] (Mixed[T] = T at runtime)
      val argTyped = transformArg(arg, paramTypeFlex)
      if !argTyped.tpe.isError then
        lastFlexArg = applyTypedArgs(lastFlexArg.select("addList"), argTyped :: Nil, argTyped.span)

    for callArg <- flexCallArgs do
      callArg match
        case Ast.Expr(Ast.Ident("..") :: rest) =>
          if rest.size != 1 then
            Reporter.error(".. should be followed by exact one word, found = " + rest.size, callArg.pos)
          else
            checkSpliceMixed(rest.head)

        case Ast.Apply(Ast.Ident(".."), spliceArgs) =>
          spliceArgs match
            case List(word: Ast.Word) => checkSpliceMixed(word)
            case _ =>
              Reporter.error(".. should be followed by exact one word, found = " + spliceArgs.size, callArg.pos)

        case Ast.PrefixOperatorCall(Ast.Ident(".."), arg) =>
          checkSpliceMixed(arg)

        case word: Ast.Word =>
          val argTyped = transformArg(word, elementType)
          if !argTyped.tpe.isError then
            lastFlexArg = applyTypedArgs(lastFlexArg.select("add"), argTyped :: Nil, argTyped.span)

        case namedArg: Ast.NamedArg =>
          val argTyped = transformArg(namedArg.arg, elementType)
          if !argTyped.tpe.isError then
            val wrapped = wrapNamedArg(namedArg.name, argTyped)
            lastFlexArg = applyTypedArgs(lastFlexArg.select("add"), wrapped :: Nil, wrapped.span)

    Some(fixedTyped :+ finishPack(lastFlexArg, flexSpan))

  /** Handle a call to a vararg function whose vararg element type is Named[T].
    *
    * Only keyword arguments are accepted in the vararg portion.
    * Positional arguments and splices are rejected at the call site.
    */
  private def transformVarargsNamed(callArgs: List[Ast.CallArg], proc: ProcType, callSpan: Span)
      (using defn: Definitions, sc: Scope, rp: Reporter, so: Source, tvars: TypeVars, cs: ControlScope)
  : Option[List[Word]] =
    val paramTypesFix :+ paramTypeFlex = proc.postParamTypes: @unchecked
    val fixCount = paramTypesFix.size
    val elementType = paramTypeFlex.stripVarargs

    // Validate: fixed-param prefix may be positional; vararg portion must be keyword-only
    val seenNames = mutable.HashSet.empty[String]
    var positionalCount = 0
    var seenNamed = false
    var ok = true

    for callArg <- callArgs do
      callArg match
        case namedArg: Ast.NamedArg =>
          seenNamed = true

          if !seenNames.add(namedArg.name) then
            Reporter.error(s"Named argument '${namedArg.name}' is specified more than once", namedArg.pos)
            ok = false

        case Ast.Expr(Ast.Ident("..") :: _)
           | Ast.PrefixOperatorCall(Ast.Ident(".."), _)
           | Ast.Apply(Ast.Ident(".."), _) =>
          Reporter.error("..Named[T] does not support splice", callArg.pos)
          ok = false

        case _: Ast.Word =>
          if !seenNamed && positionalCount < fixCount then
            positionalCount += 1
          else
            Reporter.error("..Named[T] only accepts keyword arguments in the vararg portion", callArg.pos)
            ok = false

    if !ok then return None

    val (fixCallArgs, flexCallArgs) = callArgs.splitAt(fixCount)

    val fixedTyped = transformArgs(fixCallArgs.asInstanceOf[List[Ast.Word]], paramTypesFix)

    val flexSpan = flexCallArgs.headOption.map(_.span).getOrElse(callSpan)

    var lastFlexArg: Word = newPack(flexCallArgs.size, elementType, flexSpan)

    for callArg <- flexCallArgs do
      callArg match
        case namedArg: Ast.NamedArg =>
          val argTyped = transformArg(namedArg.arg, elementType)
          if !argTyped.tpe.isError then
            val wrapped = wrapNamedArg(namedArg.name, argTyped)
            lastFlexArg = applyTypedArgs(lastFlexArg.select("add"), wrapped :: Nil, wrapped.span)
        case _ =>

    Some(fixedTyped :+ finishPack(lastFlexArg, flexSpan))

  private def synthesizePostDefaultAt(procType: ProcType, postIndex: Int, span: Span)
      (using defn: Definitions)
  : Word =
    val minPostArgs = procType.minimumPostArgs
    assert(postIndex >= minPostArgs, s"postIndex = $postIndex, minimumPostArgs = $minPostArgs")
    val defaultIndex = postIndex - minPostArgs
    val defaultValue = procType.defaults(defaultIndex)
    val tpe = procType.postParamTypes(postIndex)

    defaultValue match
      case DefaultValue.Lit(const) => Literal(const)(tpe, span)
      case DefaultValue.Ref(sym) =>
        if sym.tpe.isValueType then
          Ident(sym)(span)
        else
          Apply(Ident(sym)(span), Nil, Nil)(span)

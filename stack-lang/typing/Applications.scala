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
          Inference.freshIsolate:
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
                  if elementType.isNamedArgType then
                    transformVarargsWithNamed(args, proc, applySpan)
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
            Some:
              if invokeType.hasVararg then
                transformVarargs(positional, invokeType.paramTypes, applySpan)
              else
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
            Autos.resolve(fun1, argsTyped, applySpan).adapt

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
      Autos.resolve(fun, argTyped :: Nil, call.span).adapt

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


      Autos.resolve(fun, preArgs2 ++ postArgs2, call.span).adapt

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

    var lastFlexArg: Word =
      val tapply = Ident(defn.List_empty)(flexSpan).appliedToTypes(elementType)
      Apply(tapply, args = Nil, autos = Nil)(flexSpan)

    def checkSplice(arg: Ast.Word): Unit =
      val argTyped = transformArg(arg, paramTypeFlex)

      if !argTyped.tpe.isError then
        lastFlexArg = lastFlexArg.select("++").appliedTo(argTyped)

    for arg <- argsFlex do
      arg match
        case Ast.Expr(Ast.Ident("..") :: rest) =>
          if rest.size != 1 then
            Reporter.error(".. should be followed by exact one word, found = " + rest.size, arg.pos)

          else
            checkSplice(rest.head)

        case Ast.Apply(Ast.Ident(".."), callArgs) =>
          callArgs match
            case List(word: Ast.Word) =>
              checkSplice(word)

            case _ =>
              Reporter.error(".. should be followed by exact one word, found = " + callArgs.size, arg.pos)

        case Ast.PrefixOperatorCall(Ast.Ident(".."), arg) =>
          checkSplice(arg)

        case Ast.Ident("..") =>
          Reporter.error(".. should be followed by exact one word, found = 0", arg.pos)

        case _ =>
          val argTyped = transformArg(arg, elementType)
          if !argTyped.tpe.isError then
            lastFlexArg = lastFlexArg.select("+").appliedTo(argTyped)
      end match

    argsFixTyped :+ lastFlexArg

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
    val fun = Ident(defn.compile_namedArg)(span).appliedToTypes(arg.tpe)
    Apply(fun, List(StringLit(name)(span), arg), Nil)(span)

  /** Handle a call to a vararg function whose vararg element type is NamedArg[T].
    *
    * Positional args before the vararg boundary fill fixed parameters as usual.
    * In the vararg portion, positional args are typed normally and named args
    * are wrapped with `namedArg("key", value)` so the backend can emit them
    * as keyword arguments.
    */
  private def transformVarargsWithNamed(callArgs: List[Ast.CallArg], proc: ProcType, callSpan: Span)
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

    var lastFlexArg: Word =
      val tapply = Ident(defn.List_empty)(flexSpan).appliedToTypes(elementType)
      Apply(tapply, args = Nil, autos = Nil)(flexSpan)

    for callArg <- flexCallArgs do
      callArg match
        case word: Ast.Word =>
          val argTyped = transformArg(word, elementType)
          if !argTyped.tpe.isError then
            lastFlexArg = lastFlexArg.select("+").appliedTo(argTyped)

        case namedArg: Ast.NamedArg =>
          val argTyped = transformArg(namedArg.arg, elementType)
          if !argTyped.tpe.isError then
            val wrapped = wrapNamedArg(namedArg.name, argTyped)
            lastFlexArg = lastFlexArg.select("+").appliedTo(wrapped)

    Some(fixedTyped :+ lastFlexArg)

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

package typing

import ast.{ Trees => Ast }
import ast.Positions.*

import sast.*
import sast.Trees.*
import sast.Types.*

import reporting.Reporter

import Inference.*

trait Applications:
  this: Namer =>

  /** Handles explicit postfix call syntax f(arg1, arg2, ...) */
  def transformCall(apply: Ast.Apply)
      (using defn: Definitions, sc: Scope, rp: Reporter, so: Source, tt: TargetType, tvars: TypeVars)
  : Word =

    var fun =
      given TargetType = TargetType.Call
      transform(apply.fun)

    val funType = fun.tpe

    if funType.isInvokableType then
      if funType.isPolyType then
        fun = TreeOps.instantiatePoly(funType.asProcType, fun)

      val invokeType = fun.tpe.asInvokableType
      val paramSize = invokeType.paramTypes.size

      // Conditionally apply context instantiation
      Inference.conditionalInstantiate(invokeType.resultType, tt)

      val preArgTypes = invokeType.preParamTypes
      if preArgTypes.size != 0 then
        Reporter.error(
          s"The postfix call syntax cannot be used, as the function takes prefix arguments",
          fun.pos)
        errorWord(apply.span)

      else if apply.args.size < invokeType.minimumArgs ||
              !invokeType.hasVararg && apply.args.size > paramSize then
        val mod = if invokeType.hasVararg then "at least " else ""
        val size = if invokeType.hasVararg then invokeType.minimumArgs else paramSize
        Reporter.error(
          s"The function expects $mod$size argument(s), found = ${apply.args.size}",
          apply.pos)
        errorWord(apply.span)

      else
        val numProvided = apply.args.size
        val argsTyped =
          if invokeType.hasVararg then
            transformVarargs(apply.args, invokeType.paramTypes, apply.span)
          else
            val providedArgs = transformArgs(apply.args, invokeType.paramTypes.take(numProvided))
            val defaultArgs = invokeType match
              case proc: ProcType => Defaults.synthesizePostDefaults(proc, numProvided, apply.span)
              case _ => Nil
            providedArgs ++ defaultArgs

        // Resolve auto parameters from local scope
        if invokeType.autoTypes.isEmpty then
          TreeOps.smartApply(fun, argsTyped, autos = Nil)(apply.span).adapt

        else
          Autos.resolve(fun, argsTyped, apply.span).adapt

    else
      if !fun.tpe.isError then
        Reporter.error(s"Not a function: " + fun.tpe.show, fun.pos)
      errorWord(apply.span)

  /** Check a dotless call such as `str1 + str2` */
  def transformDotlessCall(call: Ast.InfixOperatorCall)
      (using defn: Definitions, sc: Scope, rp: Reporter, so: Source, tt: TargetType, tvars: TypeVars)
  : Word =

    val Ast.InfixOperatorCall(obj, meth, arg) = call

    val objSpan = obj.span

    // Delegate member resolution to transformSelect
    val selectAst = Ast.Select(obj, meth.name)(objSpan | meth.span)
    var fun = transformSelect(selectAst)(using defn, sc, rp, so, TargetType.Call, tvars)

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
    (using defn: Definitions, sc: Scope, rp: Reporter, so: Source, tt: TargetType, tvars: TypeVars)
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
      (using defn: Definitions, sc: Scope, rp: Reporter, so: Source, tvars: TypeVars)
  : List[Word] =

    for (arg, paramType) <- args.zip(params)
    yield transformArg(arg, paramType)

  def transformArg
      (arg: Ast.Word, paramType: Type)
      (using defn: Definitions, sc: Scope, rp: Reporter, so: Source, tvars: TypeVars)
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
      (using defn: Definitions, sc: Scope, rp: Reporter, so: Source, tvars: TypeVars)
  : List[Word] =

    val paramTypesFix :+ paramTypeFlex = paramTypes: @unchecked
    val (argsFix, argsFlex) = args.splitAt(paramTypesFix.size)

    val argsFixTyped = transformArgs(argsFix, paramTypesFix)

    val elementType = paramTypeFlex.stripVarargs

    var lastFlexArg: Word =
      val tapply = Ident(defn.List_empty)(span).appliedToTypes(elementType)
      Apply(tapply, args = Nil, autos = Nil)(span)

    def checkSplice(splice: Ast.Word, args: List[Ast.Word]): Unit =
      if args.size != 1 then
        Reporter.error(".. should be followed by exact one word, found = " + args.size, splice.pos)

      else
        val argTyped = transformArg(args.head, paramTypeFlex)

        if !argTyped.tpe.isError then
          lastFlexArg = lastFlexArg.select("++").appliedTo(argTyped)

    for arg <- argsFlex do
      arg match
        case Ast.Expr(Ast.Ident("..") :: rest) =>
          checkSplice(arg, rest)

        case Ast.Apply(Ast.Ident(".."), args) =>
          checkSplice(arg, args)

        case _ =>
          val argTyped = transformArg(arg, elementType)
          if !argTyped.tpe.isError then
            lastFlexArg = lastFlexArg.select("+").appliedTo(argTyped)
      end match

    argsFixTyped :+ lastFlexArg

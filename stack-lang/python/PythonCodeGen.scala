package python

import sast.*
import sast.Trees.*
import sast.Symbols.Symbol
import sast.Types
import sast.Types.{Type, NamedInfo}

import python.Trees as P

import common.UniqueName
import common.WorkList

import reporting.Reporter

import scala.collection.mutable

/** Code generator that translates Jo SAST to Python AST
  *
  */
class PythonCodeGen(runtime: PythonRuntime, rewire: Map[Symbol, Symbol])(using defn: Definitions):

  //----------------------------------------------------------------------------
  // Name management
  //----------------------------------------------------------------------------

  private val reservedNames = new UniqueName(separator = "")

  val pythonKeywords = PythonRuntime.keywords

  // Built-in names that are only problematic as local variable names
  // (they shadow the built-ins), but are fine as attribute/member names.
  val pythonBuiltins = List(
    "abs", "aiter", "all", "anext", "any", "ascii",
    "bin", "bool", "breakpoint", "bytearray", "bytes",
    "callable", "chr", "classmethod", "compile", "complex",
    "delattr", "dict", "dir", "divmod",
    "enumerate", "eval", "exec",
    "filter", "float", "format", "frozenset",
    "getattr", "globals",
    "hasattr", "hash", "help", "hex",
    "id", "input", "int", "isinstance", "issubclass", "iter",
    "len", "list", "locals",
    "map", "max", "memoryview", "min",
    "next",
    "object", "oct", "open", "ord",
    "pow", "print", "property",
    "range", "repr", "reversed", "round",
    "set", "setattr", "slice", "sorted", "staticmethod", "str", "sum", "super",
    "tuple", "type",
    "vars",
    "zip",
    "__import__"
  )

  val keywords = pythonKeywords ++ pythonBuiltins

  // Make keywords and built-ins unavailable as local variable names
  for word <- keywords do reservedNames.freshName(word)

  // Make runtime symbols unavailable
  for name <- runtime.runtimeNames do reservedNames.freshName(name)

  // Scope used exclusively for member name uniqueness — only avoids true keywords,
  // not built-ins, since built-in names are valid Python attribute names.
  private val memberReservedNames = new UniqueName(separator = "")
  for word <- pythonKeywords do memberReservedNames.freshName(word)

  private case class Context(currentFunction: Symbol, loopTargets: LoopContext)

  private val symbol2UniqueName: mutable.Map[Symbol, String] = mutable.Map.empty

  val globalScope = reservedNames.newScope(separator = "")

  private def abortBadPythonName(word: Word, api: String)(using ctx: Context): Nothing =
    Reporter.abort(s"$api requires a string literal name", word.pos(using ctx.currentFunction.source))

  private def abortBadPythonIdentifier(word: Word, api: String)(using ctx: Context): Nothing =
    Reporter.abort(
      s"$api requires a string literal name that is a valid Python identifier",
      word.pos(using ctx.currentFunction.source)
    )

  private def abortBadFfiCallArgs(word: Word)(using ctx: Context): Nothing =
    Reporter.abort(
      "Dynamic call arguments must be written directly at the call site; use positional args, named args (key = value), or ..xs splice",
      word.pos(using ctx.currentFunction.source)
    )

  private def pythonInteropMemberName(sym: Symbol): String =
    runtime.pyTargetName(sym).getOrElse(pythonMemberName(sym))

  def pythonMemberName(sym: Symbol): String =
    assert(sym.isOneOf(Flags.Method | Flags.Field), "Not a method, sym = " + sym)

    symbol2UniqueName.get(sym) match
      case Some(name) => name

      case _ =>
        val rawName = PythonCodeGen.encodeSymbolic(sym.name)
        val scope = memberReservedNames.newScope("_")
        val name = scope.freshName(rawName)
        symbol2UniqueName(sym) = name
        name

  def pythonName(sym: Symbol)(using scope: UniqueName): String =
    assert(!sym.isOneOf(Flags.Method | Flags.Field), "Member name should call pythonMemberName, sym = " + sym)

    symbol2UniqueName.get(sym) match
      case Some(name) => name

      case None =>
        rewire.get(sym) match
          case Some(target) => pythonName(target)

          case None =>
            val uniqueName =
              if sym.isLocal then
                scope.freshName(PythonCodeGen.encodeSymbolic(sym.name))

              else
                val rawName = sym.fullName.replace(".", "_")
                val baseName = globalScope.freshName(PythonCodeGen.encodeSymbolic(rawName))
                baseName

            symbol2UniqueName(sym) = uniqueName

            // Add function or class to work list
            if (sym.isFunction && !sym.owner.isOneOf(Flags.Class | Flags.Interface)) || sym.isClass then
              workList.add(sym)

            uniqueName

  //----------------------------------------------------------------------------
  // Compilation
  //----------------------------------------------------------------------------

  val workList = new WorkList[Symbol]
  private val localExitExceptionNames = mutable.Map.empty[Symbol, String]
  private final case class LoopFrame(
    breakLabel: Option[Symbol],
    continueLabel: Option[Symbol]
  )

  private final case class LoopContext(active: List[LoopFrame]):
    def enterLoop(frame: LoopFrame): LoopContext =
      copy(active = frame :: active)

  private enum LocalLoopJump:
    case Break
    case Continue

  private def localLoopJump(label: Symbol)(using ctx: Context): Option[LocalLoopJump] =
    ctx.loopTargets.active.headOption.flatMap: frame =>
      if frame.continueLabel.contains(label) then Some(LocalLoopJump.Continue)
      else if frame.breakLabel.contains(label) then Some(LocalLoopJump.Break)
      else None

  private def isLoopControlLabel(label: Symbol)(using ctx: Context): Boolean =
    ctx.loopTargets.active.exists: frame =>
      frame.breakLabel.contains(label) || frame.continueLabel.contains(label)

  private def localExitExceptionName(label: Symbol): String =
    localExitExceptionNames.getOrElseUpdate(
      label,
      globalScope.freshName("__jo_local_exit_" + PythonCodeGen.encodeSymbolic(label.name))
    )

  /** Compile a complete set of file units to a Python program */
  def compile(units: List[FileUnit]): P.Program =
    workList.add(runtime.start)

    val funDefMap = mutable.Map.empty[Symbol, FunDef]
    val classDefMap = mutable.Map.empty[Symbol, ClassDef]

    for
      unit <- units
      defn <- unit
    do
      defn match
        case fdef: FunDef =>
          funDefMap(fdef.symbol) = fdef

        case cdef: ClassDef =>
          classDefMap(cdef.symbol) = cdef

        case _ =>

    val defs = mutable.ArrayBuffer.empty[P.Def]

    given UniqueName = globalScope

    // Compile all reachable definitions
    workList.run: sym =>
      val defn =
        if sym.isFunction then
          compileFunction(funDefMap(sym))
        else if sym.isClass then
          compileClass(classDefMap(sym))
        else
          throw new Exception("Symbol is neither a function nor class: " + sym)

      defs += defn

    for name <- localExitExceptionNames.values.toList.sorted do
      defs += P.ClassDef(name, Nil, Nil, base = Some("Exception"))

    // Build the program: combine all initialization with the main call
    val globalInit =
      runtime.paramIds.toList.map: (fullName, globalName) =>
        // _param_jo_IO_stdout = "jo.IO.stdout"
        P.Assign(globalName, P.StringLit(fullName))

    val singletonInits = runtime.singletonIds.toList.map: (classSym, singletonVar) =>
      // _singleton_jo_Predef_Unit = new jo_Predef_Unit
      P.Assign(singletonVar, P.New(pythonName(classSym), Nil))

    // Create the main call statement
    val startCall = P.ExprStat(P.Call(None, pythonName(runtime.start), Nil))

    // Combine everything: global init + singleton init + start call
    val mainBlock = P.Block(globalInit ++ singletonInits :+ startCall)

    P.Program(
      defs = defs.toList,
      mainCall = mainBlock
    )

  /** Compile a function definition */
  private def compileFunction(fdef: FunDef): P.FunDef = try
    val sym = fdef.symbol

    // Regular function - create new scope for local variables
    given UniqueName = reservedNames.newScope(separator = "")
    given Context = Context(sym, LoopContext(Nil))

    val name =
      if sym.is(Flags.Constructor) then
        // Python constructor is always named "__init__"
        "__init__"

      else if sym.is(Flags.Method) then
        pythonMemberName(sym)

      else
        pythonName(sym)

    val baseParams = fdef.params.map(pythonName) ++ fdef.autos.map(pythonName)

    // For methods, add 'self' as the first parameter
    val params = if sym.is(Flags.Method) then "self" :: baseParams else baseParams

    // For __init__, don't add return statement (or return None)
    val body = if name == "__init__" then
      val (stats, expr) = compileExpr(fdef.body, enforcePurity = false)
      // __init__ should not return a value, so discard the final expression
      P.Block(stats :+ P.ExprStat(expr))
    else
      compileFunctionBody(fdef.body)

    P.FunDef(name, params, body)
  catch case ex: Exception =>
    // println("Error compiling function:" + fdef.show)
    throw ex

  /** Compile a class definition */
  private def compileClass(cdef: ClassDef)(using scope: UniqueName): P.ClassDef =
    val classSym = cdef.symbol
    val pythonClassName = pythonName(classSym)

    symbol2UniqueName(cdef.self) = "self"

    // Get all fields from the class definition
    val fieldNames = cdef.vals.map(_.symbol).map(pythonMemberName)

    // Compile methods - each method gets compiled with its own scope
    // Include __init__ in the methods list (it will be renamed in compileFunction)
    val methods = cdef.funs.map(compileFunction)

    P.ClassDef(
      name = pythonClassName,
      fields = fieldNames,
      methods = methods
    )

  /** Compile function body (adds Return statement unless it ends with Raise) */
  private def compileFunctionBody(word: Word)(using scope: UniqueName, ctx: Context): P.Block =
    val (stats, expr) = compileExpr(word, enforcePurity = false)
    // If the last statement is terminal, don't add Return.
    stats.lastOption match
      case Some(_: P.Return) =>
        assert(expr == P.NoneLit, s"Expected NoneLit after Return, got: $expr")
        P.Block(stats)

      case Some(_: P.Raise) =>
        // Invariant: if statements end with Raise, expr must be NoneLit (never reached)
        assert(expr == P.NoneLit, s"Expected NoneLit after Raise, got: $expr")
        P.Block(stats)

      case _ => P.Block(stats :+ P.Return(expr))

  /** Helper for fresh temporary variable names */
  private def freshTemp()(using scope: UniqueName): String =
    scope.freshName("_temp")

  /** Compile in statement position (no value needed) */
  private def compileStat(word: Word)(using scope: UniqueName, ctx: Context): P.Stat =
    word match
      case Block(words) =>
        // In statement position, all words become statements
        P.Block(words.map(compileStat))

      case Assign(Ident(sym), rhs, _) =>
        // Assignment: RHS is in EXPRESSION position (need the value)
        val (rhsStats, rhsExpr) = compileExpr(rhs, enforcePurity = false)
        if rhsStats.isEmpty then
          P.Assign(pythonName(sym), rhsExpr)
        else
          P.Block(rhsStats :+ P.Assign(pythonName(sym), rhsExpr))

      case FieldAssign(lhs @ Select(qual, _), rhs) =>
        val memberName = lhs.tpe match
          case Types.MemberRef(_, sym) => pythonMemberName(sym)
          case _ => throw new Exception("Unexpected lhs of assign: " + lhs.show)

        val (rhsStats, rhsExpr) = compileExpr(rhs, enforcePurity = false)
        val (qualStats, qualExpr) = compileExpr(qual, enforcePurity = false)

        qual match
          case Ident(sym) if sym.owner.isType && sym == sym.owner.classInfo.self =>
            // Field assignment on self
            if rhsStats.isEmpty && qualStats.isEmpty then
              P.AttrAssign(qualExpr, memberName, rhsExpr)
            else
              P.Block((qualStats ++ rhsStats) :+ P.AttrAssign(qualExpr, memberName, rhsExpr))

          case _ =>
            // Field assignment on other object
            if rhsStats.isEmpty && qualStats.isEmpty then
              P.AttrAssign(qualExpr, memberName, rhsExpr)
            else
              P.Block((qualStats ++ rhsStats) :+ P.AttrAssign(qualExpr, memberName, rhsExpr))

      case While(cond, body) =>
        // While loop: condition in expression position, body in statement position
        val (condStats, condExpr) = compileExpr(cond, enforcePurity = false)
        val (continueLabelOpt, loopBody) =
          body match
            case Labeled(continueLabel, continueType, body2) if continueType.isVoidType =>
              (Some(continueLabel), body2)
            case _ =>
              (None, body)
        val bodyStat =
          given Context = ctx.copy(loopTargets = ctx.loopTargets.enterLoop(LoopFrame(None, continueLabelOpt)))
          compileStat(loopBody)
        if condStats.isEmpty then
          P.While(condExpr, bodyStat)
        else
          // Need to put condition inside while body
          val break = P.IfStat(P.UnaryOp("not", condExpr), P.Break, P.Block(Nil))
          val body = P.Block(condStats :+ break :+ bodyStat)
          P.While(P.BoolLit(true), body)

      case Labeled(label, resultType, While(cond, rawBody)) =>
        assert(resultType.isVoidType, s"Python backend only supports VoidType labeled blocks for now, found ${resultType.show}")
        val (continueLabelOpt, loopBody) =
          rawBody match
            case Labeled(continueLabel, continueType, body2) if continueType.isVoidType =>
              (Some(continueLabel), body2)
            case _ =>
              (None, rawBody)
        val bodyStat =
          given Context = ctx.copy(loopTargets = ctx.loopTargets.enterLoop(LoopFrame(Some(label), continueLabelOpt)))
          compileStat(loopBody)
        val (condStats, condExpr) = compileExpr(cond, enforcePurity = false)
        if condStats.isEmpty then
          P.While(condExpr, bodyStat)
        else
          val break = P.IfStat(P.UnaryOp("not", condExpr), P.Break, P.Block(Nil))
          P.While(P.BoolLit(true), P.Block(condStats :+ break :+ bodyStat))

      case Labeled(label, resultType, body) =>
        assert(resultType.isVoidType, s"Python backend only supports VoidType labeled blocks for now, found ${resultType.show}")
        val excName = localExitExceptionName(label)
        val labeledBody =
          compileStat(body)
        P.TryExcept(
          body = labeledBody,
          exceptionType = P.Ident(excName),
          binder = None,
          handler = P.Block(Nil)
        )

      case Return(label, value) =>
        if label.is(Flags.Fun) then
          val (stats, expr) = compileExpr(value, enforcePurity = false)
          P.Block(stats :+ P.Return(expr))
        else
          assert(value.tpe.isVoidType && value.isEmpty,
            s"Python backend expects empty VoidType payload for local labeled return, found ${value.show}: ${value.tpe.show}")
          localLoopJump(label) match
            case Some(LocalLoopJump.Break) =>
              P.Break
            case Some(LocalLoopJump.Continue) =>
              P.Continue
            case None =>
              assert(
                !isLoopControlLabel(label),
                s"Python backend would emit raise for active loop-control label `${label.name}`; expected native break/continue")
              val excName = localExitExceptionName(label)
              P.Raise(P.Call(None, excName, Nil))

      case If(cond, thenp, elsep) =>
        // In statement position, use IfStat directly
        val (condStats, condExpr) = compileExpr(cond, enforcePurity = false)
        val thenStat = compileStat(thenp)  // branches in statement position
        val elseStat = compileStat(elsep)

        if condStats.isEmpty then
          P.IfStat(condExpr, thenStat, elseStat)
        else
          P.Block(condStats :+ P.IfStat(condExpr, thenStat, elseStat))

      case Encoded(repr) =>
        compileStat(repr)

      case Apply(fun, args, autos) =>
        // Function call as statement (for side effects)
        val (stats, expr) = compileCall(fun, args ++ autos, enforcePurity = false)
        P.Block(stats :+ P.ExprStat(expr))

      case _ =>
        // For other expression-like constructs, compile as expression and discard value
        val (stats, expr) = compileExpr(word, enforcePurity = false)
        P.Block(stats :+ P.ExprStat(expr))

  /** Compile in expression position (value needed, lift statements)
    *
    * @param enforcePurity If true, ensures the result expression is pure by wrapping
    *                      impure expressions in temporary variables. This preserves
    *                      evaluation order when statements from later arguments need
    *                      to be lifted.
    */
  private def compileExpr(word: Word, enforcePurity: Boolean)(using scope: UniqueName, ctx: Context): (List[P.Stat], P.Expr) =
    word match
      case Block(words) =>
        // In expression position: all but last are STATEMENTS, last is EXPRESSION
        (words: @unchecked) match
          case Nil =>
            throw new Exception("Unexpected empty block for expression")

          case init :+ last =>
            val stats = init.map(compileStat)
            val (finalStats, finalExpr) = compileExpr(last, enforcePurity)
            (stats ++ finalStats, finalExpr)

      case If(cond, thenp, elsep) =>
        // In expression position, branches are also expressions
        // Warning: Only lift from CONDITION, NOT from branches!

        // 1. First, recursively process both branches to see if they have statements
        val (thenStats, thenExpr) = compileExpr(thenp, enforcePurity = false)
        val (elseStats, elseExpr) = compileExpr(elsep, enforcePurity = false)

        // 2. Determine if we need an if-statement (branches have statements) or ternary (simple)
        val needsIfStatement = thenStats.nonEmpty || elseStats.nonEmpty

        // 3. Compile condition with purity enforcement if the result should be pure ternary expression
        val (condStats, condExpr) = compileExpr(cond, enforcePurity && !needsIfStatement)

        if !needsIfStatement then
          // Both branches are simple (no statements)
          // → Use ternary if-expression!
          val ternary = P.IfExpr(condExpr, thenExpr, elseExpr)
          val result = (condStats, ternary)
          // Ternary might have side effects in branches - wrap if purity needed
          if enforcePurity then
            val tempName = freshTemp()
            (condStats :+ P.Assign(tempName, ternary), P.Ident(tempName))

          else
            result

        else
          // At least one branch has statements
          // → Must use if-statement with temp variable
          // Branch statements stay INSIDE the if-statement blocks
          val tempName = freshTemp()
          // If a branch ends with a terminal statement, don't add assignment.
          val thenBlock = thenStats.lastOption match
            case Some(P.Break) =>
              assert(thenExpr == P.NoneLit, s"Expected NoneLit after terminal then branch, got: $thenExpr")
              P.Block(thenStats)
            case Some(P.Continue) =>
              assert(thenExpr == P.NoneLit, s"Expected NoneLit after terminal then branch, got: $thenExpr")
              P.Block(thenStats)
            case Some(_: P.Return) =>
              assert(thenExpr == P.NoneLit, s"Expected NoneLit after terminal then branch, got: $thenExpr")
              P.Block(thenStats)
            case Some(_: P.Raise) =>
              // Invariant: if statements end with Raise, expr must be NoneLit (never reached)
              assert(thenExpr == P.NoneLit, s"Expected NoneLit after Raise in then branch, got: $thenExpr")
              P.Block(thenStats)

            case _ => P.Block(thenStats :+ P.Assign(tempName, thenExpr))

          val elseBlock = elseStats.lastOption match
            case Some(P.Break) =>
              assert(elseExpr == P.NoneLit, s"Expected NoneLit after terminal else branch, got: $elseExpr")
              P.Block(elseStats)
            case Some(P.Continue) =>
              assert(elseExpr == P.NoneLit, s"Expected NoneLit after terminal else branch, got: $elseExpr")
              P.Block(elseStats)
            case Some(_: P.Return) =>
              assert(elseExpr == P.NoneLit, s"Expected NoneLit after terminal else branch, got: $elseExpr")
              P.Block(elseStats)
            case Some(_: P.Raise) =>
              // Invariant: if statements end with Raise, expr must be NoneLit (never reached)
              assert(elseExpr == P.NoneLit, s"Expected NoneLit after Raise in else branch, got: $elseExpr")
              P.Block(elseStats)

            case _ => P.Block(elseStats :+ P.Assign(tempName, elseExpr))

          val ifStmt = P.IfStat(condExpr, thenBlock, elseBlock)
          // Result is already a temp variable (pure identifier)
          (condStats :+ ifStmt, P.Ident(tempName))

      case Literal(c) =>
        // Literals are always pure
        (Nil, compileLiteral(c))

      case Ident(sym) =>
        assert(!sym.is(Flags.Context), "Unexpected context parameter")
        if sym == runtime.py_none then
          (Nil, P.NoneLit)

        else if enforcePurity && sym.isMutable then
          // Mutable variable reads are impure - wrap in temp
          val tempName = freshTemp()
          (List(P.Assign(tempName, P.Ident(pythonName(sym)))), P.Ident(tempName))

        else
          (Nil, P.Ident(pythonName(sym)))

      case Select(qual, name) =>
        word.tpe match
          case Types.MemberRef(_, sym) =>
            val (qualStats, qualExpr) = compileExpr(qual, enforcePurity)
            val memberName = pythonInteropMemberName(sym)
            (qualStats, P.Select(qualExpr, memberName))

          case _ => throw new Exception("Unexpected select: " + word.show)

      case encoded @ Encoded(repr) =>
        if encoded.isValueDrop then
          throw new Exception("Unexpected value drop in expression position: " + encoded.show)

        else if encoded.tpe.isLambdaType && repr.tpe.isClassType then
          // Wrap class instance as lambda
          val (objStats, objExpr) = compileExpr(repr, enforcePurity = false)  // lambda body is OK
          val objName = freshTemp()
          val bindObj = P.Assign(objName, objExpr)
          // lambda *args: instance.apply(*args)
          val lambdaBody = P.Call(Some(P.Ident(objName)), "apply", List(P.RawCode("*args")))
          (objStats :+ bindObj, P.Lambda(List("*args"), lambdaBody))

        else
          // Pass through enforcePurity
          compileExpr(repr, enforcePurity)

      case Apply(Select(New(classType), _), args, autos) =>
        // Object construction - always impure, wrap if purity needed
        val classSym = classType.tpe.classSymbol
        val allArgs = args ++ autos
        val (argStats, argExprs) = compileExprList(allArgs, enforcePurity = false)
        val newExpr = P.New(pythonName(classSym), argExprs)
        if enforcePurity then
          val tempName = freshTemp()
          (argStats :+ P.Assign(tempName, newExpr), P.Ident(tempName))
        else
          (argStats, newExpr)

      case ClassTest(arg, cls) =>
        // Type test for union types - this is pure
        val (argStats, argExpr) = compileExpr(arg, enforcePurity)

        val className =
          if cls == defn.String_type then "str"
          else if cls == defn.Float_type then "float"
          else if cls == defn.Int_type || cls == defn.Byte_type || cls == defn.Char_type then "int"
          else if cls == defn.Bool_type then "bool"
          else if cls == defn.Array_class then "list"
          else pythonName(cls)

        // Type test is pure if argxpr is pure
        (argStats, P.InstanceOf(argExpr, className))

      case Apply(fun, args, autos) =>
        // Function/method calls are generally impure, wrap if purity needed
        compileCall(fun, args ++ autos, enforcePurity)

      case Return(label, value) =>
        if label.is(Flags.Fun) then
          val (stats, expr) = compileExpr(value, enforcePurity = false)
          (stats :+ P.Return(expr), P.NoneLit)
        else
          assert(value.tpe.isVoidType && value.isEmpty,
            s"Python backend expects empty VoidType payload for local labeled return, found ${value.show}: ${value.tpe.show}")
          localLoopJump(label) match
            case Some(LocalLoopJump.Break) =>
              (P.Break :: Nil, P.NoneLit)
            case Some(LocalLoopJump.Continue) =>
              (P.Continue :: Nil, P.NoneLit)
            case None =>
              assert(
                !isLoopControlLabel(label),
                s"Python backend would emit raise for active loop-control label `${label.name}`; expected native break/continue")
              val excName = localExitExceptionName(label)
              (P.Raise(P.Call(None, excName, Nil)) :: Nil, P.NoneLit)


      case _: Def | _: With | _: Allow | _: Match |
           _: New | _: IsExpr | _: PatValDef | _: Lambda | _: RecordLit |
           _: Labeled |
           _: Assign | _: FieldAssign | _: While | _: TypeApply =>
        throw new Exception("Unexpected in expression position: " + word)

  /** Compile a literal constant */
  private def compileLiteral(c: Constant): P.Expr =
    c match
      case Constant.Bool(b) => P.BoolLit(b)
      case Constant.String(s) => P.StringLit(s)
      case Constant.Int(n) => P.IntLit(n)
      case Constant.Float(d) => P.FloatLit(d)


  /** Compile a list of expressions, correctly handling evaluation order */
  private def compileExprList(words: List[Word], enforcePurity: Boolean)(using scope: UniqueName, ctx: Context): (List[P.Stat], List[P.Expr]) =
    var stats: List[P.Stat] = Nil
    var exprs: List[P.Expr] = Nil

    for word <- words.reverse do
      val shouldEnforcePurity = enforcePurity || stats.nonEmpty
      val (wordStats, wordExpr) = compileExpr(word, shouldEnforcePurity)
      stats = wordStats ++ stats
      exprs = wordExpr :: exprs

    (stats, exprs)

  /** Compile one call argument with awareness of the declared parameter type.
    *
    * - `Positional[T]`: any namedArg key is discarded; argument emitted positionally.
    * - `Keyword[T]` with no namedArg wrapper: emit as `paramName=value`.
    * - Otherwise: delegate to the standard `compileCallArg`.
    */
  private def compileCallArgWithType(word: Word, paramName: String, paramType: Type, enforcePurity: Boolean)
      (using scope: UniqueName, ctx: Context): (List[P.Stat], P.Expr) =

    if runtime.isPositionalType(paramType) then
      // Strip any namedArg wrapper — Python only accepts positional here
      val valueWord = word match
        case Apply(fun, List(_, value), _) if fun.refers(runtime.compile_namedArg) => value
        case other => other
      compileExpr(valueWord, enforcePurity)

    else if runtime.isKeywordType(paramType) then
      // Ensure argument is emitted as a keyword argument, using the rename if specified
      val kwName = runtime.keywordRename(paramType).getOrElse(paramName)
      word match
        case Apply(fun, List(_, value), _) if fun.refers(runtime.compile_namedArg) =>
          // Strip the namedArg wrapper and emit with the correct keyword name
          val (stats, valueExpr) = compileExpr(value, enforcePurity)
          (stats, P.KwArg(kwName, valueExpr))
        case _ =>
          // Plain value (e.g. synthesized default) — wrap as keyword arg
          val (stats, valueExpr) = compileExpr(word, enforcePurity)
          (stats, P.KwArg(kwName, valueExpr))

    else
      // Regular param: named args on the Jo side are documentation only.
      // Strip any namedArg wrapper and emit positionally so that synthesized
      // defaults after a named arg don't produce invalid Python syntax like
      // `f(a=1, 10, 20)`.
      val valueWord = word match
        case Apply(fun, List(_, value), _) if fun.refers(runtime.compile_namedArg) => value
        case other => other
      compileExpr(valueWord, enforcePurity)

  /** Like compileCallArgList but each argument is compiled with param-type awareness. */
  private def compileCallArgListWithTypes(args: List[Word], params: List[NamedInfo[Type]], enforcePurity: Boolean)
      (using scope: UniqueName, ctx: Context): (List[P.Stat], List[P.Expr]) =
    var stats: List[P.Stat] = Nil
    var exprs: List[P.Expr] = Nil

    for (word, param) <- args.zip(params).reverse do
      val shouldEnforcePurity = enforcePurity || stats.nonEmpty
      val (wordStats, wordExpr) = compileCallArgWithType(word, param.name, param.info, shouldEnforcePurity)
      stats = wordStats ++ stats
      exprs = wordExpr :: exprs

    (stats, exprs)

  /** Compile two arguments in order with conditional purity enforcement
    *
    * Optimization: LHS only needs purity if RHS has statements to lift over it.
    * This avoids unnecessary temp variables when both operands are already pure.
    *
    * @param enforcePurity If true, ensures both LHS and RHS are pure expressions
    */
  private def compileTwoArgs(lhs: Word, rhs: Word, enforcePurity: Boolean)(using scope: UniqueName, ctx: Context): (List[P.Stat], P.Expr, P.Expr) =
    val (statsRhs, exprRhs) = compileExpr(rhs, enforcePurity)
    val (statsLhs, exprLhs) = compileExpr(lhs, enforcePurity || statsRhs.nonEmpty)
    (statsLhs ++ statsRhs, exprLhs, exprRhs)

  /** Compile a vararg element for use as a Python call argument.
    *
    * Detects namedArg markers and emits the appropriate
    * Python-level argument node (KwArg or plain expression).
    */
  private def compileCallArg(word: Word, enforcePurity: Boolean)(using scope: UniqueName, ctx: Context): (List[P.Stat], P.Expr) =
    word match
      case Apply(fun, List(name, value), _) if fun.refers(runtime.compile_namedArg) =>
        name match
          case Literal(Constant.String(key)) =>
            val (stats, valueExpr) = compileExpr(value, enforcePurity)
            (stats, P.KwArg(key, valueExpr))
          case _ =>
            abortBadPythonName(name, "namedArg")

      case _ =>
        compileExpr(word, enforcePurity)

  /** Compile a packed vararg list for a `@py.interop` abstract method call.
    *
    * Handles both `+` (single item) and `++` (Jo splice) chains:
    * - `+` item: compiled via `compileCallArg` (handles namedArg → k=v, plain → positional)
    * - `++` item: emitted as `P.Starred(xs)` i.e. `*xs`
    */
  private def compileVarargItems(packed: Word, enforcePurity: Boolean)(using scope: UniqueName, ctx: Context): (List[P.Stat], List[P.Expr]) =
    packed match
      case Apply(Ident(sym), Nil, _) if sym == defn.List_empty =>
        (Nil, Nil)

      case Apply(Select(prev, "+"), List(arg), _) =>
        val (prevStats, prevExprs) = compileVarargItems(prev, enforcePurity)
        val (argStats, argExpr) = compileCallArg(arg, enforcePurity = false)
        (prevStats ++ argStats, prevExprs :+ argExpr)

      case Apply(Select(prev, "++"), List(xs), _) =>
        val (prevStats, prevExprs) = compileVarargItems(prev, enforcePurity)
        val (xsStats, xsExpr) = compileExpr(xs, enforcePurity = false)
        // Convert Jo List to Python list so Python *-unpacking works
        val pyList = P.Call(None, pythonName(runtime.py_list), List(xsExpr))
        (prevStats ++ xsStats, prevExprs :+ P.Starred(pyList))

      case _ =>
        abortBadFfiCallArgs(packed)

  /** Compile a function/method call */
  private def compileCall(fun: Word, args: List[Word], enforcePurity: Boolean)(using scope: UniqueName, ctx: Context): (List[P.Stat], P.Expr) =
    fun match
      case Ident(sym) if sym.isFunction =>
        if sym.is(Flags.Object) then
          // direct singleton object access
          val funType = sym.tpe.asProcType
          val classSym = funType.resultType.classSymbol

          // Mark class reachable
          pythonName(classSym)

          // Get or create the global singleton variable name
          val singletonVar = runtime.getOrCreateSingletonId(classSym)
          (Nil, P.Ident(singletonVar))

        else if sym == runtime.paramKey then
          val paramSym = args.head match
            case Encoded(Ident(paramSym)) => paramSym
            case word => throw new Exception("Unsupported argument to paramKey: " + word)

          val keyId = runtime.getOrCreateParamId(paramSym)
          (Nil, P.Ident(keyId))

        else if sym == defn.jo_pass then
          (Nil, P.NoneLit)

        else if sym == runtime.py_none then
          (Nil, P.NoneLit)

        // --- py.Dynamic dynamic operations ---

        else if sym == runtime.py_dynamic then
          // py.dynamic(x) → x  (no-op at runtime; all Python values are already Python objects)
          val receiver :: Nil = args: @unchecked
          compileExpr(receiver, enforcePurity)

        else if sym == runtime.py_module then
          // importModule(name) → importlib.import_module(name)
          val pkg :: Nil = args: @unchecked
          val (pkgStats, pkgExpr) = compileExpr(pkg, enforcePurity = false)
          val importlib = P.Call(None, "__import__", List(P.StringLit("importlib")))
          val call = P.Call(Some(importlib), "import_module", List(pkgExpr))
          if enforcePurity then
            val tempName = freshTemp()
            (pkgStats :+ P.Assign(tempName, call), P.Ident(tempName))
          else
            (pkgStats, call)

        else if sym == runtime.py_call then
          // py.call(f, args...) → f(*args, **kwargs)
          val target :: packedArgs :: Nil = args: @unchecked
          val (targetStats, targetExpr) = compileExpr(target, enforcePurity = false)
          val (argStats, argExprs) = compileVarargItems(packedArgs, enforcePurity = false)
          val callExpr   = P.LambdaCall(targetExpr, argExprs)
          if enforcePurity then
            val tempName = freshTemp()
            (targetStats ++ argStats :+ P.Assign(tempName, callExpr), P.Ident(tempName))
          else
            (targetStats ++ argStats, callExpr)

        // --- Python runtime intrinsics ---

        else if sym == runtime.py_abort then
          // abort(msg: String): Bottom  →  raise Exception(msg)
          val msg :: Nil = args: @unchecked
          val (msgStats, msgExpr) = compileExpr(msg, enforcePurity = false)
          (msgStats :+ P.Raise(P.Call(None, "Exception", List(msgExpr))), P.NoneLit)

        // --- py.* intrinsics ---

        else if sym == runtime.py_try then
          // py.try(action): Result[T, Error]
          // Intrinsified: wrap the call site in a Python try/except block.
          // Error is an interface — the Python exception object IS the Error value.
          val action :: Nil = args: @unchecked
          val (actionStats, actionExpr) = compileExpr(action, enforcePurity = false)
          val tempResult = freshTemp()
          val tempExc    = freshTemp()
          val okExpr     = P.New(pythonName(runtime.jo_Ok), List(actionExpr))
          val errExpr    = P.New(pythonName(runtime.jo_Err), List(P.Ident(tempExc)))
          val tryBody    = P.Block(actionStats :+ P.Assign(tempResult, okExpr))
          val tryStat    = P.TryExcept(tryBody, P.Ident("Exception"), Some(tempExc),
            P.Assign(tempResult, errExpr))
          (List(P.Assign(tempResult, P.NoneLit), tryStat), P.Ident(tempResult))

        else if sym == runtime.py_isNone then
          // py.isNone(obj)  →  obj is None
          val receiver :: Nil = args: @unchecked
          val (stats, recvExpr) = compileExpr(receiver, enforcePurity = false)
          (stats, P.BinOp(recvExpr, "is", P.NoneLit))

        else if sym == runtime.py_isIdentical then
          // py.isIdentical(obj, other)  →  obj is other
          val receiver :: other :: Nil = args: @unchecked
          val (stats, List(recvExpr, otherExpr)) = compileExprList(List(receiver, other), enforcePurity = false): @unchecked
          val expr = P.BinOp(recvExpr, "is", otherExpr)
          if enforcePurity then
            val tempName = freshTemp()
            (stats :+ P.Assign(tempName, expr), P.Ident(tempName))
          else
            (stats, expr)

        else if sym == runtime.py_isInstance then
          // py.isInstance(obj, cls)  →  isinstance(obj, cls)
          val receiver :: cls :: Nil = args: @unchecked
          val (stats, List(recvExpr, clsExpr)) = compileExprList(List(receiver, cls), enforcePurity = false): @unchecked
          val call = P.Call(None, "isinstance", List(recvExpr, clsExpr))
          if enforcePurity then
            val tempName = freshTemp()
            (stats :+ P.Assign(tempName, call), P.Ident(tempName))
          else
            (stats, call)

        else
          val procType = sym.tpe.asProcType
          val (argStats, argExprs) = compileCallArgListWithTypes(args, procType.params ++ procType.autos, enforcePurity = false)
          val call = P.Call(None, pythonName(sym), argExprs)
          if enforcePurity then
            val tempName = freshTemp()
            (argStats :+ P.Assign(tempName, call), P.Ident(tempName))
          else
            (argStats, call)


      case Select(qual, name) if qual.tpe.isSubtype(defn.BoolType) =>
        compileBoolPrimitive(name, qual, args, enforcePurity)

      case Select(qual, name) if qual.tpe.isSubtype(defn.IntType) =>
        compileIntPrimitive(name, qual, args, enforcePurity)

      case Select(qual, name) if qual.tpe.isSubtype(defn.ByteType) =>
        compileBytePrimitive(name, qual, args, enforcePurity)

      case Select(qual, name) if qual.tpe.isSubtype(defn.CharType) =>
        compileCharPrimitive(name, qual, args, enforcePurity)

      case Select(qual, name) if qual.tpe.isSubtype(defn.FloatType) =>
        compileFloatPrimitive(name, qual, args, enforcePurity)

      case Select(qual, name) if qual.tpe.isSubtype(defn.StringType) =>
        compileStringPrimitive(name, qual, args, enforcePurity)

      case f if f.tpe.isLambdaType =>
        // Lambda call
        val (funStats, funExpr) = compileExpr(f, enforcePurity = false)
        val (argStats, argExprs) = compileExprList(args, enforcePurity = false)

        val call = P.LambdaCall(funExpr, argExprs)
        if enforcePurity then
          val tempName = freshTemp()
          (argStats :+ P.Assign(tempName, call), P.Ident(tempName))

        else
          (funStats ++ argStats, call)

      case Select(qual, name) =>
        // Intrinsify py.Dynamic dynamic operations when they appear as member calls
        val methodSym = fun.tpe match
          case Types.MemberRef(_, sym) => sym
          case _ => throw new Exception("Unexpected select: " + fun.show)

        if methodSym == runtime.py_Dynamic_selectDynamic then
          // x.selectDynamic("a") → x.a
          val nameWord :: Nil = args: @unchecked
          val (stats, recvExpr) = compileExpr(qual, enforcePurity = false)
          nameWord match
            case Literal(Constant.String(attrName)) =>
              if PythonRuntime.isValidMemberName(attrName) then
                val expr = P.Select(recvExpr, attrName)
                if enforcePurity then
                  val tempName = freshTemp()
                  (stats :+ P.Assign(tempName, expr), P.Ident(tempName))
                else
                  (stats, expr)
              else
                abortBadPythonIdentifier(nameWord, "selectDynamic")
            case _ =>
              abortBadPythonName(nameWord, "selectDynamic")

        else if methodSym == runtime.py_Dynamic_updateDynamic then
          // x.updateDynamic("a", v) → x.a = v
          val nameWord :: value :: Nil = args: @unchecked
          nameWord match
            case Literal(Constant.String(attrName)) =>
              if PythonRuntime.isValidMemberName(attrName) then
                val (stats, List(recvExpr, valueExpr)) = compileExprList(List(qual, value), enforcePurity = false): @unchecked
                (stats :+ P.AttrAssign(recvExpr, attrName, valueExpr), P.NoneLit)
              else
                abortBadPythonIdentifier(nameWord, "updateDynamic")
            case _ =>
              abortBadPythonName(nameWord, "updateDynamic")

        else if methodSym == runtime.py_Dynamic_callDynamic then
          // x.callDynamic("foo", packed_args) → x.foo(*args, **kwargs)
          val nameWord :: packedArgs :: Nil = args: @unchecked
          val (recvStats, recvExpr) = compileExpr(qual, enforcePurity = false)
          val (argStats, argExprs) = compileVarargItems(packedArgs, enforcePurity = false)
          nameWord match
            case Literal(Constant.String(methodName)) =>
              if PythonRuntime.isValidMemberName(methodName) then
                val callExpr = P.Call(Some(recvExpr), methodName, argExprs)
                if enforcePurity then
                  val tempName = freshTemp()
                  (recvStats ++ argStats :+ P.Assign(tempName, callExpr), P.Ident(tempName))
                else
                  (recvStats ++ argStats, callExpr)
              else
                abortBadPythonIdentifier(nameWord, "callDynamic")
            case _ =>
              abortBadPythonName(nameWord, "callDynamic")

        else if methodSym == runtime.py_Dynamic_getDynamic then
          // x.getDynamic(k) → x[k]
          val key :: Nil = args: @unchecked
          val (stats, List(recvExpr, keyExpr)) = compileExprList(List(qual, key), enforcePurity = false): @unchecked
          val expr = P.Index(recvExpr, keyExpr)
          if enforcePurity then
            val tempName = freshTemp()
            (stats :+ P.Assign(tempName, expr), P.Ident(tempName))
          else
            (stats, expr)

        else if methodSym == runtime.py_Dynamic_setDynamic then
          // x.setDynamic(k, v) → x[k] = v
          val key :: value :: Nil = args: @unchecked
          val (stats, List(recvExpr, keyExpr, valueExpr)) = compileExprList(List(qual, key, value), enforcePurity = false): @unchecked
          (stats :+ P.IndexAssign(recvExpr, keyExpr, valueExpr), P.NoneLit)

        else if methodSym == runtime.py_Dynamic_cast then
          // x.cast[T] → x  (no-op at runtime)
          compileExpr(qual, enforcePurity)

        else
          // Regular method/function call on an object.
          // Evaluation order: qual first, then args left-to-right.
          // We enforce this by compiling args right-to-left (so earlier args become pure
          // when later args have statements), then compiling qual with enforcePurity = true
          // when any arg has statements.  qualStats ++ argStats guarantees qual's side
          // effects precede all arg side effects.  When no side effects produce statements,
          // Python's own left-to-right evaluation of `recv.m(a, b)` preserves the order.
          val memberName = pythonInteropMemberName(methodSym)
          val procType = methodSym.tpe.asProcType
          val isInteropVararg =
            methodSym.owner.hasAnnotation(runtime.annot_interop) && procType.hasVararg

          val (argStats, argExprs) =
            if isInteropVararg then
              // Split fixed prefix args from the packed vararg last arg, then unpack it.
              val prefixArgs  = args.init
              val varargPack  = args.last
              val prefixParams = (procType.params ++ procType.autos).init
              val (prefixStats, prefixExprs) = compileCallArgListWithTypes(prefixArgs, prefixParams, enforcePurity = false)
              val (varargStats, varargExprs) = compileVarargItems(varargPack, enforcePurity = false)
              (prefixStats ++ varargStats, prefixExprs ++ varargExprs)
            else
              compileCallArgListWithTypes(args, procType.params ++ procType.autos, enforcePurity = false)

          val (qualStats, qualExpr) = compileExpr(qual, enforcePurity = argStats.nonEmpty)
          val stats = qualStats ++ argStats
          val call =
            if methodSym.hasAnnotation(runtime.annot_property) then
              P.Select(qualExpr, memberName)
            else
              P.Call(Some(qualExpr), memberName, argExprs)

          if enforcePurity then
            val tempName = freshTemp()
            (stats :+ P.Assign(tempName, call), P.Ident(tempName))
          else
            (stats, call)

      case Encoded(repr) =>
        // Strip encoding and recurse
        compileCall(repr, args, enforcePurity)

      case _ =>
        throw new Exception("Unexpected function in call: " + fun)

  /** Compile Bool class method operations (&&, ||, ==, !=, ~!, toString) */
  private def compileBoolPrimitive(name: String, qual: Word, args: List[Word], enforcePurity: Boolean)(using scope: UniqueName, ctx: Context): (List[P.Stat], P.Expr) =
    name match
      case "&&" =>
        val arg :: Nil = args: @unchecked
        val (stats, qualExpr, argExpr) = compileTwoArgs(qual, arg, enforcePurity)

        if stats.isEmpty then
          // common case, for better generated code
          (Nil, P.BinOp(qualExpr, "and", argExpr))
        else
          val desugared = If(qual, arg, BoolLit(false)(qual.span))(defn.BoolType, qual.span | arg.span)
          compileExpr(desugared, enforcePurity)

      case "||" =>
        val arg :: Nil = args: @unchecked
        val (stats, qualExpr, argExpr) = compileTwoArgs(qual, arg, enforcePurity)

        if stats.isEmpty then
          // common case, for better generated code
          (Nil, P.BinOp(qualExpr, "or", argExpr))
        else
          val desugared = If(qual, BoolLit(true)(qual.span), arg)(defn.BoolType, qual.span | arg.span)
          compileExpr(desugared, enforcePurity)

      case "==" =>
        val arg :: Nil = args: @unchecked
        val (stats, qualExpr, argExpr) = compileTwoArgs(qual, arg, enforcePurity)
        (stats, P.BinOp(qualExpr, "==", argExpr))

      case "!=" =>
        val arg :: Nil = args: @unchecked
        val (stats, qualExpr, argExpr) = compileTwoArgs(qual, arg, enforcePurity)
        (stats, P.BinOp(qualExpr, "!=", argExpr))

      case "~!" =>
        val (stats, expr) = compileExpr(qual, enforcePurity)
        (stats, P.UnaryOp("not", expr))

      case "toString" =>
        val (stats, expr) = compileExpr(qual, enforcePurity)
        (stats, P.IfExpr(expr, P.StringLit("true"), P.StringLit("false")))

      case _ =>
        throw new Exception(s"Unknown Bool method: $name")

  private def compileIntPrimitive(name: String, qual: Word, args: List[Word], enforcePurity: Boolean)(using scope: UniqueName, ctx: Context): (List[P.Stat], P.Expr) =
    name match
      case "+" | "-" | "*" | "%" | "==" | "!=" | "<" | ">" | "<=" | ">=" | "&" | "|" | "^" | "<<" | ">>" =>
        val arg :: Nil = args: @unchecked
        val (stats, qualExpr, argExpr) = compileTwoArgs(qual, arg, enforcePurity)
        (stats, P.BinOp(qualExpr, name, argExpr))

      case "/" =>
        // Integer division in Python requires //
        val arg :: Nil = args: @unchecked
        val (stats, qualExpr, argExpr) = compileTwoArgs(qual, arg, enforcePurity)
        (stats, P.BinOp(qualExpr, "//", argExpr))

      case "toFloat" =>
        val (stats, expr) = compileExpr(qual, enforcePurity)
        (stats, P.Call(None, "float", List(expr)))

      case "toByte" =>
        val (stats, expr) = compileExpr(qual, enforcePurity)
        (stats, P.BinOp(expr, "&", P.IntLit(0xFF)))

      case "toChar" =>
        // Char is represented as Int (Unicode code point) in Python, so this is a no-op
        compileExpr(qual, enforcePurity)

      case "~-" =>
        val (stats, expr) = compileExpr(qual, enforcePurity)
        (stats, P.UnaryOp("-", expr))

      case "toString" =>
        val (stats, expr) = compileExpr(qual, enforcePurity)
        (stats, P.Call(None, "str", List(expr)))

      case _ =>
        throw new Exception(s"Unknown Int method: $name")

  /** Compile Byte primitive operations */
  private def compileBytePrimitive(name: String, qual: Word, args: List[Word], enforcePurity: Boolean)(using scope: UniqueName, ctx: Context): (List[P.Stat], P.Expr) =
    name match
      case "toInt" =>
        // Byte is already represented as int in Python
        compileExpr(qual, enforcePurity)

      case "toChar" =>
        // Both Byte and Char are int in Python
        compileExpr(qual, enforcePurity)

      case _ =>
        // All other Byte operations are the same as Int operations
        compileIntPrimitive(name, qual, args, enforcePurity)

  /** Compile Char primitive operations */
  private def compileCharPrimitive(name: String, qual: Word, args: List[Word], enforcePurity: Boolean)(using scope: UniqueName, ctx: Context): (List[P.Stat], P.Expr) =
    name match
      case "==" | "!=" | "<" | ">" | "<=" | ">=" =>
        val arg :: Nil = args: @unchecked
        val (stats, qualExpr, argExpr) = compileTwoArgs(qual, arg, enforcePurity)
        (stats, P.BinOp(qualExpr, name, argExpr))

      case "toByte" =>
        // Char is already represented as Int in Python
        val (stats, expr) = compileExpr(qual, enforcePurity)
        (stats, P.BinOp(expr, "&", P.IntLit(0xFF)))

      case "toInt" =>
        // Char is already represented as Int (Unicode code point) in Python
        compileExpr(qual, enforcePurity)

      case "toString" =>
        // Use chr to convert Unicode code point to string
        val (stats, expr) = compileExpr(qual, enforcePurity)
        (stats, P.Call(None, "chr", List(expr)))

      case _ =>
        throw new Exception(s"Unknown Char method: $name")

  /** Compile Float primitive operations */
  private def compileFloatPrimitive(name: String, qual: Word, args: List[Word], enforcePurity: Boolean)(using scope: UniqueName, ctx: Context): (List[P.Stat], P.Expr) =
    name match
      case "+" | "-" | "*" | "/" | ">" | "<" | ">=" | "<=" | "==" | "!=" =>
        val arg :: Nil = args: @unchecked
        val (stats, qualExpr, argExpr) = compileTwoArgs(qual, arg, enforcePurity)
        (stats, P.BinOp(qualExpr, name, argExpr))

      case "toInt" =>
        val (stats, expr) = compileExpr(qual, enforcePurity)
        (stats, P.Call(None, "int", List(expr)))

      case "~-" =>
        val (stats, expr) = compileExpr(qual, enforcePurity)
        (stats, P.UnaryOp("-", expr))

      case "toString" =>
        val (stats, expr) = compileExpr(qual, enforcePurity)
        (stats, P.Call(None, "str", List(expr)))

      case _ =>
        throw new Exception(s"Unknown Float method: $name")

  /** Compile String primitive operations */
  private def compileStringPrimitive(name: String, qual: Word, args: List[Word], enforcePurity: Boolean)(using scope: UniqueName, ctx: Context): (List[P.Stat], P.Expr) =
    name match
      case "+" =>
        val arg :: Nil = args: @unchecked
        val (stats, qualExpr, argExpr) = compileTwoArgs(qual, arg, enforcePurity)
        (stats, P.BinOp(qualExpr, "+", argExpr))

      case "==" =>
        val arg :: Nil = args: @unchecked
        val (stats, qualExpr, argExpr) = compileTwoArgs(qual, arg, enforcePurity)
        (stats, P.BinOp(qualExpr, "==", argExpr))

      case "size" =>
        val (stats, expr) = compileExpr(qual, enforcePurity)
        (stats, P.Call(None, "len", List(expr)))

      case "get" =>
        val index :: Nil = args: @unchecked
        val (stats, qualExpr, indexExpr) = compileTwoArgs(qual, index, enforcePurity)
        // ord(s[index]) - get character code point at index
        (stats, P.Call(None, "ord", List(P.Index(qualExpr, indexExpr))))

      case "substring" =>
        val index :: len :: Nil = args: @unchecked
        val (stats, exprs) = compileExprList(List(qual, index, len), enforcePurity)
        val qualExpr :: indexExpr :: lenExpr :: Nil = exprs: @unchecked
        // s[index:index+len] - Python slice syntax
        val endExpr = P.BinOp(indexExpr, "+", lenExpr)
        (stats, P.Index(qualExpr, P.Slice(indexExpr, endExpr)))

      case "indexOf" =>
        val other :: from :: Nil = args: @unchecked
        val (stats, exprs) = compileExprList(List(qual, other, from), enforcePurity)
        val qualExpr :: otherExpr :: fromExpr :: Nil = exprs: @unchecked
        val lowerBounded = P.Call(None, "max", List(fromExpr, P.IntLit(0)))
        val upperBound = P.Call(None, "len", List(qualExpr))
        val startExpr = P.Call(None, "min", List(lowerBounded, upperBound))
        (stats, P.Call(Some(qualExpr), "find", List(otherExpr, startExpr)))

      case "iterator" =>
        val (stats, exprs) = compileExprList(List(qual), enforcePurity = false)
        val call = P.Call(None, pythonName(runtime.String_iterator), exprs)
        if enforcePurity then
          val tempName = freshTemp()
          (stats :+ P.Assign(tempName, call), P.Ident(tempName))
        else
          (stats, call)

      case "toLower" =>
        val (stats, expr) = compileExpr(qual, enforcePurity)
        (stats, P.Call(Some(expr), "lower", Nil))

      case "toUpper" =>
        val (stats, expr) = compileExpr(qual, enforcePurity)
        (stats, P.Call(Some(expr), "upper", Nil))

      case _ =>
        throw new Exception(s"Unknown String method: $name")

  /** Generate Python code from file units and write to output file */
  def generate(units: List[FileUnit], outFile: String): Unit =
    val program = compile(units)

    val pw = new java.io.PrintWriter(outFile)
    Printer.print(program, pw)
    pw.flush()
    pw.close()

end PythonCodeGen

object PythonCodeGen:
  private val symbolEncoding = Map(
    '$' -> "_D_",
    '.' -> "_",
    '+' -> "_plus",
    '-' -> "_minus",
    '*' -> "_times",
    '/' -> "_div",
    '%' -> "_mod",
    '&' -> "_and",
    '|' -> "_or",
    '^' -> "_xor",
    '<' -> "_lt",
    '>' -> "_gt",
    '=' -> "_eq",
    '!' -> "_bang",
    '~' -> "_tilde",
    '?' -> "_q",
    '@' -> "_at",
    '#' -> "_hash",
    ':' -> "_colon"
  )

  def encodeSymbolic(name: String): String =
    val sb = new StringBuilder(name.length * 2)
    name.foreach { ch =>
      symbolEncoding.get(ch) match
        case Some(replacement) => sb.append(replacement)
        case None => sb.append(ch)
    }
    sb.toString

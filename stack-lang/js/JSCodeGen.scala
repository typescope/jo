package js

import sast.*
import sast.Trees.*
import sast.Symbols.Symbol
import sast.Types

import js.Trees as JS

import common.UniqueName
import common.WorkList

import scala.collection.mutable

/** Code generator that translates Jo SAST to JavaScript AST
  *
  */
class JSCodeGen(runtime: JSRuntime, rewire: Map[Symbol, Symbol])(using defn: Definitions):

  //----------------------------------------------------------------------------
  // Name management
  //----------------------------------------------------------------------------
  val SingletonFieldName = "__instance"

  private val reservedNames = new UniqueName(separator = "")

  val keywords = List(
    // JavaScript keywords
    "for", "while", "function", "var", "let", "const", "break", "continue",
    "if", "else", "switch", "case", "default", "return", "yield",
    "try", "catch", "finally", "throw",
    "import", "export", "from", "as", "class", "extends", "super",
    "new", "this", "typeof", "instanceof", "void", "delete",
    "async", "await", "static", "get", "set",
    "true", "false", "null", "undefined", "constructor",
    // JavaScript built-in objects/functions
    "Array", "Object", "String", "Number", "Boolean", "Function",
    "Math", "Date", "JSON", "Promise", "Error",
    "parseInt", "parseFloat", "isNaN", "isFinite",
    "console", "Buffer", "require", "module", "exports",
    "window", "document", "global", "process"
  )

  // Make keywords unavailable
  for word <- keywords do reservedNames.freshName(word)

  // Make runtime symbols unavailable
  for name <- runtime.runtimeNames do reservedNames.freshName(name)

  private val symbol2UniqueName: mutable.Map[Symbol, String] = mutable.Map.empty

  val globalScope = reservedNames.newScope(separator = "")

  def jsMemberName(sym: Symbol): String =
    assert(sym.isOneOf(Flags.Method | Flags.Field), "Not a method, sym = " + sym)

    symbol2UniqueName.get(sym) match
      case Some(name) => name

      case _ =>
        val rawName = JSCodeGen.encodeSymbolic(sym.name)
        val scope = reservedNames.newScope("_")
        val name = scope.freshName(rawName)
        symbol2UniqueName(sym) = name
        name

  def jsName(sym: Symbol)(using scope: UniqueName): String =
    assert(!sym.isOneOf(Flags.Method | Flags.Field), "Member name should call jsMemberName, sym = " + sym)

    symbol2UniqueName.get(sym) match
      case Some(name) => name

      case None =>
        rewire.get(sym) match
          case Some(target) => jsName(target)

          case None =>
            val uniqueName =
              if sym.isLocal then
                scope.freshName(JSCodeGen.encodeSymbolic(sym.name))

              else
                val rawName = sym.fullName.replace(".", "_")
                val baseName = globalScope.freshName(JSCodeGen.encodeSymbolic(rawName))
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

  /** Compile a complete set of file units to a JavaScript program */
  def compile(units: List[FileUnit]): JS.Program =
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

    val defs = mutable.ArrayBuffer.empty[JS.Def]

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

    // Build the program: combine all initialization with the main call
    val initStatements = mutable.ArrayBuffer.empty[JS.Stat]

    // Global runtime initialization: var __runtime_contextParams = {};
    initStatements += JS.VarDecl("var", "__runtime_contextParams", JS.ObjectLit(Nil))

    // Context parameter ID constants: const __param_... = "...";
    runtime.paramIds.foreach: (fullName, globalId) =>
      val jsSym = JS.Call(Some(JS.Ident("Symbol")), "for", JS.StringLit(fullName) :: Nil)
      initStatements += JS.VarDecl("const", globalId, jsSym)

    // Start call
    val startCall = JS.ExprStat(JS.Call(None, jsName(runtime.start), Nil))

    // Combine all initialization with start call
    val mainBlock = JS.Block(initStatements.toList :+ startCall)

    JS.Program(
      defs = defs.toList,
      mainCall = mainBlock
    )

  /** Compile a function definition */
  private def compileFunction(fdef: FunDef): JS.FunDef =
    val sym = fdef.symbol

    // Object accessors should not be reachable - they're replaced with direct access
    assert(!sym.is(Flags.Object),
      s"Object accessor ${sym.name} should not be compiled - it should be replaced with static field access")

    // Create new scope for function locals
    given UniqueName = reservedNames.newScope(separator = "")

    val name =
      if sym.is(Flags.Constructor) then
        // JavaScript constructor is always named "constructor"
        "constructor"

      else if sym.is(Flags.Method) then
        jsMemberName(sym)

      else
        jsName(sym)

    val baseParams = fdef.params.map(param => jsName(param)) ++ fdef.autos.map(auto => jsName(auto))

    val params =
      if sym.is(Flags.Method) then
        // Methods need 'this' binding - handled automatically in JavaScript
        baseParams
      else
        baseParams

    val localDecls =
      fdef.locals.filter(_.isMutable).map(sym => JS.VarDecl("var", jsName(sym), JS.UndefinedLit))

    // For constructor, don't add return statement (or return value)
    val body = if sym.is(Flags.Constructor) then
      val (stats, expr) = compileExpr(fdef.body, enforcePurity = false)
      // Constructor should not return a value, so discard the final expression
      JS.Block((localDecls ++ stats) :+ JS.ExprStat(expr))

    else
      compileFunctionBody(fdef.body) match
        case JS.Block(stats) => JS.Block(localDecls ++ stats)

    JS.FunDef(name, params, body)

  /** Compile a class definition */
  private def compileClass(cdef: ClassDef)(using UniqueName): JS.ClassDef =
    val classSym = cdef.symbol
    val jsClassName = jsName(classSym)

    symbol2UniqueName(cdef.self) = "this"

    // Get all fields from the class definition
    val fieldNames = cdef.vals.map(jsMemberName)

    // Compile methods - each method gets compiled with its own scope
    val methods = cdef.funs.map(compileFunction)

    // Add static field if this is a singleton object
    val staticFields =
      if classSym.is(Flags.Object) then
        JS.Assign(SingletonFieldName, JS.New(jsClassName, Nil)) :: Nil
      else
        Nil

    JS.ClassDef(
      name = jsClassName,
      fields = fieldNames,
      methods = methods,
      staticFields = staticFields
    )

  /** Compile function body (adds Return statement unless it ends with Throw) */
  private def compileFunctionBody(word: Word)(using UniqueName): JS.Block =
    val (stats, expr) = compileExpr(word, enforcePurity = false)
    // If the last statement is a Throw, don't add Return (throw never returns)
    stats.lastOption match
      case Some(_: JS.Throw) =>
        // Invariant: if statements end with Throw, expr must be NullLit (never reached)
        assert(expr == JS.NullLit, s"Expected NullLit after Throw, got: $expr")
        JS.Block(stats)
      case _ => JS.Block(stats :+ JS.Return(expr))

  /** Helper for fresh temporary variable names */
  private def freshTemp()(using scope: UniqueName): String =
    scope.freshName("_temp")

  /** Compile in statement position (no value needed) */
  private def compileStat(word: Word)(using UniqueName): JS.Stat =
    word match
      case Block(words) =>
        // In statement position, all words become statements
        JS.Block(words.map(compileStat))

      case Assign(Ident(sym), rhs) =>
        // Assignment: RHS is in EXPRESSION position (need the value)
        val (rhsStats, rhsExpr) = compileExpr(rhs, enforcePurity = false)
        val assign =
          if sym.isMutable then
            JS.Assign(jsName(sym), rhsExpr)

          else
            // Use `var` because pattern desugared variables are out of scope.
            //
            // Uniqueness of symbol names is guaranteed by the name generator.
            JS.VarDecl("var", jsName(sym), rhsExpr)

        if rhsStats.isEmpty then
          assign

        else
          JS.Block(rhsStats :+ assign)

      case FieldAssign(lhs @ Select(qual, _), rhs) =>
        val memberName = lhs.tpe match
          case Types.MemberRef(_, sym) => jsMemberName(sym)
          case _ => throw new Exception("Unexpected lhs of assign: " + lhs.show)

        val (rhsStats, rhsExpr) = compileExpr(rhs, enforcePurity = false)
        val (qualStats, qualExpr) = compileExpr(qual, enforcePurity = false)

        if rhsStats.isEmpty && qualStats.isEmpty then
          JS.FieldAssign(qualExpr, memberName, rhsExpr)

        else
          JS.Block((qualStats ++ rhsStats) :+ JS.FieldAssign(qualExpr, memberName, rhsExpr))

      case While(cond, body) =>
        // While loop: condition in expression position, body in statement position
        val (condStats, condExpr) = compileExpr(cond, enforcePurity = false)
        val bodyStat = compileStat(body)

        if condStats.isEmpty then
          JS.While(condExpr, bodyStat)

        else
          // Need to put condition inside while body
          val break = JS.IfStat(JS.UnaryOp("!", condExpr), JS.Break, JS.Block(Nil))
          val body = JS.Block(condStats :+ break :+ bodyStat)
          JS.While(JS.BoolLit(true), body)

      case If(cond, thenp, elsep) =>
        // In statement position, use IfStat directly
        val (condStats, condExpr) = compileExpr(cond, enforcePurity = false)  // condition is expression
        val thenStat = compileStat(thenp)  // branches in statement position
        val elseStat = compileStat(elsep)

        if condStats.isEmpty then
          JS.IfStat(condExpr, thenStat, elseStat)

        else
          JS.Block(condStats :+ JS.IfStat(condExpr, thenStat, elseStat))

      case Encoded(repr) =>
        compileStat(repr)

      case Apply(fun, args, autos) =>
        // Function call as statement (for side effects)
        val (stats, expr) = compileCall(fun, args ++ autos, enforcePurity = false)
        JS.Block(stats :+ JS.ExprStat(expr))

      case _: TypeDef =>
        JS.Block(Nil)  // Empty block

      case _ =>
        // For other expression-like constructs, compile as expression and discard value
        val (stats, expr) = compileExpr(word, enforcePurity = false)
        JS.Block(stats :+ JS.ExprStat(expr))

  /** Compile in expression position (value needed, lift statements)
    *
    * @param enforcePurity If true, ensures the result expression is pure by wrapping
    *                      impure expressions in temporary variables. This preserves
    *                      evaluation order when statements from later arguments need
    *                      to be lifted.
    */
  private def compileExpr(word: Word, enforcePurity: Boolean)(using UniqueName): (List[JS.Stat], JS.Expr) =
    word match
      case Block(words) =>
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

        // 3. Compile condition with purity enforcement if the result should be pure ternary expr
        val (condStats, condExpr) = compileExpr(cond, enforcePurity && !needsIfStatement)

        if !needsIfStatement then
          // Both branches are simple (no statements)
          // → Use ternary conditional!
          val ternary = JS.Conditional(condExpr, thenExpr, elseExpr)
          val result = (condStats, ternary)
          // Ternary might have side effects in branches - wrap if purity needed
          if enforcePurity then
            val tempName = freshTemp()
            (condStats :+ JS.VarDecl("const", tempName, ternary), JS.Ident(tempName))
          else
            result
        else
          // At least one branch has statements
          // → Must use if-statement with temp variable
          // Branch statements stay INSIDE the if-statement blocks
          val tempName = freshTemp()
          val tempDecl = JS.VarDecl("let", tempName, JS.UndefinedLit)
          // If a branch ends with Throw, don't add assignment (throw never returns)
          val thenBlock = thenStats.lastOption match
            case Some(_: JS.Throw) =>
              // Invariant: if statements end with Throw, expr must be NullLit (never reached)
              assert(thenExpr == JS.NullLit, s"Expected NullLit after Throw in then branch, got: $thenExpr")
              JS.Block(thenStats)

            case _ => JS.Block(thenStats :+ JS.Assign(tempName, thenExpr))

          val elseBlock = elseStats.lastOption match
            case Some(_: JS.Throw) =>
              // Invariant: if statements end with Throw, expr must be NullLit (never reached)
              assert(elseExpr == JS.NullLit, s"Expected NullLit after Throw in else branch, got: $elseExpr")
              JS.Block(elseStats)

            case _ => JS.Block(elseStats :+ JS.Assign(tempName, elseExpr))

          val ifStmt = JS.IfStat(condExpr, thenBlock, elseBlock)
          // Result is already a temp variable (pure identifier)
          (condStats :+ tempDecl :+ ifStmt, JS.Ident(tempName))

      case Literal(c) =>
        // Literals are always pure
        (Nil, compileLiteral(c))

      case Ident(sym) =>
        assert(!sym.is(Flags.Context), "Unexpected context parameter")
        if enforcePurity && sym.isMutable then
          // Mutable variable reads are impure - wrap in temp
          val tempName = freshTemp()
          (List(JS.VarDecl("const", tempName, JS.Ident(jsName(sym)))), JS.Ident(tempName))

        else
          (Nil, JS.Ident(jsName(sym)))

      case Select(qual, name) =>
        word.tpe match
          case Types.MemberRef(_, sym) =>
            val (qualStats, qualExpr) = compileExpr(qual, enforcePurity)
            val memberName = jsMemberName(sym)
            (qualStats, JS.Select(qualExpr, memberName))

          case _ => throw new Exception("Unexpected select: " + word.show)

      case encoded @ Encoded(repr) =>
        if encoded.isValueDrop then
          throw new Exception("Unexpected value drop in expression position: " + encoded.show)

        else if encoded.tpe.isLambdaType && repr.tpe.isClassType then
          // Wrap class instance as lambda
          val (objStats, objExpr) = compileExpr(repr, enforcePurity = false)  // lambda body is OK
          // (...args) => obj.apply(...args)
          // Lambda creation is pure - just creates a function object
          val lambdaBody = JS.Call(Some(objExpr), "apply", List(JS.RawCode("...args")))
          val lambdaExpr = JS.Arrow(List("...args"), lambdaBody)
          (objStats, lambdaExpr)

        else
          // Pass through enforcePurity
          compileExpr(repr, enforcePurity)

      case Apply(Select(New(classType), _), args, autos) =>
        // Object construction - always impure, wrap if purity needed
        val classSym = classType.tpe.asClassInfo.classSymbol
        val allArgs = args ++ autos
        val (argStats, argExprs) = compileExprList(allArgs, enforcePurity = false)
        val newExpr = JS.New(jsName(classSym), argExprs)

        if enforcePurity then
          val tempName = freshTemp()
          (argStats :+ JS.VarDecl("const", tempName, newExpr), JS.Ident(tempName))
        else
          (argStats, newExpr)


      case ClassTest(arg, cls) =>
        // Type test for union types - this is pure
        val (argStats, argExpr) = compileExpr(arg, enforcePurity)

        val test =
          if cls == defn.PlatformString_type then
            val cond1 = JS.BinOp(JS.UnaryOp("typeof", argExpr), "==", JS.StringLit("string"))
            JS.BinOp(cond1, "||", JS.InstanceOf(argExpr, "String"))

          else if cls == defn.Bool_type then
            JS.BinOp(JS.UnaryOp("typeof", argExpr), "==", JS.StringLit("boolean"))

          else if cls == defn.Float_type || cls == defn.Int_type || cls == defn.Byte_type || cls == defn.Char_type then
            JS.BinOp(JS.UnaryOp("typeof", argExpr), "==", JS.StringLit("number"))

          else
            JS.InstanceOf(argExpr, jsName(cls))

        // Type test is pure if argExpr is pure
        (argStats, test)

      case Apply(fun, args, autos) =>
        // Function/method calls are generally impure, wrap if purity needed
        compileCall(fun, args ++ autos, enforcePurity)

      case _: TypeDef =>
        // Type definitions are pure (erased)
        (Nil, JS.UndefinedLit)

      case _: Def | _: With | _: Allow | _: Match |
           _: New | _: IsExpr | _: CaseDef | _: Lambda | _: RecordLit |
           _: Assign | _: FieldAssign | _: While | _: TypeApply =>
        throw new Exception("Unexpected in expression position: " + word)

  /** Compile a literal constant */
  private def compileLiteral(c: Constant): JS.Expr =
    c match
      case Constant.Bool(b) => JS.BoolLit(b)
      case Constant.String(s) => JS.StringLit(s)
      case Constant.Int(n) => JS.IntLit(n)
      case Constant.Float(d) => JS.FloatLit(d)

  /** Compile a list of expressions, correctly handling evaluation order */
  private def compileExprList(words: List[Word], enforcePurity: Boolean)(using UniqueName): (List[JS.Stat], List[JS.Expr]) =
    var stats: List[JS.Stat] = Nil
    var exprs: List[JS.Expr] = Nil

    for word <- words.reverse do
      val shouldEnforcePurity = enforcePurity || stats.nonEmpty
      val (wordStats, wordExpr) = compileExpr(word, shouldEnforcePurity)
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
  private def compileTwoArgs(lhs: Word, rhs: Word, enforcePurity: Boolean)(using UniqueName): (List[JS.Stat], JS.Expr, JS.Expr) =
    val (statsRhs, exprRhs) = compileExpr(rhs, enforcePurity)
    val (statsLhs, exprLhs) = compileExpr(lhs, enforcePurity || statsRhs.nonEmpty)
    (statsLhs ++ statsRhs, exprLhs, exprRhs)

  /** Compile a function/method call */
  private def compileCall(fun: Word, args: List[Word], enforcePurity: Boolean)(using UniqueName): (List[JS.Stat], JS.Expr) =
    fun match
      case Ident(sym) if sym.isFunction =>
        if sym.is(Flags.Object) then
          // direct singleton object access
          val funType = sym.info.asProcType
          val classInfo = funType.resultType.asClassInfo
          val classSym = classInfo.classSymbol

          // Mark class reachable
          jsName(classSym)

          // Direct access to static singleton field
          (Nil, JS.Select(JS.Ident(jsName(classSym)), SingletonFieldName))

        else if sym == runtime.js then
          // Raw JavaScript code
          val Literal(Constant.String(code)) :: Nil = args : @unchecked

          if enforcePurity then
            val tempName = freshTemp()
            (JS.VarDecl("const", tempName, JS.RawCode(code)) :: Nil, JS.Ident(tempName))
          else
            (Nil, JS.RawCode(code))

        else if sym == runtime.paramKey then
          val paramSym = args.head match
            case Ident(paramSym) => paramSym
            case Literal(Constant.String(path)) => defn.resolveTerm(path) // special support for entry method
            case word => throw new Exception("Unsupported argument to paramKey: " + word)

          val keyId = runtime.getOrCreateParamId(paramSym)
          (Nil, JS.Ident(keyId))

        else if sym == defn.jo_pass then
          (Nil, JS.NullLit)

        else
          val (argStats, argExprs) = compileExprList(args, enforcePurity = false)
          val call = JS.Call(None, jsName(sym), argExprs)
          if enforcePurity then
            val tempName = freshTemp()
            (argStats :+ JS.VarDecl("const", tempName, call), JS.Ident(tempName))
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

      case Select(qual, name) =>
        // Regular method/function call on an object
        // Treat qualifier + args together to enforce proper evaluation order
        val memberName = fun.tpe match
          case Types.MemberRef(_, sym) => jsMemberName(sym)
          case _ => throw new Exception("Unexpected select: " + fun.show)

        val (stats, qualExpr :: argExprs) = compileExprList(qual :: args, enforcePurity = false): @unchecked
        val call = JS.Call(Some(qualExpr), memberName, argExprs)

        if enforcePurity then
          val tempName = freshTemp()
          (stats :+ JS.VarDecl("const", tempName, call), JS.Ident(tempName))

        else
          (stats, call)

      case TypeApply(fun2, _) =>
        // Strip type application and recurse
        compileCall(fun2, args, enforcePurity)

      case f if f.tpe.isLambdaType =>
        // Lambda call
        val (funStats, funExpr) = compileExpr(f, enforcePurity = false)
        val (argStats, argExprs) = compileExprList(args, enforcePurity = false)

        val call = JS.Call(None, "", argExprs) match
          case call => call.copy(receiver = Some(funExpr))

        if enforcePurity then
          val tempName = freshTemp()
          (funStats ++ argStats :+ JS.VarDecl("const", tempName, call), JS.Ident(tempName))
        else
          (funStats ++ argStats, call)

      case Encoded(repr) =>
        // Strip encoding and recurse
        compileCall(repr, args, enforcePurity)

      case _ =>
        throw new Exception("Unexpected function in call: " + fun)

  /** Compile Bool class method operations (&&, ||, ==, !=, ~!, toString) */
  private def compileBoolPrimitive(name: String, qual: Word, args: List[Word], enforcePurity: Boolean)(using UniqueName): (List[JS.Stat], JS.Expr) =
    name match
      case "&&" =>
        val arg :: Nil = args: @unchecked
        val (stats, qualExpr, argExpr) = compileTwoArgs(qual, arg, enforcePurity)

        if stats.isEmpty then
          // common case, for better generated code
          (Nil, JS.BinOp(qualExpr, "&&", argExpr))
        else
          val desugared = If(qual, arg, BoolLit(false)(qual.span))(defn.BoolType, qual.span | arg.span)
          compileExpr(desugared, enforcePurity)

      case "||" =>
        val arg :: Nil = args: @unchecked
        val (stats, qualExpr, argExpr) = compileTwoArgs(qual, arg, enforcePurity)

        if stats.isEmpty then
          // common case, for better generated code
          (Nil, JS.BinOp(qualExpr, "||", argExpr))
        else
          val desugared = If(qual, BoolLit(true)(qual.span), arg)(defn.BoolType, qual.span | arg.span)
          compileExpr(desugared, enforcePurity)

      case "==" =>
        val arg :: Nil = args: @unchecked
        val (stats, qualExpr, argExpr) = compileTwoArgs(qual, arg, enforcePurity)
        (stats, JS.BinOp(qualExpr, "===", argExpr))

      case "!=" =>
        val arg :: Nil = args: @unchecked
        val (stats, qualExpr, argExpr) = compileTwoArgs(qual, arg, enforcePurity)
        (stats, JS.BinOp(qualExpr, "!==", argExpr))

      case "~!" =>
        val (stats, expr) = compileExpr(qual, enforcePurity)
        (stats, JS.UnaryOp("!", expr))

      case "toString" =>
        val (stats, expr) = compileExpr(qual, enforcePurity)
        (stats, JS.Call(Some(expr), "toString", Nil))

      case _ =>
        throw new Exception(s"Unknown Bool method: $name")

  /** Compile Int primitive operations */
  private def compileIntPrimitive(name: String, qual: Word, args: List[Word], enforcePurity: Boolean)(using UniqueName): (List[JS.Stat], JS.Expr) =
    name match
      case "+" | "-" | "*" | "%" | "<" | ">" | "<=" | ">=" | "&" | "|" | "^" | "<<" | ">>" =>
        val arg :: Nil = args: @unchecked
        val (stats, qualExpr, argExpr) = compileTwoArgs(qual, arg, enforcePurity)
        (stats, JS.BinOp(qualExpr, name, argExpr))

      case "==" =>
        val arg :: Nil = args: @unchecked
        val (stats, qualExpr, argExpr) = compileTwoArgs(qual, arg, enforcePurity)
        (stats, JS.BinOp(qualExpr, "===", argExpr))

      case "!=" =>
        val arg :: Nil = args: @unchecked
        val (stats, qualExpr, argExpr) = compileTwoArgs(qual, arg, enforcePurity)
        (stats, JS.BinOp(qualExpr, "!==", argExpr))

      case "/" =>
        // Integer division in JavaScript requires Math.floor
        val arg :: Nil = args: @unchecked
        val (stats, qualExpr, argExpr) = compileTwoArgs(qual, arg, enforcePurity)
        val divExpr = JS.BinOp(qualExpr, "/", argExpr)
        (stats, JS.Call(Some(JS.Ident("Math")), "floor", List(divExpr)))

      case "toFloat" =>
        val (stats, expr) = compileExpr(qual, enforcePurity)
        (stats, expr)  // JavaScript numbers are already floats

      case "toByte" =>
        val (stats, expr) = compileExpr(qual, enforcePurity)
        (stats, JS.BinOp(expr, "&", JS.IntLit(0xFF)))

      case "toChar" =>
        // Char is represented as Int (Unicode code point) in JavaScript, so this is a no-op
        compileExpr(qual, enforcePurity)

      case "~-" =>
        val (stats, expr) = compileExpr(qual, enforcePurity)
        (stats, JS.UnaryOp("-", expr))

      case "toString" =>
        val (stats, expr) = compileExpr(qual, enforcePurity)
        (stats, JS.Call(Some(expr), "toString", Nil))

      case _ =>
        throw new Exception(s"Unknown Int method: $name")

  /** Compile Byte primitive operations */
  private def compileBytePrimitive(name: String, qual: Word, args: List[Word], enforcePurity: Boolean)(using UniqueName): (List[JS.Stat], JS.Expr) =
    name match
      case "toInt" =>
        // Byte is already represented as number in JavaScript
        compileExpr(qual, enforcePurity)

      case "toChar" =>
        // Both Byte and Char are number in JavaScript
        compileExpr(qual, enforcePurity)

      case _ =>
        // All other Byte operations are the same as Int operations
        compileIntPrimitive(name, qual, args, enforcePurity)

  /** Compile Char primitive operations */
  private def compileCharPrimitive(name: String, qual: Word, args: List[Word], enforcePurity: Boolean)(using UniqueName): (List[JS.Stat], JS.Expr) =
    name match
      case "<" | ">" | "<=" | ">=" =>
        val arg :: Nil = args: @unchecked
        val (stats, qualExpr, argExpr) = compileTwoArgs(qual, arg, enforcePurity)
        (stats, JS.BinOp(qualExpr, name, argExpr))

      case "==" =>
        val arg :: Nil = args: @unchecked
        val (stats, qualExpr, argExpr) = compileTwoArgs(qual, arg, enforcePurity)
        (stats, JS.BinOp(qualExpr, "===", argExpr))

      case "!=" =>
        val arg :: Nil = args: @unchecked
        val (stats, qualExpr, argExpr) = compileTwoArgs(qual, arg, enforcePurity)
        (stats, JS.BinOp(qualExpr, "!==", argExpr))

      case "toByte" =>
        // Char is already represented as Int in JavaScript
        val (stats, expr) = compileExpr(qual, enforcePurity)
        (stats, JS.BinOp(expr, "&", JS.IntLit(0xFF)))

      case "toInt" =>
        // Char is already represented as Int (Unicode code point) in JavaScript
        compileExpr(qual, enforcePurity)

      case "toString" =>
        // Use String.fromCodePoint to convert Unicode code point to string
        val (stats, expr) = compileExpr(qual, enforcePurity)
        (stats, JS.Call(Some(JS.Ident("String")), "fromCodePoint", List(expr)))

      case _ =>
        throw new Exception(s"Unknown Char method: $name")

  /** Compile Float primitive operations */
  private def compileFloatPrimitive(name: String, qual: Word, args: List[Word], enforcePurity: Boolean)(using UniqueName): (List[JS.Stat], JS.Expr) =
    name match
      case "+" | "-" | "*" | "/" | ">" | "<" | ">=" | "<=" =>
        val arg :: Nil = args: @unchecked
        val (stats, qualExpr, argExpr) = compileTwoArgs(qual, arg, enforcePurity)
        (stats, JS.BinOp(qualExpr, name, argExpr))

      case "==" =>
        val arg :: Nil = args: @unchecked
        val (stats, qualExpr, argExpr) = compileTwoArgs(qual, arg, enforcePurity)
        (stats, JS.BinOp(qualExpr, "===", argExpr))

      case "!=" =>
        val arg :: Nil = args: @unchecked
        val (stats, qualExpr, argExpr) = compileTwoArgs(qual, arg, enforcePurity)
        (stats, JS.BinOp(qualExpr, "!==", argExpr))

      case "toInt" =>
        val (stats, expr) = compileExpr(qual, enforcePurity)
        (stats, JS.Call(Some(JS.Ident("Math")), "floor", List(expr)))

      case "~-" =>
        val (stats, expr) = compileExpr(qual, enforcePurity)
        (stats, JS.UnaryOp("-", expr))

      case "toString" =>
        val (stats, expr) = compileExpr(qual, enforcePurity)
        (stats, JS.Call(Some(expr), "toString", Nil))

      case _ =>
        throw new Exception(s"Unknown Float method: $name")

  /** Compile String primitive operations */
  private def compileStringPrimitive(name: String, qual: Word, args: List[Word], enforcePurity: Boolean)(using UniqueName): (List[JS.Stat], JS.Expr) =
    name match
      case "+" =>
        val arg :: Nil = args: @unchecked
        val (stats, qualExpr, argExpr) = compileTwoArgs(qual, arg, enforcePurity)
        (stats, JS.BinOp(qualExpr, "+", argExpr))

      case "==" =>
        val arg :: Nil = args: @unchecked
        val (stats, qualExpr, argExpr) = compileTwoArgs(qual, arg, enforcePurity)
        (stats, JS.BinOp(qualExpr, "===", argExpr))

      case "size" =>
        val (stats, exprs) = compileExprList(qual :: Nil, enforcePurity = false)
        val call = JS.Call(None, jsName(runtime.String_size), exprs)
        if enforcePurity then
          val tempName = freshTemp()
          (stats :+ JS.VarDecl("const", tempName, call), JS.Ident(tempName))
        else
          (stats, call)

      case "get" =>
        val index :: Nil = args: @unchecked
        val (stats, exprs) = compileExprList(qual :: index :: Nil, enforcePurity = false)
        val call = JS.Call(None, jsName(runtime.String_get), exprs)
        if enforcePurity then
          val tempName = freshTemp()
          (stats :+ JS.VarDecl("const", tempName, call), JS.Ident(tempName))
        else
          (stats, call)

      case "substring" =>
        val index :: len :: Nil = args: @unchecked
        val (stats, exprs) = compileExprList(qual :: index :: len :: Nil, enforcePurity = false)
        val call = JS.Call(None, jsName(runtime.String_substring), exprs)
        if enforcePurity then
          val tempName = freshTemp()
          (stats :+ JS.VarDecl("const", tempName, call), JS.Ident(tempName))
        else
          (stats, call)

      case "indexOfFrom" =>
        val other :: from :: Nil = args: @unchecked
        val (stats, exprs) = compileExprList(qual :: other :: from :: Nil, enforcePurity = false)
        val call = JS.Call(None, jsName(runtime.String_indexOfFrom), exprs)
        if enforcePurity then
          val tempName = freshTemp()
          (stats :+ JS.VarDecl("const", tempName, call), JS.Ident(tempName))
        else
          (stats, call)

      case "toLower" =>
        val (stats, expr) = compileExpr(qual, enforcePurity)
        (stats, JS.Call(Some(expr), "toLowerCase", Nil))

      case "toUpper" =>
        val (stats, expr) = compileExpr(qual, enforcePurity)
        (stats, JS.Call(Some(expr), "toUpperCase", Nil))

      case _ =>
        throw new Exception(s"Unknown String method: $name")

  /** Generate JavaScript code from file units and write to output file */
  def generate(units: List[FileUnit], outFile: String): Unit =
    val program = this.compile(units)

    val pw = new java.io.PrintWriter(outFile)
    Printer.print(program, pw)
    pw.flush()
    pw.close()

end JSCodeGen

object JSCodeGen:
  private val symbolEncoding = Map(
    '$' -> "_dollar",
    '.' -> "_",
    '+' -> "_plus",
    '-' -> "_minus",
    '*' -> "_times",
    '/' -> "_div",
    '%' -> "_mod",
    '&' -> "_and",
    '|' -> "_or",
    '^' -> "_xor",
    '!' -> "_bang",
    '?' -> "_qmark",
    '=' -> "_eq",
    '<' -> "_lt",
    '>' -> "_gt",
    '~' -> "_tilde",
    ':' -> "_colon",
    '@' -> "_at",
    '#' -> "_hash"
  )

  def encodeSymbolic(name: String): String =
    val sb = new StringBuilder(name.length * 2)
    name.foreach { ch =>
      symbolEncoding.get(ch) match
        case Some(replacement) => sb.append(replacement)
        case None => sb.append(ch)
    }
    sb.toString

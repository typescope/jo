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

  /** Compile a complete set of namespaces to a JavaScript program */
  def compile(nss: List[Namespace]): JS.Program =
    workList.add(runtime.start)

    val funDefMap = mutable.Map.empty[Symbol, FunDef]
    val classDefMap = mutable.Map.empty[Symbol, ClassDef]

    for
      ns <- nss
      defn <- ns
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
    runtime.paramIds.foreach { (fullName, globalId) =>
      initStatements += JS.VarDecl("const", globalId, JS.StringLit(fullName))
    }

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

    // Object accessors should not be reachable - they're replaced with Class._instance
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
      val (stats, expr) = compileExpr(fdef.body)
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

    // Add static _instance field if this is a singleton object
    val staticFields =
      if classSym.is(Flags.Object) then
        JS.Assign("_instance", JS.New(jsClassName, Nil)) :: Nil
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
    val (stats, expr) = compileExpr(word)
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
        val (rhsStats, rhsExpr) = compileExpr(rhs)
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

        val (rhsStats, rhsExpr) = compileExpr(rhs)
        val (qualStats, qualExpr) = compileExpr(qual)

        if rhsStats.isEmpty && qualStats.isEmpty then
          JS.FieldAssign(qualExpr, memberName, rhsExpr)

        else
          JS.Block((qualStats ++ rhsStats) :+ JS.FieldAssign(qualExpr, memberName, rhsExpr))

      case While(cond, body) =>
        // While loop: condition in expression position, body in statement position
        val (condStats, condExpr) = compileExpr(cond)
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
        val (condStats, condExpr) = compileExpr(cond)  // condition is expression
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
        val (stats, expr) = compileCall(fun, args ++ autos)
        JS.Block(stats :+ JS.ExprStat(expr))

      case _: TypeDef =>
        JS.Block(Nil)  // Empty block

      case _ =>
        // For other expression-like constructs, compile as expression and discard value
        val (stats, expr) = compileExpr(word)
        JS.Block(stats :+ JS.ExprStat(expr))

  /** Compile in expression position (value needed, lift statements) */
  private def compileExpr(word: Word)(using UniqueName): (List[JS.Stat], JS.Expr) =
    word match
      case Block(words) =>
        // In expression position: all but last are STATEMENTS, last is EXPRESSION
        (words: @unchecked) match
          case Nil =>
            (Nil, JS.UndefinedLit)
          case init :+ last =>
            val stats = init.map(compileStat)
            val (finalStats, finalExpr) = compileExpr(last)
            (stats ++ finalStats, finalExpr)

      case If(cond, thenp, elsep) =>
        // In expression position, branches are also expressions
        // Warning: Only lift from CONDITION, NOT from branches!

        // 1. Lift statements from the condition
        val (condStats, condExpr) = compileExpr(cond)

        // 2. Recursively process both branches (but keep them inside!)
        val (thenStats, thenExpr) = compileExpr(thenp)
        val (elseStats, elseExpr) = compileExpr(elsep)

        if thenStats.isEmpty && elseStats.isEmpty then
          // Both branches are simple (no statements)
          // → Use ternary conditional!
          (condStats, JS.Conditional(condExpr, thenExpr, elseExpr))
        else
          // At least one branch has statements
          // → Must use if-statement with temp variable
          // Branch statements stay INSIDE the if-statement blocks
          val tempName = freshTemp()
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

          val varDecl = JS.VarDecl("let", tempName, JS.UndefinedLit)
          val ifStmt = JS.IfStat(condExpr, thenBlock, elseBlock)
          (condStats :+ varDecl :+ ifStmt, JS.Ident(tempName))

      case Literal(c) =>
        (Nil, compileLiteral(c))

      case Ident(sym) =>
        (Nil, JS.Ident(jsName(sym)))

      case Select(qual, name) =>
        word.tpe match
          case Types.MemberRef(_, sym) =>
            val (qualStats, qualExpr) = compileExpr(qual)
            val memberName = jsMemberName(sym)
            (qualStats, JS.Select(qualExpr, memberName))

          case _ => throw new Exception("Unexpected select: " + word.show)

      case encoded @ Encoded(repr) =>
        if encoded.isValueDrop then
          throw new Exception("Unexpected value drop in expression position: " + encoded.show)

        else if encoded.tpe.isLambdaType && repr.tpe.isClassType then
          // Wrap class instance as lambda
          val (objStats, objExpr) = compileExpr(repr)
          // (...args) => obj.apply(...args)
          val lambdaBody = JS.Call(Some(objExpr), "apply", List(JS.RawCode("...args")))
          val lambdaExpr = JS.Arrow(List("...args"), lambdaBody)
          (objStats, lambdaExpr)

        else
          compileExpr(repr)

      case Apply(Select(New(classType), _), args, autos) =>
        // Object construction
        val classSym = classType.tpe.asClassInfo.classSymbol
        val allArgs = args ++ autos
        val (argStats, argExprs) = compileExprList(allArgs)
        (argStats, JS.New(jsName(classSym), argExprs))

      case Apply(TypeApply(Ident(sym), tpt :: Nil), arg :: Nil, Nil) if sym == defn.Internal_typeTest =>
        // Type test for union types
        val classInfo = tpt.tpe.asClassInfo
        val cls = classInfo.classSymbol
        val (argStats, argExpr) = compileExpr(arg)

        val test =
          if cls == defn.String_String then
            val cond1 = JS.BinOp(JS.UnaryOp("typeof", argExpr), "==", JS.StringLit("string"))
            JS.BinOp(cond1, "||", JS.InstanceOf(argExpr, "String"))

          else if cls == defn.Float_Float || cls == defn.Int_Int || cls == defn.Byte_Byte || cls == defn.Char_Char then
            JS.BinOp(JS.UnaryOp("typeof", argExpr), "==", JS.StringLit("number"))

          else
            JS.InstanceOf(argExpr, jsName(cls))

        (argStats, test)

      case Apply(fun, args, autos) =>
        compileCall(fun, args ++ autos)

      case TypeApply(fun, _) =>
        // Strip type application (JavaScript doesn't have generics)
        compileExpr(fun)

      case _: TypeDef =>
        (Nil, JS.UndefinedLit)

      case _: Def | _: With | _: Allow | _: Match |
           _: New | _: IsExpr | _: CaseDef | _: Lambda | _: RecordLit |
           _: Assign | _: FieldAssign | _: While =>
        throw new Exception("Unexpected in expression position: " + word)

  /** Compile a literal constant */
  private def compileLiteral(c: Constant): JS.Expr =
    c match
      case Constant.Bool(b) => JS.BoolLit(b)
      case Constant.String(s) => JS.StringLit(s)
      case Constant.Int(n) => JS.IntLit(n)
      case Constant.Float(d) => JS.FloatLit(d)

  /** Compile a list of expressions, collecting all lifted statements */
  private def compileExprList(words: List[Word])(using UniqueName): (List[JS.Stat], List[JS.Expr]) =
    val stats = mutable.ArrayBuffer.empty[JS.Stat]
    val exprs = mutable.ArrayBuffer.empty[JS.Expr]
    for word <- words do
      val (wordStats, wordExpr) = compileExpr(word)
      stats ++= wordStats
      exprs += wordExpr
    (stats.toList, exprs.toList)

  /** Compile a function/method call */
  private def compileCall(fun: Word, args: List[Word])(using UniqueName): (List[JS.Stat], JS.Expr) =
    fun match
      case Encoded(f) if f.tpe.isLambdaType =>
        // Lambda call
        val (funStats, funExpr) = compileExpr(f)
        val (argStats, argExprs) = compileExprList(args)
        (funStats ++ argStats, JS.Call(None, "", argExprs) match
          case call => call.copy(receiver = Some(funExpr)))

      case Ident(sym) =>
        if sym.is(Flags.Object) then
          // Object accessor: replace call with Class._instance
          val funType = sym.info.asProcType
          val classInfo = funType.resultType.asClassInfo
          val classSym = classInfo.classSymbol

          // Mark the class as reachable - it will get a static _instance field
          val className = jsName(classSym)
          (Nil, JS.Select(JS.Ident(className), "_instance"))

        else if sym == defn.Internal_abort then
          // abort(msg) => throw new Error(msg)
          // Since abort has type Bottom and never returns, we generate a Throw statement
          val msg :: Nil = args : @unchecked
          val (msgStats, msgExpr) = compileExpr(msg)
          val throwStmt = JS.Throw(JS.New("Error", List(msgExpr)))
          // Return the throw as a statement with a dummy expression (never reached)
          (msgStats :+ throwStmt, JS.NullLit)

        else if sym == runtime.paramSymbol then
          // paramSymbol(paramIdent) => globalParamId
          // This is called by LowerContextParams to get the parameter key
          val Ident(paramSym) :: Nil = args : @unchecked
          val globalId = runtime.getOrCreateParamId(paramSym)
          (Nil, JS.Ident(globalId))

        else if sym.owner == defn.Bool then
          compileBoolPrimitive(sym, args)

        else if sym == runtime.js then
          // Raw JavaScript code
          val Literal(Constant.String(code)) :: Nil = args : @unchecked
          (Nil, JS.RawCode(code))

        else
          val (argStats, argExprs) = compileExprList(args)
          (argStats, JS.Call(None, jsName(sym), argExprs))

      case Select(qual, name) if qual.tpe.isSubtype(defn.IntType) =>
        compileIntPrimitive(name, qual, args)

      case Select(qual, name) if qual.tpe.isSubtype(defn.ByteType) =>
        compileBytePrimitive(name, qual, args)

      case Select(qual, name) if qual.tpe.isSubtype(defn.CharType) =>
        compileCharPrimitive(name, qual, args)

      case Select(qual, name) if qual.tpe.isSubtype(defn.FloatType) =>
        compileFloatPrimitive(name, qual, args)

      case Select(qual, name) if qual.tpe.isSubtype(defn.StringType) =>
        compileStringPrimitive(name, qual, args)

      case Select(qual, name) =>
        // Regular method/function call on an object
        val (qualStats, qualExpr) = compileExpr(qual)
        val memberName = fun.tpe match
          case Types.MemberRef(_, sym) => jsMemberName(sym)
          case _ => name
        val (argStats, argExprs) = compileExprList(args)
        (qualStats ++ argStats, JS.Call(Some(qualExpr), memberName, argExprs))

      case TypeApply(fun2, _) =>
        // Strip type application and recurse
        compileCall(fun2, args)

      case Encoded(repr) =>
        // Strip encoding and recurse
        compileCall(repr, args)

      case _ =>
        throw new Exception("Unexpected function in call: " + fun)

  /** Compile Bool primitive operations */
  private def compileBoolPrimitive(sym: Symbol, args: List[Word])(using UniqueName): (List[JS.Stat], JS.Expr) =
    sym match
      case defn.Bool_both =>
        val a :: b :: Nil = args: @unchecked
        val (aStats, aExpr) = compileExpr(a)
        val (bStats, bExpr) = compileExpr(b)
        (aStats ++ bStats, JS.BinOp(aExpr, "&&", bExpr))

      case defn.Bool_either =>
        val a :: b :: Nil = args: @unchecked
        val (aStats, aExpr) = compileExpr(a)
        val (bStats, bExpr) = compileExpr(b)
        (aStats ++ bStats, JS.BinOp(aExpr, "||", bExpr))

      case defn.Bool_not =>
        val operand :: Nil = args: @unchecked
        val (stats, expr) = compileExpr(operand)
        (stats, JS.UnaryOp("!", expr))

      case _ =>
        val (argStats, argExprs) = compileExprList(args)
        (argStats, JS.Call(None, jsName(sym), argExprs))

  /** Compile Int primitive operations */
  private def compileIntPrimitive(name: String, qual: Word, args: List[Word])(using UniqueName): (List[JS.Stat], JS.Expr) =
    name match
      case "+" | "-" | "*" | "%" | "===" | "!==" | "<" | ">" | "<=" | ">=" | "&" | "|" | "^" | "<<" | ">>" =>
        val arg :: Nil = args: @unchecked
        val (qualStats, qualExpr) = compileExpr(qual)
        val (argStats, argExpr) = compileExpr(arg)
        (qualStats ++ argStats, JS.BinOp(qualExpr, name, argExpr))

      case "==" =>
        val arg :: Nil = args: @unchecked
        val (qualStats, qualExpr) = compileExpr(qual)
        val (argStats, argExpr) = compileExpr(arg)
        (qualStats ++ argStats, JS.BinOp(qualExpr, "===", argExpr))

      case "!=" =>
        val arg :: Nil = args: @unchecked
        val (qualStats, qualExpr) = compileExpr(qual)
        val (argStats, argExpr) = compileExpr(arg)
        (qualStats ++ argStats, JS.BinOp(qualExpr, "!==", argExpr))

      case "/" =>
        // Integer division in JavaScript requires Math.floor
        val arg :: Nil = args: @unchecked
        val (qualStats, qualExpr) = compileExpr(qual)
        val (argStats, argExpr) = compileExpr(arg)
        val divExpr = JS.BinOp(qualExpr, "/", argExpr)
        (qualStats ++ argStats, JS.Call(Some(JS.Ident("Math")), "floor", List(divExpr)))

      case "toFloat" =>
        val (stats, expr) = compileExpr(qual)
        (stats, expr)  // JavaScript numbers are already floats

      case "toByte" =>
        val (stats, expr) = compileExpr(qual)
        (stats, JS.BinOp(expr, "&", JS.IntLit(0xFF)))

      case "toChar" =>
        // Char is represented as Int (Unicode code point) in JavaScript, so this is a no-op
        compileExpr(qual)

      case "toString" =>
        val (stats, expr) = compileExpr(qual)
        (stats, JS.Call(Some(expr), "toString", Nil))

      case _ =>
        throw new Exception(s"Unknown Int method: $name")

  /** Compile Byte primitive operations */
  private def compileBytePrimitive(name: String, qual: Word, args: List[Word])(using UniqueName): (List[JS.Stat], JS.Expr) =
    name match
      case "toInt" =>
        // Byte is already represented as number in JavaScript
        compileExpr(qual)

      case "toChar" =>
        // Both Byte and Char are number in JavaScript
        compileExpr(qual)

      case _ =>
        // All other Byte operations are the same as Int operations
        compileIntPrimitive(name, qual, args)

  /** Compile Char primitive operations */
  private def compileCharPrimitive(name: String, qual: Word, args: List[Word])(using UniqueName): (List[JS.Stat], JS.Expr) =
    name match
      case "===" | "!==" | "<" | ">" | "<=" | ">=" =>
        val arg :: Nil = args: @unchecked
        val (qualStats, qualExpr) = compileExpr(qual)
        val (argStats, argExpr) = compileExpr(arg)
        (qualStats ++ argStats, JS.BinOp(qualExpr, name, argExpr))

      case "==" =>
        val arg :: Nil = args: @unchecked
        val (qualStats, qualExpr) = compileExpr(qual)
        val (argStats, argExpr) = compileExpr(arg)
        (qualStats ++ argStats, JS.BinOp(qualExpr, "===", argExpr))

      case "!=" =>
        val arg :: Nil = args: @unchecked
        val (qualStats, qualExpr) = compileExpr(qual)
        val (argStats, argExpr) = compileExpr(arg)
        (qualStats ++ argStats, JS.BinOp(qualExpr, "!==", argExpr))

      case "toByte" =>
        // Char is already represented as Int in JavaScript
        val (stats, expr) = compileExpr(qual)
        (stats, JS.BinOp(expr, "&", JS.IntLit(0xFF)))

      case "toInt" =>
        // Char is already represented as Int (Unicode code point) in JavaScript
        compileExpr(qual)

      case "toString" =>
        // Use String.fromCodePoint to convert Unicode code point to string
        val (stats, expr) = compileExpr(qual)
        (stats, JS.Call(Some(JS.Ident("String")), "fromCodePoint", List(expr)))

      case _ =>
        throw new Exception(s"Unknown Char method: $name")

  /** Compile Float primitive operations */
  private def compileFloatPrimitive(name: String, qual: Word, args: List[Word])(using UniqueName): (List[JS.Stat], JS.Expr) =
    name match
      case "+" | "-" | "*" | "/" | ">" | "<" | ">=" | "<=" | "===" | "!==" =>
        val arg :: Nil = args: @unchecked
        val (qualStats, qualExpr) = compileExpr(qual)
        val (argStats, argExpr) = compileExpr(arg)
        (qualStats ++ argStats, JS.BinOp(qualExpr, name, argExpr))

      case "==" =>
        val arg :: Nil = args: @unchecked
        val (qualStats, qualExpr) = compileExpr(qual)
        val (argStats, argExpr) = compileExpr(arg)
        (qualStats ++ argStats, JS.BinOp(qualExpr, "===", argExpr))

      case "!=" =>
        val arg :: Nil = args: @unchecked
        val (qualStats, qualExpr) = compileExpr(qual)
        val (argStats, argExpr) = compileExpr(arg)
        (qualStats ++ argStats, JS.BinOp(qualExpr, "!==", argExpr))

      case "toInt" =>
        val (stats, expr) = compileExpr(qual)
        (stats, JS.Call(Some(JS.Ident("Math")), "floor", List(expr)))

      case "toString" =>
        val (stats, expr) = compileExpr(qual)
        (stats, JS.Call(Some(expr), "toString", Nil))

      case _ =>
        throw new Exception(s"Unknown Float method: $name")

  /** Compile String primitive operations */
  private def compileStringPrimitive(name: String, qual: Word, args: List[Word])(using UniqueName): (List[JS.Stat], JS.Expr) =
    name match
      case "+" =>
        val arg :: Nil = args: @unchecked
        val (qualStats, qualExpr) = compileExpr(qual)
        val (argStats, argExpr) = compileExpr(arg)
        (qualStats ++ argStats, JS.BinOp(qualExpr, "+", argExpr))

      case "===" =>
        val arg :: Nil = args: @unchecked
        val (qualStats, qualExpr) = compileExpr(qual)
        val (argStats, argExpr) = compileExpr(arg)
        (qualStats ++ argStats, JS.BinOp(qualExpr, "===", argExpr))

      case "==" =>
        val arg :: Nil = args: @unchecked
        val (qualStats, qualExpr) = compileExpr(qual)
        val (argStats, argExpr) = compileExpr(arg)
        (qualStats ++ argStats, JS.BinOp(qualExpr, "===", argExpr))

      case "!=" | "!==" =>
        val arg :: Nil = args: @unchecked
        val (qualStats, qualExpr) = compileExpr(qual)
        val (argStats, argExpr) = compileExpr(arg)
        (qualStats ++ argStats, JS.BinOp(qualExpr, "!==", argExpr))

      case "size" =>
        val (stats, expr) = compileExpr(qual)
        (stats, JS.Select(expr, "length"))

      case "get" =>
        val index :: Nil = args: @unchecked
        val (qualStats, qualExpr) = compileExpr(qual)
        val (indexStats, indexExpr) = compileExpr(index)
        // s.charCodeAt(index) - get character code point at index
        val charAtExpr = JS.Call(Some(qualExpr), "charCodeAt", List(indexExpr))
        (qualStats ++ indexStats, charAtExpr)

      case "substring" | "slice" =>
        val index :: len :: Nil = args: @unchecked
        val (qualStats, qualExpr) = compileExpr(qual)
        val (indexStats, indexExpr) = compileExpr(index)
        val (lenStats, lenExpr) = compileExpr(len)
        // s.substring(index, index+len) - JavaScript substring syntax
        val endExpr = JS.BinOp(indexExpr, "+", lenExpr)
        val substringExpr = JS.Call(Some(qualExpr), "substring", List(indexExpr, endExpr))
        (qualStats ++ indexStats ++ lenStats, substringExpr)

      case _ =>
        throw new Exception(s"Unknown String method: $name")

  /** Generate JavaScript code from namespaces and write to output file */
  def generate(nss: List[Namespace], outFile: String): Unit =
    val program = this.compile(nss)

    val pw = new java.io.PrintWriter(outFile)
    Printer.print(program, pw)
    pw.flush()
    pw.close()

end JSCodeGen

object JSCodeGen:
  def encodeSymbolic(name: String): String =
    // Replace special characters with JavaScript-safe alternatives
    var result = name
    result = result.replace("$", "$D")       // $ → $D (Dollar)
    result = result.replace(".", "_")        // . → _
    result = result.replace("+", "$plus")
    result = result.replace("-", "$minus")
    result = result.replace("*", "$times")
    result = result.replace("/", "$div")
    result = result.replace("%", "$mod")
    result = result.replace("&", "$and")
    result = result.replace("|", "$or")
    result = result.replace("^", "$xor")
    result = result.replace("!", "$bang")
    result = result.replace("?", "$qmark")
    result = result.replace("=", "$eq")
    result = result.replace("<", "$lt")
    result = result.replace(">", "$gt")
    result = result.replace("~", "$tilde")
    result = result.replace(":", "$colon")
    result = result.replace("@", "$at")
    result = result.replace("#", "$hash")
    result

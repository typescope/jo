package python

import sast.*
import sast.Trees.*
import sast.Symbols.Symbol
import sast.Types

import python.Trees as P

import common.UniqueName
import common.WorkList

import scala.collection.mutable

/** Code generator that translates Jo SAST to Python AST
  *
  */
class PythonCodeGen(runtime: PythonRuntime, rewire: Map[Symbol, Symbol])(using defn: Definitions):

  //----------------------------------------------------------------------------
  // Name management
  //----------------------------------------------------------------------------

  private val reservedNames = new UniqueName(separator = "")

  val keywords = List(
    // Python keywords
    "for", "while", "def", "class", "if", "else", "elif",
    "break", "continue", "return", "yield", "pass",
    "try", "except", "finally", "raise", "assert",
    "import", "from", "as", "global", "nonlocal",
    "lambda", "with", "in", "is", "not", "and", "or",
    "self", "__init__",
    "True", "False", "None", "async", "await",
    // Python built-in functions (from https://docs.python.org/3/library/functions.html)
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

  // Make keywords unavailable
  for word <- keywords do reservedNames.freshName(word)

  // Make runtime symbols unavailable
  for name <- runtime.runtimeNames do reservedNames.freshName(name)

  private val symbol2UniqueName: mutable.Map[Symbol, String] = mutable.Map.empty

  val globalScope = reservedNames.newScope(separator = "")

  def pythonMemberName(sym: Symbol): String =
    assert(sym.isOneOf(Flags.Method | Flags.Field), "Not a method, sym = " + sym)

    symbol2UniqueName.get(sym) match
      case Some(name) => name

      case _ =>
        val rawName = PythonCodeGen.encodeSymbolic(sym.name)
        val scope = reservedNames.newScope("_")
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

    // Build the program: combine all initialization with the main call
    val globalInit = P.Assign("_runtime_contextParams", P.RawCode("{}")) ::
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
  private def compileFunction(fdef: FunDef): P.FunDef =
    val sym = fdef.symbol

    // Regular function - create new scope for local variables
    given UniqueName = reservedNames.newScope(separator = "")

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

  /** Compile a class definition */
  private def compileClass(cdef: ClassDef)(using scope: UniqueName): P.ClassDef =
    val classSym = cdef.symbol
    val pythonClassName = pythonName(classSym)

    symbol2UniqueName(cdef.self) = "self"

    // Get all fields from the class definition
    val fieldNames = cdef.vals.map(pythonMemberName)

    // Compile methods - each method gets compiled with its own scope
    // Include __init__ in the methods list (it will be renamed in compileFunction)
    val methods = cdef.funs.map(compileFunction)

    P.ClassDef(
      name = pythonClassName,
      fields = fieldNames,
      methods = methods
    )

  /** Compile function body (adds Return statement unless it ends with Raise) */
  private def compileFunctionBody(word: Word)(using UniqueName): P.Block =
    val (stats, expr) = compileExpr(word, enforcePurity = false)
    // If the last statement is a Raise, don't add Return (raise never returns)
    stats.lastOption match
      case Some(_: P.Raise) =>
        // Invariant: if statements end with Raise, expr must be NoneLit (never reached)
        assert(expr == P.NoneLit, s"Expected NoneLit after Raise, got: $expr")
        P.Block(stats)

      case _ => P.Block(stats :+ P.Return(expr))

  /** Helper for fresh temporary variable names */
  private def freshTemp()(using scope: UniqueName): String =
    scope.freshName("_temp")

  /** Compile in statement position (no value needed) */
  private def compileStat(word: Word)(using UniqueName): P.Stat =
    word match
      case Block(words) =>
        // In statement position, all words become statements
        P.Block(words.map(compileStat))

      case Assign(Ident(sym), rhs) =>
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
        val bodyStat = compileStat(body)
        if condStats.isEmpty then
          P.While(condExpr, bodyStat)
        else
          // Need to put condition inside while body
          val break = P.IfStat(P.UnaryOp("not", condExpr), P.Break, P.Block(Nil))
          val body = P.Block(condStats :+ break :+ bodyStat)
          P.While(P.BoolLit(true), body)

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

      case _: TypeDef =>
        P.Block(Nil)  // Empty block

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
  private def compileExpr(word: Word, enforcePurity: Boolean)(using UniqueName): (List[P.Stat], P.Expr) =
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
          // If a branch ends with Raise, don't add assignment (raise never returns)
          val thenBlock = thenStats.lastOption match
            case Some(_: P.Raise) =>
              // Invariant: if statements end with Raise, expr must be NoneLit (never reached)
              assert(thenExpr == P.NoneLit, s"Expected NoneLit after Raise in then branch, got: $thenExpr")
              P.Block(thenStats)

            case _ => P.Block(thenStats :+ P.Assign(tempName, thenExpr))

          val elseBlock = elseStats.lastOption match
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
        if enforcePurity && sym.isMutable then
          // Mutable variable reads are impure - wrap in temp
          val tempName = freshTemp()
          (List(P.Assign(tempName, P.Ident(pythonName(sym)))), P.Ident(tempName))

        else
          (Nil, P.Ident(pythonName(sym)))

      case Select(qual, name) =>
        word.tpe match
          case Types.MemberRef(_, sym) =>
            val (qualStats, qualExpr) = compileExpr(qual, enforcePurity)
            val memberName = pythonMemberName(sym)
            (qualStats, P.Select(qualExpr, memberName))

          case _ => throw new Exception("Unexpected select: " + word.show)

      case encoded @ Encoded(repr) =>
        if encoded.isValueDrop then
          throw new Exception("Unexpected value drop in expression position: " + encoded.show)

        else if encoded.tpe.isLambdaType && repr.tpe.isClassType then
          // Wrap class instance as lambda
          val (objStats, objExpr) = compileExpr(repr, enforcePurity = false)  // lambda body is OK
          // lambda *args: obj.apply(*args)
          // Lambda creation is pure - just creates a function object
          val lambdaBody = P.Call(Some(objExpr), "apply", List(P.RawCode("*args")))
          (objStats, P.Lambda(List("*args"), lambdaBody))

        else
          // Pass through enforcePurity
          compileExpr(repr, enforcePurity)

      case Apply(Select(New(classType), _), args, autos) =>
        // Object construction - always impure, wrap if purity needed
        val classSym = classType.tpe.asClassInfo.classSymbol
        val allArgs = args ++ autos
        val (argStats, argExprs) = compileExprList(allArgs, enforcePurity = false)
        val newExpr = P.New(pythonName(classSym), argExprs)
        if enforcePurity then
          val tempName = freshTemp()
          (argStats :+ P.Assign(tempName, newExpr), P.Ident(tempName))
        else
          (argStats, newExpr)

      case Apply(TypeApply(Ident(sym), tpt :: Nil), arg :: Nil, Nil) if sym == defn.Internal_typeTest =>
        // Type test for union types - this is pure
        val classInfo = tpt.tpe.asClassInfo
        val cls = classInfo.classSymbol
        val (argStats, argExpr) = compileExpr(arg, enforcePurity)

        val className =
          if cls == defn.String_type then "str"
          else if cls == defn.Float_type then "float"
          else if cls == defn.Int_type || cls == defn.Byte_type || cls == defn.Char_type then "int"
          else if cls == defn.Bool_type then "bool"
          else pythonName(cls)

        // Type test is pure if argxpr is pure
        (argStats, P.InstanceOf(argExpr, className))

      case Apply(fun, args, autos) =>
        // Function/method calls are generally impure, wrap if purity needed
        compileCall(fun, args ++ autos, enforcePurity)

      case _: TypeDef =>
        // Type definitions are pure (erased)
        (Nil, P.NoneLit)

      case _: Def | _: With | _: Allow | _: Match |
           _: New | _: IsExpr | _: CaseDef | _: Lambda | _: RecordLit |
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
  private def compileExprList(words: List[Word], enforcePurity: Boolean)(using UniqueName): (List[P.Stat], List[P.Expr]) =
    var stats: List[P.Stat] = Nil
    var exprs: List[P.Expr] = Nil

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
  private def compileTwoArgs(lhs: Word, rhs: Word, enforcePurity: Boolean)(using UniqueName): (List[P.Stat], P.Expr, P.Expr) =
    val (statsRhs, exprRhs) = compileExpr(rhs, enforcePurity)
    val (statsLhs, exprLhs) = compileExpr(lhs, enforcePurity || statsRhs.nonEmpty)
    (statsLhs ++ statsRhs, exprLhs, exprRhs)

  /** Compile a function/method call */
  private def compileCall(fun: Word, args: List[Word], enforcePurity: Boolean)(using UniqueName): (List[P.Stat], P.Expr) =
    fun match
      case Ident(sym) =>
        if sym.is(Flags.Object) then
          // direct singleton object access
          val funType = sym.info.asProcType
          val classInfo = funType.resultType.asClassInfo
          val classSym = classInfo.classSymbol

          // Mark class reachable
          pythonName(classSym)

          // Get or create the global singleton variable name
          val singletonVar = runtime.getOrCreateSingletonId(classSym)
          (Nil, P.Ident(singletonVar))

        else if sym == defn.Internal_abort then
          // abort(msg) => raise Exception(msg)
          // Since abort has type Bottom and never returns, we generate a Raise statement
          val msg :: Nil = args : @unchecked
          val (msgStats, msgExpr) = compileExpr(msg, enforcePurity = false)
          val raiseStmt = P.Raise(P.New("Exception", List(msgExpr)))
          // Return the raise as a statement with a dummy expression (never reached)
          (msgStats :+ raiseStmt, P.NoneLit)

        else if sym == runtime.python then
          // Raw Python code
          val Literal(Constant.String(code)) :: Nil = args : @unchecked

          if enforcePurity then
            val tempName = freshTemp()
            (P.Assign(tempName, P.RawCode(code)) :: Nil, P.Ident(tempName))

          else
            (Nil, P.RawCode(code))

        else if sym == runtime.paramKey then
          val paramSym = args.head match
            case Ident(paramSym) => paramSym
            case Literal(Constant.String(path)) => defn.resolveTerm(path) // special support for entry method
            case word => throw new Exception("Unsupported argument to paramKey: " + word)

          val keyId = runtime.getOrCreateParamId(paramSym)
          (Nil, P.Ident(keyId))

        else if sym == defn.jo_pass then
          (Nil, P.NoneLit)

        else
          val (argStats, argExprs) = compileExprList(args, enforcePurity = false)
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

      case Select(qual, name) =>
        // Regular method/function call on an object
        // Treat qualifier + args together to enforce proper evaluation order
        val memberName = fun.tpe match
          case Types.MemberRef(_, sym) => pythonMemberName(sym)
          case _ => throw new Exception("Unexpected select: " + fun.show)

        val (stats, qualExpr :: argExprs) = compileExprList(qual :: args, enforcePurity = false): @unchecked
        val call = P.Call(Some(qualExpr), memberName, argExprs)

        if enforcePurity then
          val tempName = freshTemp()
          (stats :+ P.Assign(tempName, call), P.Ident(tempName))
        else
          (stats, call)

      case TypeApply(fun2, _) =>
        // Strip type application and recurse
        compileCall(fun2, args, enforcePurity)

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

      case Encoded(repr) =>
        // Strip encoding and recurse
        compileCall(repr, args, enforcePurity)

      case _ =>
        throw new Exception("Unexpected function in call: " + fun)

  /** Compile Bool class method operations (&&, ||, ==, !=, ~!, toString) */
  private def compileBoolPrimitive(name: String, qual: Word, args: List[Word], enforcePurity: Boolean)(using UniqueName): (List[P.Stat], P.Expr) =
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

  private def compileIntPrimitive(name: String, qual: Word, args: List[Word], enforcePurity: Boolean)(using UniqueName): (List[P.Stat], P.Expr) =
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
  private def compileBytePrimitive(name: String, qual: Word, args: List[Word], enforcePurity: Boolean)(using UniqueName): (List[P.Stat], P.Expr) =
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
  private def compileCharPrimitive(name: String, qual: Word, args: List[Word], enforcePurity: Boolean)(using UniqueName): (List[P.Stat], P.Expr) =
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
  private def compileFloatPrimitive(name: String, qual: Word, args: List[Word], enforcePurity: Boolean)(using UniqueName): (List[P.Stat], P.Expr) =
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
  private def compileStringPrimitive(name: String, qual: Word, args: List[Word], enforcePurity: Boolean)(using UniqueName): (List[P.Stat], P.Expr) =
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

      case "substring" | "slice" =>
        val index :: len :: Nil = args: @unchecked
        val (stats, exprs) = compileExprList(List(qual, index, len), enforcePurity)
        val qualExpr :: indexExpr :: lenExpr :: Nil = exprs: @unchecked
        // s[index:index+len] - Python slice syntax
        val endExpr = P.BinOp(indexExpr, "+", lenExpr)
        (stats, P.Index(qualExpr, P.Slice(indexExpr, endExpr)))

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

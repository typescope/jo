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
  * This follows the Ruby backend pattern but uses separate statement/expression
  * methods to enforce Python's semantics.
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

  /** Compile a complete set of namespaces to a Python program */
  def compile(nss: List[Namespace]): P.Program =
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
      // _singleton_jo_Predef_Unit = jo_Predef_Unit1()
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

    // Check if this is an object accessor
    if sym.is(Flags.Object) then
      val funType = sym.info.asProcType
      val classInfo = funType.resultType.asClassInfo
      val classSym = classInfo.classSymbol
      val name = pythonName(sym)

      // Mark the class as reachable
      workList.add(classSym)

      // Get or create the global singleton variable name
      val singletonVar = runtime.getOrCreateSingletonId(classSym)

      // Generate: def name(): return _singleton_ClassName
      return P.FunDef(name, Nil, P.Block(List(P.Return(P.Ident(singletonVar)))))

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
      val (stats, expr) = compileExpr(fdef.body)
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

  /** Compile function body (adds Return statement) */
  private def compileFunctionBody(word: Word)(using UniqueName): P.Block =
    val (stats, expr) = compileExpr(word)
    P.Block(stats :+ P.Return(expr))

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
        val (rhsStats, rhsExpr) = compileExpr(rhs)
        if rhsStats.isEmpty then
          P.Assign(pythonName(sym), rhsExpr)
        else
          P.Block(rhsStats :+ P.Assign(pythonName(sym), rhsExpr))

      case FieldAssign(lhs @ Select(qual, _), rhs) =>
        val memberName = lhs.tpe match
          case Types.MemberRef(_, sym) => pythonMemberName(sym)
          case _ => throw new Exception("Unexpected lhs of assign: " + lhs.show)

        val (rhsStats, rhsExpr) = compileExpr(rhs)
        val (qualStats, qualExpr) = compileExpr(qual)

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
        val (condStats, condExpr) = compileExpr(cond)
        val bodyStat = compileStat(body)
        if condStats.isEmpty then
          P.While(condExpr, bodyStat)
        else
          // Need to lift condition statements - wrap in block with while loop
          P.Block(condStats :+ P.While(condExpr, bodyStat))

      case If(cond, thenp, elsep) =>
        // In statement position, use IfStat directly
        val (condStats, condExpr) = compileExpr(cond)  // condition is expression
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
        val (stats, expr) = compileCall(fun, args ++ autos)
        P.Block(stats :+ P.ExprStat(expr))

      case _: TypeDef =>
        P.Block(Nil)  // Empty block

      case _ =>
        // For other expression-like constructs, compile as expression and discard value
        val (stats, expr) = compileExpr(word)
        P.Block(stats :+ P.ExprStat(expr))

  /** Compile in expression position (value needed, lift statements) */
  private def compileExpr(word: Word)(using UniqueName): (List[P.Stat], P.Expr) =
    word match
      case Block(words) =>
        // In expression position: all but last are STATEMENTS, last is EXPRESSION
        (words: @unchecked) match
          case Nil =>
            (Nil, P.NoneLit)
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
          // → Use ternary if-expression!
          (condStats, P.IfExpr(condExpr, thenExpr, elseExpr))
        else
          // At least one branch has statements
          // → Must use if-statement with temp variable
          // Branch statements stay INSIDE the if-statement blocks
          val tempName = freshTemp()
          val thenBlock = P.Block(thenStats :+ P.Assign(tempName, thenExpr))
          val elseBlock = P.Block(elseStats :+ P.Assign(tempName, elseExpr))
          val ifStmt = P.IfStat(condExpr, thenBlock, elseBlock)
          (condStats :+ ifStmt, P.Ident(tempName))

      case Literal(c) =>
        (Nil, compileLiteral(c))

      case Ident(sym) =>
        assert(!sym.is(Flags.Context), "Unexpected context parameter")
        (Nil, P.Ident(pythonName(sym)))

      case Select(qual, name) =>
        word.tpe match
          case Types.MemberRef(_, sym) =>
            val (qualStats, qualExpr) = compileExpr(qual)
            val memberName = pythonMemberName(sym)
            (qualStats, P.Select(qualExpr, memberName))

          case _ => throw new Exception("Unexpected select: " + word.show)

      case encoded @ Encoded(repr) =>
        if encoded.isValueDrop then
          throw new Exception("Unexpected value drop in expression position: " + encoded.show)

        else if encoded.tpe.isLambdaType && repr.tpe.isClassType then
          // Wrap class instance as lambda
          val (objStats, objExpr) = compileExpr(repr)
          // lambda *args: obj.apply(*args)
          val lambdaBody = P.Call(Some(objExpr), "apply", List(P.RawCode("*args")))
          val lambdaExpr = P.Lambda(List("*args"), lambdaBody)
          (objStats, lambdaExpr)

        else
          compileExpr(repr)

      case Apply(Select(New(classType), _), args, autos) =>
        // Object construction
        val classSym = classType.tpe.asClassInfo.classSymbol
        val allArgs = args ++ autos
        val (argStats, argExprs) = compileExprList(allArgs)
        (argStats, P.New(pythonName(classSym), argExprs))

      case Apply(TypeApply(Ident(sym), tpt :: Nil), arg :: Nil, Nil) if sym == defn.Internal_typeTest =>
        // Type test for union types
        val classInfo = tpt.tpe.asClassInfo
        val cls = classInfo.classSymbol
        val (argStats, argExpr) = compileExpr(arg)

        val className =
          if cls == defn.String_String then "str"
          else if cls == defn.Float_Float then "float"
          else if cls == defn.Int_Int || cls == defn.Byte_Byte || cls == defn.Char_Char then "int"
          else if cls == defn.Bool_Bool then "bool"
          else pythonName(cls)

        (argStats, P.InstanceOf(argExpr, className))

      case Apply(fun, args, autos) =>
        compileCall(fun, args ++ autos)

      case TypeApply(fun, _) =>
        // Strip type application (Python doesn't have generics)
        compileExpr(fun)

      case _: TypeDef =>
        (Nil, P.NoneLit)

      case _: Def | _: With | _: Allow | _: Match |
           _: New | _: IsExpr | _: CaseDef | _: Lambda | _: RecordLit |
           _: Assign | _: FieldAssign | _: While =>
        throw new Exception("Unexpected in expression position: " + word)

  /** Compile a literal constant */
  private def compileLiteral(c: Constant): P.Expr =
    c match
      case Constant.Bool(b) => P.BoolLit(b)
      case Constant.String(s) => P.StringLit(s)
      case Constant.Int(n) => P.IntLit(n)
      case Constant.Float(d) => P.FloatLit(d)

  /** Compile a list of expressions, collecting all lifted statements */
  private def compileExprList(words: List[Word])(using UniqueName): (List[P.Stat], List[P.Expr]) =
    val stats = mutable.ArrayBuffer.empty[P.Stat]
    val exprs = mutable.ArrayBuffer.empty[P.Expr]
    for word <- words do
      val (wordStats, wordExpr) = compileExpr(word)
      stats ++= wordStats
      exprs += wordExpr
    (stats.toList, exprs.toList)

  /** Compile a function/method call */
  private def compileCall(fun: Word, args: List[Word])(using UniqueName): (List[P.Stat], P.Expr) =
    fun match
      case Encoded(f) if f.tpe.isLambdaType =>
        // Lambda call
        val (funStats, funExpr) = compileExpr(f)
        val (argStats, argExprs) = compileExprList(args)
        (funStats ++ argStats, P.LambdaCall(funExpr, argExprs))

      case Ident(sym) =>
        if sym.owner == defn.Bool then
          compileBoolPrimitive(sym, args)
        else if sym == runtime.python then
          // Raw Python code
          val Literal(Constant.String(code)) :: Nil = args : @unchecked
          (Nil, P.RawCode(code))
        else if sym == runtime.paramSymbol then
          // paramSymbol(paramIdent) => "param_key_string"
          // For Python, we use strings as context parameter keys
          val Ident(paramSym) :: Nil = args : @unchecked
          val keyId = runtime.getOrCreateParamId(paramSym)
          (Nil, P.Ident(keyId))
        else
          val (argStats, argExprs) = compileExprList(args)
          (argStats, P.Call(None, pythonName(sym), argExprs))

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
          case Types.MemberRef(_, sym) => pythonMemberName(sym)
          case _ => name
        val (argStats, argExprs) = compileExprList(args)
        (qualStats ++ argStats, P.Call(Some(qualExpr), memberName, argExprs))

      case TypeApply(fun2, _) =>
        // Strip type application and recurse
        compileCall(fun2, args)

      case Encoded(repr) =>
        // Strip encoding and recurse
        compileCall(repr, args)

      case _ =>
        throw new Exception("Unexpected function in call: " + fun)

  /** Compile Bool primitive operations */
  private def compileBoolPrimitive(sym: Symbol, args: List[Word])(using UniqueName): (List[P.Stat], P.Expr) =
    sym match
      case defn.Bool_both =>
        val a :: b :: Nil = args: @unchecked
        val (aStats, aExpr) = compileExpr(a)
        val (bStats, bExpr) = compileExpr(b)
        (aStats ++ bStats, P.BinOp(aExpr, "and", bExpr))

      case defn.Bool_either =>
        val a :: b :: Nil = args: @unchecked
        val (aStats, aExpr) = compileExpr(a)
        val (bStats, bExpr) = compileExpr(b)
        (aStats ++ bStats, P.BinOp(aExpr, "or", bExpr))

      case defn.Bool_not =>
        val operand :: Nil = args: @unchecked
        val (stats, expr) = compileExpr(operand)
        (stats, P.UnaryOp("not", expr))

      case _ =>
        val (argStats, argExprs) = compileExprList(args)
        (argStats, P.Call(None, pythonName(sym), argExprs))

  /** Compile Int primitive operations */
  private def compileIntPrimitive(name: String, qual: Word, args: List[Word])(using UniqueName): (List[P.Stat], P.Expr) =
    name match
      case "+" | "-" | "*" | "%" | "==" | "!=" | "<" | ">" | "<=" | ">=" | "&" | "|" | "^" | "<<" | ">>" =>
        val arg :: Nil = args: @unchecked
        val (qualStats, qualExpr) = compileExpr(qual)
        val (argStats, argExpr) = compileExpr(arg)
        (qualStats ++ argStats, P.BinOp(qualExpr, name, argExpr))

      case "/" =>
        // Integer division in Python requires //
        val arg :: Nil = args: @unchecked
        val (qualStats, qualExpr) = compileExpr(qual)
        val (argStats, argExpr) = compileExpr(arg)
        (qualStats ++ argStats, P.BinOp(qualExpr, "//", argExpr))

      case "toFloat" =>
        val (stats, expr) = compileExpr(qual)
        (stats, P.Call(None, "float", List(expr)))

      case "toByte" =>
        val (stats, expr) = compileExpr(qual)
        (stats, P.BinOp(expr, "&", P.IntLit(0xFF)))

      case "toChar" =>
        // Char is represented as Int (Unicode code point) in Python, so this is a no-op
        compileExpr(qual)

      case "toString" =>
        val (stats, expr) = compileExpr(qual)
        (stats, P.Call(None, "str", List(expr)))

      case _ =>
        throw new Exception(s"Unknown Int method: $name")

  /** Compile Byte primitive operations */
  private def compileBytePrimitive(name: String, qual: Word, args: List[Word])(using UniqueName): (List[P.Stat], P.Expr) =
    name match
      case "toInt" =>
        // Byte is already represented as int in Python
        compileExpr(qual)

      case "toChar" =>
        // Both Byte and Char are int in Python
        compileExpr(qual)

      case _ =>
        // All other Byte operations are the same as Int operations
        compileIntPrimitive(name, qual, args)

  /** Compile Char primitive operations */
  private def compileCharPrimitive(name: String, qual: Word, args: List[Word])(using UniqueName): (List[P.Stat], P.Expr) =
    name match
      case "==" | "!=" | "<" | ">" | "<=" | ">=" =>
        val arg :: Nil = args: @unchecked
        val (qualStats, qualExpr) = compileExpr(qual)
        val (argStats, argExpr) = compileExpr(arg)
        (qualStats ++ argStats, P.BinOp(qualExpr, name, argExpr))

      case "toByte" =>
        // Char is already represented as Int in Python
        val (stats, expr) = compileExpr(qual)
        (stats, P.BinOp(expr, "&", P.IntLit(0xFF)))

      case "toInt" =>
        // Char is already represented as Int (Unicode code point) in Python
        compileExpr(qual)

      case "toString" =>
        // Use chr to convert Unicode code point to string
        val (stats, expr) = compileExpr(qual)
        (stats, P.Call(None, "chr", List(expr)))

      case _ =>
        throw new Exception(s"Unknown Char method: $name")

  /** Compile Float primitive operations */
  private def compileFloatPrimitive(name: String, qual: Word, args: List[Word])(using UniqueName): (List[P.Stat], P.Expr) =
    name match
      case "+" | "-" | "*" | "/" | ">" | "<" | ">=" | "<=" | "==" | "!=" =>
        val arg :: Nil = args: @unchecked
        val (qualStats, qualExpr) = compileExpr(qual)
        val (argStats, argExpr) = compileExpr(arg)
        (qualStats ++ argStats, P.BinOp(qualExpr, name, argExpr))

      case "toInt" =>
        val (stats, expr) = compileExpr(qual)
        (stats, P.Call(None, "int", List(expr)))

      case "toString" =>
        val (stats, expr) = compileExpr(qual)
        (stats, P.Call(None, "str", List(expr)))

      case _ =>
        throw new Exception(s"Unknown Float method: $name")

  /** Compile String primitive operations */
  private def compileStringPrimitive(name: String, qual: Word, args: List[Word])(using UniqueName): (List[P.Stat], P.Expr) =
    name match
      case "+" =>
        val arg :: Nil = args: @unchecked
        val (qualStats, qualExpr) = compileExpr(qual)
        val (argStats, argExpr) = compileExpr(arg)
        (qualStats ++ argStats, P.BinOp(qualExpr, "+", argExpr))

      case "==" =>
        val arg :: Nil = args: @unchecked
        val (qualStats, qualExpr) = compileExpr(qual)
        val (argStats, argExpr) = compileExpr(arg)
        (qualStats ++ argStats, P.BinOp(qualExpr, "==", argExpr))

      case "size" =>
        val (stats, expr) = compileExpr(qual)
        (stats, P.Call(None, "len", List(expr)))

      case "get" =>
        val index :: Nil = args: @unchecked
        val (qualStats, qualExpr) = compileExpr(qual)
        val (indexStats, indexExpr) = compileExpr(index)
        // ord(s[index]) - get character code point at index
        (qualStats ++ indexStats, P.Call(None, "ord", List(P.Index(qualExpr, indexExpr))))

      case "substring" | "slice" =>
        val index :: len :: Nil = args: @unchecked
        val (qualStats, qualExpr) = compileExpr(qual)
        val (indexStats, indexExpr) = compileExpr(index)
        val (lenStats, lenExpr) = compileExpr(len)
        // s[index:index+len] - Python slice syntax
        val endExpr = P.BinOp(indexExpr, "+", lenExpr)
        (qualStats ++ indexStats ++ lenStats, P.Index(qualExpr, P.Slice(indexExpr, endExpr)))

      case _ =>
        throw new Exception(s"Unknown String method: $name")

  /** Generate Python code from namespaces and write to output file */
  def generate(nss: List[Namespace], outFile: String): Unit =
    val program = compile(nss)

    val pw = new java.io.PrintWriter(outFile)
    Printer.print(program, pw)
    pw.flush()
    pw.close()

end PythonCodeGen

object PythonCodeGen:
  def encodeSymbolic(name: String): String =
    // Replace special characters with Python-safe alternatives
    var result = name
    result = result.replace("$", "_D_")    // $ → _D_ (Dollar)
    result = result.replace(".", "_")       // . → _
    result = result.replace("+", "_plus")
    result = result.replace("-", "_minus")
    result = result.replace("*", "_times")
    result = result.replace("/", "_div")
    result = result.replace("%", "_mod")
    result = result.replace("&", "_and")
    result = result.replace("|", "_or")
    result = result.replace("^", "_xor")
    result = result.replace("<", "_lt")
    result = result.replace(">", "_gt")
    result = result.replace("=", "_eq")
    result = result.replace("!", "_bang")
    result = result.replace("~", "_tilde")
    result = result.replace("?", "_q")
    result = result.replace("@", "_at")
    result = result.replace("#", "_hash")
    result

package ruby

import sast.*
import sast.Trees as S
import sast.Symbols.*
import sast.Types

import ruby.Trees as R

import common.UniqueName
import common.WorkList

import scala.collection.mutable

/** Code generator that translates Jo SAST to Ruby AST
  *
  * This replaces the CPS-based string generation with direct AST construction,
  * enabling better optimization and cleaner code generation.
  */
class RubyCodeGen(runtime: RubyRuntime, rewire: Map[Symbol, Symbol])(using defn: Definitions):

  //----------------------------------------------------------------------------
  // Name management
  //----------------------------------------------------------------------------

  private val reservedNames = new UniqueName(separator = "")

  val keywords = List(
    "for", "while", "def", "class", "if", "else", "elsif", "end",
    "begin", "rescue", "ensure", "case", "when", "unless", "until",
    "loop", "do", "break", "next", "return", "yield", "super", "self",
    "module", "include", "extend", "require", "puts", "print", "p",
    "true", "false", "nil", "and", "or", "not", "in", "then"
  )

  // Make keywords unavailable
  for word <- keywords do reservedNames.freshName(word)

  // Make runtime symbols unavailable
  for name <- runtime.runtimeNames do reservedNames.freshName(name)

  private val symbol2UniqueName: mutable.Map[Symbol, String] = mutable.Map.empty

  val globalScope = reservedNames.newScope(separator = "")

  def rubyMemberName(sym: Symbol): String =
    assert(sym.isOneOf(Flags.Method | Flags.Field), "Not a method, sym = " + sym)

    symbol2UniqueName.get(sym) match
      case Some(name) => name

      case _ =>
        val rawName = RubyCodeGen.encodeSymbolic(sym.name)
        val scope = reservedNames.newScope("_")
        val name = scope.freshName(rawName)
        symbol2UniqueName(sym) = name
        name

  def rubyName(sym: Symbol)(using scope: UniqueName): String =
    assert(!sym.isOneOf(Flags.Method | Flags.Field), "Member name should call rubyMemberName, sym = " + sym)

    symbol2UniqueName.get(sym) match
      case Some(name) => name

      case None =>
        rewire.get(sym) match
          case Some(target) => rubyName(target)

          case None =>
            val uniqueName =
              if sym.isLocal then
                scope.freshName(RubyCodeGen.encodeSymbolic(sym.name))

              else
                val rawName = sym.fullName.replace(".", "_")
                val baseName = globalScope.freshName(RubyCodeGen.encodeSymbolic(rawName))
                // Ruby requires class names to start with uppercase letter
                if sym.isClass && baseName.headOption.exists(_.isLower) then
                  baseName.capitalize
                else
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

  /** Compile a complete set of namespaces to a Ruby program */
  def compile(nss: List[S.Namespace]): R.Program =
    workList.add(runtime.start)

    val funDefMap = mutable.Map.empty[Symbol, S.FunDef]
    val classDefMap = mutable.Map.empty[Symbol, S.ClassDef]

    for
      ns <- nss
      defn <- ns
    do
      defn match
        case fdef: S.FunDef =>
          funDefMap(fdef.symbol) = fdef

        case cdef: S.ClassDef =>
          classDefMap(cdef.symbol) = cdef

        case _ =>

    val defs = mutable.ArrayBuffer.empty[R.Def]

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

    // Build the program
    R.Program(
      globalInit = List(R.Assign("$runtime_contextParams", R.RawCode("{}"))),
      defs = defs.toList,
      mainCall = R.Call(None, rubyName(runtime.start), Nil, false)
    )

  /** Compile a function definition */
  private def compileFunction(fdef: S.FunDef): R.FunDef =
    val sym = fdef.symbol

    // Check if this is an object accessor
    if sym.is(Flags.Object) then
      val funType = sym.info.asProcType
      val classInfo = funType.resultType.asClassInfo
      val classSym = classInfo.classSymbol
      val className = rubyName(classSym)
      val name = rubyName(sym)

      // Generate: def name() ClassName.instance end
      return R.FunDef(name, Nil, R.Call(Some(R.Ident(className)), "instance", Nil, false))

    // Regular function - create new scope for local variables
    given UniqueName = reservedNames.newScope(separator = "")

    val name =
      if sym.name == "<init>" then
        // Ruby constructor is always named "initialize"
        "initialize"
      else if sym.is(Flags.Method) then
        rubyMemberName(sym)
      else
        rubyName(sym)
    val params = fdef.params.map(rubyName)
    val body = compileExpr(fdef.body)

    R.FunDef(name, params, body)

  /** Compile a class definition */
  private def compileClass(cdef: S.ClassDef)(using scope: UniqueName): R.ClassDef =
    val classSym = cdef.symbol
    val rubyClassName = rubyName(classSym)

    symbol2UniqueName(cdef.self) = "self"

    // Get all fields from the class definition
    val fieldNames = cdef.vals.map(rubyMemberName)

    // Compile methods - each method gets compiled with its own scope
    val methods = cdef.funs.map(compileFunction)

    R.ClassDef(
      name = rubyClassName,
      fields = fieldNames,
      methods = methods,
      isObject = classSym.is(Flags.Object)
    )

  /** Compile an expression */
  def compileExpr(word: S.Word)(using UniqueName): R.Expr = word match
    case S.Literal(c) =>
      c match
        case Constant.Bool(b) => R.BoolLit(b)
        case Constant.String(s) => R.StringLit(s)
        case Constant.Int(n) => R.IntLit(n)
        case Constant.Float(d) => R.FloatLit(d)

    case S.Ident(sym) =>
      assert(!sym.is(Flags.Context), "Unexpected context parameter")
      R.Ident(rubyName(sym))

    case S.Select(qual, name) =>
      word.tpe match
        case Types.MemberRef(_, sym) =>
          val qualExpr = compileExpr(qual)
          val memberName = rubyMemberName(sym)
          R.Select(qualExpr, memberName)
        case _ => throw new Exception("Unexpected select: " + word.show)

    case S.Block(words) =>
      words match
        case Nil =>
          R.Nil

        case _ =>
          if word.tpe.isValueType then
            val stats :+ expr = words: @unchecked
            val rubyStats = stats.flatMap(compileStat)
            val rubyResult = compileExpr(expr)
            if rubyStats.isEmpty then
              rubyResult
            else
              R.Block(rubyStats, rubyResult)
          else
            val rubyStats = words.flatMap(compileStat)
            R.Block(rubyStats, R.Nil)

    case encoded @ S.Encoded(repr) =>
      if encoded.isEmpty then
        R.Nil
      else if encoded.isValueDrop then
        // Value dropped - execute for side effects, result is nil
        // We still need to execute repr, just discard its value
        val exprToExec = compileExpr(repr)
        R.Block(List(R.ExprStat(exprToExec)), R.Nil)
      else if encoded.tpe.isLambdaType && repr.tpe.isClassType then
        // Wrap class instance as lambda
        val obj = compileExpr(repr)
        R.Lambda(List("*args"), R.Call(Some(obj), "apply", List(R.RawCode("*args")), false))
      else
        compileExpr(repr)

    case S.Apply(S.Select(S.New(classType), _), args, autos) =>
      // Object construction
      val classSym = classType.tpe.asClassInfo.classSymbol
      val rubyArgs = (args ++ autos).map(compileExpr)
      R.New(rubyName(classSym), rubyArgs)

    case S.Apply(S.TypeApply(S.Ident(sym), tpt :: Nil), arg :: Nil, Nil) if sym == defn.Internal_typeTest =>
      // Type test for union types
      val classInfo = tpt.tpe.asClassInfo
      val cls = classInfo.classSymbol
      val value = compileExpr(arg)

      val className =
        if cls == defn.String_String then "String"
        else if cls == defn.Float_Float then "Float"
        else if cls == defn.Int_Int || cls == defn.Byte_Byte || cls == defn.Char_Char then "Integer"
        else rubyName(cls)

      R.InstanceOf(value, className)

    case S.Apply(fun, args, autos) =>
      compileCall(fun, args ++ autos)

    case S.TypeApply(fun, _) =>
      // Strip type application (Ruby doesn't have generics)
      compileExpr(fun)

    case S.Assign(S.Ident(sym), rhs) =>
      val rhsExpr = compileExpr(rhs)
      val name = rubyName(sym)
      // Assignment as expression - wrap in block that returns nil
      R.Block(List(R.Assign(name, rhsExpr)), R.Nil)

    case S.FieldAssign(lhs @ S.Select(qual, _), rhs) =>
      val memberName = lhs.tpe match
        case Types.MemberRef(_, sym) => rubyMemberName(sym)
        case _ => throw new Exception("Unexpected lhs of assign: " + lhs.show)

      val rhsExpr = compileExpr(rhs)

      qual match
        case S.Ident(sym) if symbol2UniqueName.get(sym).contains("self") =>
          // Field assignment on self - use instance variable syntax
          R.Block(List(R.FieldAssign(None, memberName, rhsExpr)), R.Nil)

        case _ =>
          // Field assignment on other object - use setter syntax
          val qualExpr = compileExpr(qual)
          R.Block(List(R.FieldAssign(Some(qualExpr), memberName, rhsExpr)), R.Nil)

    case S.If(cond, thenp, elsep) =>
      val condExpr = compileExpr(cond)
      val thenExpr = compileExpr(thenp)
      val elseExpr = compileExpr(elsep)
      R.If(condExpr, thenExpr, elseExpr)

    case S.While(cond, body) =>
      val condExpr = compileExpr(cond)
      val bodyStats = compileStat(body)
      // While returns nil in Ruby
      R.Block(List(R.While(condExpr, bodyStats)), R.Nil)

    case _: S.TypeDef =>
      R.Nil

    case _: S.Def | _: S.With | _: S.Allow | _: S.Match |
         _: S.New | _: S.IsExpr | _: S.CaseDef | _: S.Lambda | _: S.RecordLit =>
      throw new Exception("Unexpected: " + word)

  /** Compile a statement (returns list of statements) */
  private def compileStat(word: S.Word)(using UniqueName): List[R.Stat] = word match
    case S.Assign(S.Ident(sym), rhs) =>
      val rhsExpr = compileExpr(rhs)
      val name = rubyName(sym)
      List(R.Assign(name, rhsExpr))

    case S.FieldAssign(lhs @ S.Select(qual, _), rhs) =>
      val memberName = lhs.tpe match
        case Types.MemberRef(_, sym) => rubyMemberName(sym)
        case _ => throw new Exception("Unexpected lhs of assign: " + lhs.show)

      val rhsExpr = compileExpr(rhs)

      qual match
        case S.Ident(sym) if symbol2UniqueName.get(sym).contains("self") =>
          List(R.FieldAssign(None, memberName, rhsExpr))

        case _ =>
          val qualExpr = compileExpr(qual)
          List(R.FieldAssign(Some(qualExpr), memberName, rhsExpr))

    case S.Block(words) =>
      words.flatMap(compileStat)

    case S.If(cond, thenp, elsep) if !word.tpe.isValueType =>
      // If as statement (no value used)
      val condExpr = compileExpr(cond)
      val thenExpr = compileExpr(thenp)
      val elseExpr = compileExpr(elsep)
      List(R.ExprStat(R.If(condExpr, thenExpr, elseExpr)))

    case S.While(cond, body) =>
      val condExpr = compileExpr(cond)
      val bodyStats = compileStat(body)
      List(R.While(condExpr, bodyStats))

    case encoded: S.Encoded if encoded.isEmpty =>
      Nil

    case _: S.TypeDef =>
      Nil

    case _ =>
      // Expression as statement
      List(R.ExprStat(compileExpr(word)))

  /** Compile a function/method call */
  private def compileCall(fun: S.Word, args: List[S.Word])(using UniqueName): R.Expr =
    fun match
      case S.Encoded(f) if f.tpe.isLambdaType =>
        // Lambda call - use .call() syntax
        val funExpr = compileExpr(f)
        val rubyArgs = args.map(compileExpr)
        R.Call(Some(funExpr), "", rubyArgs, isLambdaCall = true)

      case S.Ident(sym) =>
        if sym.owner == defn.Bool then
          compileBoolPrimitive(sym, args)
        else if sym == runtime.ruby then
          // Raw Ruby code
          val S.Literal(Constant.String(code)) :: Nil = args : @unchecked
          R.RawCode(code)
        else
          val rubyArgs = args.map(compileExpr)
          R.Call(None, rubyName(sym), rubyArgs, false)

      case S.Select(qual, name) if qual.tpe.isSubtype(defn.IntType) =>
        compileIntPrimitive(name, qual, args)

      case S.Select(qual, name) if qual.tpe.isSubtype(defn.ByteType) =>
        compileIntPrimitive(name, qual, args)

      case S.Select(qual, name) if qual.tpe.isSubtype(defn.CharType) =>
        compileCharPrimitive(name, qual, args)

      case S.Select(qual, name) if qual.tpe.isSubtype(defn.FloatType) =>
        compileFloatPrimitive(name, qual, args)

      case S.Select(qual, name) if qual.tpe.isSubtype(defn.StringType) =>
        // Lower String operations to runtime calls
        val stringOpSym = runtime.StringOps.termMember(name)
        val rubyArgs = (qual :: args).map(compileExpr)
        R.Call(None, rubyName(stringOpSym), rubyArgs, false)

      case S.Select(qual, name) =>
        // Regular method/function call on an object
        val qualExpr = compileExpr(qual)
        val memberName = fun.tpe match
          case Types.MemberRef(_, sym) => rubyMemberName(sym)
          case _ => name
        val rubyArgs = args.map(compileExpr)
        R.Call(Some(qualExpr), memberName, rubyArgs, false)

      case S.TypeApply(fun2, _) =>
        // Strip type application and recurse
        compileCall(fun2, args)

      case S.Encoded(repr) =>
        // Strip encoding and recurse
        compileCall(repr, args)

      case _ =>
        // Complex expression - assume it's a lambda
        val funExpr = compileExpr(fun)
        val rubyArgs = args.map(compileExpr)
        R.Call(Some(funExpr), "", rubyArgs, isLambdaCall = true)

  /** Compile Bool primitive operations */
  private def compileBoolPrimitive(sym: Symbol, args: List[S.Word])(using UniqueName): R.Expr =
    sym match
      case defn.Bool_both =>
        val a :: b :: Nil = args: @unchecked
        R.BinOp("&&", compileExpr(a), compileExpr(b))

      case defn.Bool_either =>
        val a :: b :: Nil = args: @unchecked
        R.BinOp("||", compileExpr(a), compileExpr(b))

      case defn.Bool_not =>
        val operand :: Nil = args: @unchecked
        R.UnaryOp("!", compileExpr(operand))

      case _ =>
        val rubyArgs = args.map(compileExpr)
        R.Call(None, rubyName(sym), rubyArgs, false)

  /** Compile Int primitive operations */
  private def compileIntPrimitive(name: String, qual: S.Word, args: List[S.Word])(using UniqueName): R.Expr =
    name match
      case "+" | "-" | "*" | "/" | "%" | "==" | "!=" | "<" | ">" | "<=" | ">=" =>
        val arg :: Nil = args: @unchecked
        R.BinOp(name, compileExpr(qual), compileExpr(arg))

      case "toFloat" =>
        R.Call(Some(compileExpr(qual)), "to_f", Nil, false)

      case "toString" =>
        R.Call(Some(compileExpr(qual)), "to_s", Nil, false)

      case _ =>
        throw new Exception(s"Unknown Int method: $name")

  /** Compile Float primitive operations */
  private def compileFloatPrimitive(name: String, qual: S.Word, args: List[S.Word])(using UniqueName): R.Expr =
    name match
      case "+" | "-" | "*" | "/" | ">" | "<" | ">=" | "<=" | "==" | "!=" =>
        val arg :: Nil = args: @unchecked
        R.BinOp(name, compileExpr(qual), compileExpr(arg))

      case "toInt" =>
        R.Call(Some(compileExpr(qual)), "to_i", Nil, false)

      case "toString" =>
        R.Call(Some(compileExpr(qual)), "to_s", Nil, false)

      case _ =>
        throw new Exception(s"Unknown Float method: $name")

  /** Compile Char primitive operations */
  private def compileCharPrimitive(name: String, qual: S.Word, args: List[S.Word])(using UniqueName): R.Expr =
    name match
      case "==" | "!=" | "<" | ">" | "<=" | ">=" =>
        val arg :: Nil = args: @unchecked
        R.BinOp(name, compileExpr(qual), compileExpr(arg))

      case "toString" =>
        R.Call(Some(compileExpr(qual)), "chr", Nil, false)

      case _ =>
        throw new Exception(s"Unknown Char method: $name")

  /** Generate Ruby code from namespaces and write to output file */
  def generate(nss: List[S.Namespace], outFile: String): Unit =
    val program = compile(nss)
    val code = Printer.print(program)

    val pw = new java.io.PrintWriter(outFile)
    pw.write(code)
    pw.close()

end RubyCodeGen

object RubyCodeGen:
  def encodeSymbolic(name: String): String =
    // Replace special characters with Ruby-safe alternatives
    // $ must be replaced first because Ruby treats it as global variable marker
    var result = name
    result = result.replace("$", "_D_")    // $ → _D_ (Dollar)
    result = result.replace(".", "_")       // . → _
    result = result.replace("+", "_plus")
    result = result.replace("-", "_minus")
    result = result.replace("*", "_times")
    result = result.replace("/", "_div")
    result = result.replace("%", "_mod")
    result = result.replace("==", "_eq_eq")
    result = result.replace("=", "_eq")
    result = result.replace("<=", "_less_eq")
    result = result.replace("<", "_less")
    result = result.replace(">=", "_greater_eq")
    result = result.replace(">", "_greater")
    result = result.replace("!", "_bang")
    result = result.replace("&", "_amp")
    result = result.replace("|", "_bar")
    result = result.replace("^", "_hat")
    result = result.replace("~", "_tilde")
    result = result.replace("?", "_qmark")
    result = result.replace(":", "_colon")
    result

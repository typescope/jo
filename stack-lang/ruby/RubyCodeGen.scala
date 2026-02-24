package ruby

import sast.*
import sast.Trees.*
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

  val SingletonFieldName = "__instance"

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

  /** Compile a complete set of file units to a Ruby program */
  def compile(units: List[FileUnit]): R.Program =
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
    val globalInit = R.Assign("$runtime_contextParams", R.RawCode("{}")) ::
      runtime.paramIds.toList.map: (fullName, globalName) =>
        // $param_jo_IO_stdout = "jo.IO.stdout".to_sym
        R.Assign(globalName, R.RawCode(s""""$fullName".to_sym"""))

    R.Program(
      globalInit = globalInit,
      defs = defs.toList,
      mainCall = R.Call(None, rubyName(runtime.start), Nil)
    )

  /** Compile a function definition */
  private def compileFunction(fdef: FunDef): R.FunDef = try
    val sym = fdef.symbol

    // Regular function - create new scope for local variables
    given UniqueName = reservedNames.newScope(separator = "")

    val name =
      if sym.is(Flags.Constructor) then
        // Ruby constructor is always named "initialize"
        "initialize"
      else if sym.is(Flags.Method) then
        rubyMemberName(sym)
      else
        rubyName(sym)

    val params = fdef.params.map(rubyName) ++ fdef.autos.map(rubyName)
    val body = compileExpr(fdef.body)

    R.FunDef(name, params, body)
  catch case ex: Exception =>
    println("Error compiling function:" + fdef.show)
    throw ex

  /** Compile a class definition */
  private def compileClass(cdef: ClassDef)(using scope: UniqueName): R.ClassDef =
    val classSym = cdef.symbol
    val rubyClassName = rubyName(classSym)

    symbol2UniqueName(cdef.self) = "self"

    // Get all fields from the class definition
    val fieldNames = cdef.vals.map(rubyMemberName)

    // Compile methods - each method gets compiled with its own scope
    val methods = cdef.funs.map(compileFunction)

    // Add static field if this is a singleton object
    val staticFields =
      if classSym.is(Flags.Object) then
        R.Assign(SingletonFieldName, R.New(rubyClassName, Nil)) :: Nil
      else
        Nil

    R.ClassDef(
      name = rubyClassName,
      fields = fieldNames,
      methods = methods,
      staticFields = staticFields
    )

  /** Compile an expression */
  def compileExpr(word: Word)(using UniqueName): R.Tree = word match
    case Literal(c) =>
      c match
        case Constant.Bool(b) => R.BoolLit(b)
        case Constant.String(s) => R.StringLit(s)
        case Constant.Int(n) => R.IntLit(n)
        case Constant.Float(d) => R.FloatLit(d)

    case Ident(sym) =>
      assert(!sym.is(Flags.Context), "Unexpected context parameter")
      R.Ident(rubyName(sym))

    case Select(qual, name) =>
      word.tpe match
        case Types.MemberRef(_, sym) =>
          val qualExpr = compileExpr(qual)
          val memberName = rubyMemberName(sym)
          R.Select(qualExpr, memberName)

        case _ => throw new Exception("Unexpected select: " + word.show)

    case Block(words) =>
       val stats = words.map(compileExpr)
       if stats.size == 1 then stats.head
       else R.Block(stats)

    case encoded @ Encoded(repr) =>
      if encoded.isValueDrop then
        // Value dropped - execute for side effects, result is nil
        // We still need to execute repr, just discard its value
        compileExpr(repr)

      else if encoded.tpe.isLambdaType && repr.tpe.isClassType then
        // Wrap class instance as lambda
        val obj = compileExpr(repr)
        val objName = summon[UniqueName].freshName("instance")
        R.Block(
          R.Assign(objName, obj) ::
          R.Lambda(List("*args"), R.Call(Some(R.Ident(objName)), "apply", List(R.RawCode("*args")))) ::
          Nil
        )

      else
        compileExpr(repr)

    case Apply(Select(New(classType), _), args, autos) =>
      // Object construction
      val classSym = classType.tpe.asClassInfo.classSymbol
      val rubyArgs = (args ++ autos).map(compileExpr)
      R.New(rubyName(classSym), rubyArgs)

    case ClassTest(arg, cls) =>
      // Type test for union types
      val value = compileExpr(arg)

      if cls == defn.Bool_type then
        R.BinOp(R.InstanceOf(value, "TrueClass"), "||", R.InstanceOf(value, "FalseClass"))
      else
        val className =
          if cls == defn.String_type then "String"
          else if cls == defn.Float_type then "Float"
          else if cls == defn.Int_type || cls == defn.Byte_type || cls == defn.Char_type then "Integer"
          else rubyName(cls)

        R.InstanceOf(value, className)

    case Apply(fun, args, autos) =>
      compileCall(fun, args ++ autos)

    case Assign(Ident(sym), rhs) =>
      val rhsExpr = compileExpr(rhs)
      val name = rubyName(sym)
      // Assignment as expression - wrap in block that returns nil
      R.Assign(name, rhsExpr)

    case FieldAssign(lhs @ Select(qual, _), rhs) =>
      val memberName = lhs.tpe match
        case Types.MemberRef(_, sym) => rubyMemberName(sym)
        case _ => throw new Exception("Unexpected lhs of assign: " + lhs.show)

      val rhsExpr = compileExpr(rhs)

      qual match
        case Ident(sym) if sym.owner.isType && sym == sym.owner.classInfo.self =>
          // Field assignment on self - use instance variable syntax
          R.FieldAssign(None, memberName, rhsExpr)

        case _ =>
          // Field assignment on other object - use setter syntax
          val qualExpr = compileExpr(qual)
          R.FieldAssign(Some(qualExpr), memberName, rhsExpr)

    case If(cond, thenp, elsep) =>
      val condExpr = compileExpr(cond)
      val thenExpr = compileExpr(thenp)
      val elseExpr = compileExpr(elsep)
      R.If(condExpr, thenExpr, elseExpr)

    case While(cond, body) =>
      val condExpr = compileExpr(cond)
      val body2 = compileExpr(body)
      R.While(condExpr, body2)

    case _: TypeDef =>
      R.Nil

    case _: Def | _: With | _: Allow | _: Match | _: TypeApply |
         _: New | _: IsExpr | _: PatValDef | _: Lambda | _: RecordLit =>
      throw new Exception("Unexpected: " + word)

  /** Compile a function/method call */
  private def compileCall(fun: Word, args: List[Word])(using UniqueName): R.Tree =
    fun match
      case Ident(sym) if sym.isFunction =>
        if sym == runtime.ruby then
          // Raw Ruby code
          val Literal(Constant.String(code)) :: Nil = args : @unchecked
          R.RawCode(code)

        else if sym == runtime.paramKey then
          val paramSym = args.head match
            case Ident(paramSym) => paramSym
            case Literal(Constant.String(path)) => defn.resolveTerm(path) // special support for entry method
            case word => throw new Exception("Unsupported argument to paramKey: " + word)

          val globalName = runtime.getOrCreateParamId(paramSym)
          R.Ident(globalName)

        else if sym == defn.jo_pass then
          R.Nil

        else if sym.is(Flags.Object) then
          // Object accessor: replace call with direct access
          val funType = sym.info.asProcType
          val classInfo = funType.resultType.asClassInfo
          val classSym = classInfo.classSymbol

          // Mark the class as reachable - it will get a static instance field
          val className = rubyName(classSym)
          R.Select(R.Ident(className), SingletonFieldName)

        else
          val rubyArgs = args.map(compileExpr)
          R.Call(None, rubyName(sym), rubyArgs)

      case Select(qual, name) if qual.tpe.isSubtype(defn.BoolType) =>
        compileBoolPrimitive(name, qual, args)

      case Select(qual, name) if qual.tpe.isSubtype(defn.IntType) =>
        compileIntPrimitive(name, qual, args)

      case Select(qual, name) if qual.tpe.isSubtype(defn.ByteType) =>
        compileIntPrimitive(name, qual, args)

      case Select(qual, name) if qual.tpe.isSubtype(defn.CharType) =>
        compileCharPrimitive(name, qual, args)

      case Select(qual, name) if qual.tpe.isSubtype(defn.FloatType) =>
        compileFloatPrimitive(name, qual, args)

      case Select(qual, name) if qual.tpe.isSubtype(defn.StringType) =>
        compileStringPrimitive(name, qual, args)

      case f if f.tpe.isLambdaType =>
        // Lambda call - use .call() syntax
        val funExpr = compileExpr(f)
        val rubyArgs = args.map(compileExpr)
        R.LambdaCall(funExpr, rubyArgs)

      case Select(qual, name) =>
        // Regular method/function call on an object
        val qualExpr = compileExpr(qual)
        val memberName = fun.tpe match
          case Types.MemberRef(_, sym) => rubyMemberName(sym)
          case _ => throw new Exception("Unexpected select: " + fun.show)

        val rubyArgs = args.map(compileExpr)
        R.Call(Some(qualExpr), memberName, rubyArgs)

      case TypeApply(fun2, _) =>
        // Strip type application and recurse
        compileCall(fun2, args)

      case Encoded(repr) =>
        // Strip encoding and recurse
        compileCall(repr, args)

      case _ =>
        throw new Exception("Unexpected function in call: " + fun)

  /** Compile Bool class method operations (&&, ||, ==, !=, ~!, toString) */
  private def compileBoolPrimitive(name: String, qual: Word, args: List[Word])(using UniqueName): R.Tree =
    name match
      case "&&" =>
        val arg :: Nil = args: @unchecked
        R.BinOp(compileExpr(qual), "&&", compileExpr(arg))

      case "||" =>
        val arg :: Nil = args: @unchecked
        R.BinOp(compileExpr(qual), "||", compileExpr(arg))

      case "==" =>
        val arg :: Nil = args: @unchecked
        R.BinOp(compileExpr(qual), "==", compileExpr(arg))

      case "!=" =>
        val arg :: Nil = args: @unchecked
        R.BinOp(compileExpr(qual), "!=", compileExpr(arg))

      case "~!" =>
        R.UnaryOp("!", compileExpr(qual))

      case "toString" =>
        R.Call(Some(compileExpr(qual)), "to_s", Nil)

      case _ =>
        throw new Exception(s"Unknown Bool method: $name")

  private def compileIntPrimitive(name: String, qual: Word, args: List[Word])(using UniqueName): R.Tree =
    name match
      case "+" | "-" | "*" | "/" | "%" | "==" | "!=" | "<" | ">" | "<=" | ">=" | "&" | "|" | "^" | "<<" | ">>" =>
        val arg :: Nil = args: @unchecked
        R.BinOp(compileExpr(qual), name, compileExpr(arg))

      case "toFloat" =>
        R.Select(compileExpr(qual), "to_f")

      case "toByte" =>
        R.BinOp(compileExpr(qual), "&", R.IntLit(0xFF))

      case "toChar" =>
        // Char is represented as Int (Unicode code point) in Ruby, so this is a no-op
        compileExpr(qual)

      case "toInt" =>  // called from Byte
        compileExpr(qual)

      case "~-" =>
        R.UnaryOp("-", compileExpr(qual))

      case "toString" =>
        R.Select(compileExpr(qual), "to_s")

      case _ =>
        throw new Exception(s"Unknown Int method: $name")

  /** Compile Float primitive operations */
  private def compileFloatPrimitive(name: String, qual: Word, args: List[Word])(using UniqueName): R.Tree =
    name match
      case "+" | "-" | "*" | "/" | ">" | "<" | ">=" | "<=" | "==" | "!=" =>
        val arg :: Nil = args: @unchecked
        R.BinOp(compileExpr(qual), name, compileExpr(arg))

      case "toInt" =>
        R.Select(compileExpr(qual), "to_i")

      case "~-" =>
        R.UnaryOp("-", compileExpr(qual))

      case "toString" =>
        R.Select(compileExpr(qual), "to_s")

      case _ =>
        throw new Exception(s"Unknown Float method: $name")

  /** Compile Char primitive operations */
  private def compileCharPrimitive(name: String, qual: Word, args: List[Word])(using UniqueName): R.Tree =
    name match
      case "==" | "!=" | "<" | ">" | "<=" | ">=" =>
        val arg :: Nil = args: @unchecked
        R.BinOp(compileExpr(qual), name, compileExpr(arg))

      case "toByte" =>
        // Char is already represented as Int in Ruby
        R.BinOp(compileExpr(qual), "&", R.IntLit(0xFF))

      case "toInt" =>
        // Char is already represented as Int (Unicode code point) in Ruby
        compileExpr(qual)

      case "toString" =>
        // Use chr with UTF-8 encoding to support Unicode code points > 255
        R.Call(Some(compileExpr(qual)), "chr", List(R.RawCode("Encoding::UTF_8")))

      case _ =>
        throw new Exception(s"Unknown Char method: $name")

  /** Compile String primitive operations */
  private def compileStringPrimitive(name: String, qual: Word, args: List[Word])(using UniqueName): R.Tree =
    name match
      case "+" =>
        val arg :: Nil = args: @unchecked
        R.BinOp(compileExpr(qual), "+", compileExpr(arg))

      case "==" =>
        val arg :: Nil = args: @unchecked
        R.BinOp(compileExpr(qual), "==", compileExpr(arg))

      case "size" =>
        R.Select(compileExpr(qual), "length")

      case "get" =>
        val index :: Nil = args: @unchecked
        // str[index].ord - get character code point at index
        val charSelect = R.Index(compileExpr(qual), List(compileExpr(index)))
        R.Select(charSelect, "ord")

      case "substring" =>
        val index :: len :: Nil = args: @unchecked
        // str[index, len] - Ruby slice syntax
        R.Index(compileExpr(qual), List(compileExpr(index), compileExpr(len)))

      case "indexOfFrom" =>
        val other :: from :: Nil = args: @unchecked
        val idxName = summon[UniqueName].freshName("idx")
        val indexCall = R.Call(Some(compileExpr(qual)), "index", List(compileExpr(other), compileExpr(from)))
        R.Block(
          R.Assign(idxName, indexCall) ::
          R.If(
            R.BinOp(R.Ident(idxName), "==", R.Nil),
            R.IntLit(-1),
            R.Ident(idxName)) ::
          Nil)

      case "iterator" =>
        R.Call(None, rubyName(runtime.String_iterator), List(compileExpr(qual)))

      case "toLower" =>
        R.Call(Some(compileExpr(qual)), "downcase", Nil)

      case "toUpper" =>
        R.Call(Some(compileExpr(qual)), "upcase", Nil)

      case _ =>
        throw new Exception(s"Unknown String method: $name")

  /** Generate Ruby code from file units and write to output file */
  def generate(units: List[FileUnit], outFile: String): Unit =
    val program = compile(units)

    val pw = new java.io.PrintWriter(outFile)
    Printer.print(program, pw)
    pw.flush()
    pw.close()

end RubyCodeGen

object RubyCodeGen:
  private val symbolEncoding = Map(
    '$' -> "_dollar",
    '.' -> "_",
    '+' -> "_plus",
    '-' -> "_minus",
    '*' -> "_times",
    '/' -> "_div",
    '%' -> "_mod",
    '=' -> "_eq",
    '<' -> "_less",
    '>' -> "_greater",
    '!' -> "_bang",
    '&' -> "_amp",
    '|' -> "_bar",
    '^' -> "_hat",
    '~' -> "_tilde",
    '?' -> "_qmark",
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

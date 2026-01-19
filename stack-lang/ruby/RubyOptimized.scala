package ruby

import sast.*
import sast.Trees.*
import sast.Symbols.*

import common.Debug
import common.StringUtil
import common.Text
import common.Text.*
import common.UniqueName
import common.WorkList

import RubyOptimized.encodeSymbolic

import java.io.PrintWriter
import scala.collection.mutable

/**
  * Ruby platform with code optimization
  */
class RubyOptimized(outFile: String, runtime: RubyRuntime, rewire: Map[Symbol, Symbol])(using defn: Definitions):
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
        val rawName = encodeSymbolic(sym.name)
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
                scope.freshName(encodeSymbolic(sym.name))

              else
                val rawName = sym.fullName.replace(".", "_")
                // A global symbol might be first reached in a local scope
                val baseName = globalScope.freshName(encodeSymbolic(rawName))
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

  given (using localScope: UniqueName): Text.Maker[Word] = word =>
    val ctx = new StatContext(() => Text.Empty)
    compile(word)(using ctx)

  given (using localScope: UniqueName): Text.Maker[Symbol] = sym =>
    Text(rubyName(sym))

  //----------------------------------------------------------------------------

  /** A context where a result value is expected to continue execution
    *
    * @param isLast whether the context is the last position of an expression
    */
  case class ValueContext(cont: Text => Text, isLast: Boolean)

  /** A statement context where no result value is expected to continue execution */
  case class StatContext(cont: ()=> Text)

  type Context = ValueContext | StatContext

  def cont(text: Text, sideEffect: Boolean = false)(using cont1: Context)(using localScope: UniqueName): Text =
    cont1 match
      case ValueContext(cont2, isLast) =>
        if isLast || !sideEffect then
          cont2(text)
        else
          val resName = localScope.freshName("res")
          resName ~ " = " ~ text ~ Text.BreakLine
          ~ cont2(Text(resName))

      case StatContext(cont2)  =>
        text ~ cont2()

  def cont()(using cont1: Context): Text =
    cont1 match
      case ValueContext(_, _) => throw new Exception("Value expected, found none")
      case StatContext(cont2)  => cont2()

  def run(expr: Word)(cont1: Text => Text)(using UniqueName): Text =
    compile(expr)(using ValueContext(cont1, isLast = false))

  def runLast(expr: Word)(cont1: Text => Text)(using UniqueName): Text =
    compile(expr)(using ValueContext(cont1, isLast = true))

  def run(exprs: List[Word])(c: List[Text] => Text)(using UniqueName): Text =
    exprs match
      case Nil => c(Nil)
      case expr :: exprs =>
        run(expr): t =>
          run(exprs): ts =>
            c(t :: ts)

  //----------------------------------------------------------------------------
  val workList = new WorkList[Symbol]

  def compile(nss: List[Namespace]): Unit =
    val pw =  new PrintWriter(outFile)

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

    pw.append("# Generated Ruby code\n\n")

    // runtime code
    pw.append(runtime.globalDefCode)
    pw.append("\n\n")

    given UniqueName = globalScope

    // user code
    workList.run: sym =>
      val code =
         if sym.isFunction then
           compileFunction(funDefMap(sym))

         else if sym.isClass then
           compileClass(classDefMap(sym))

         else
           throw new Exception("Symbol is neither a function nor class: " + sym)

      val text = code ~ Text.BlankLine
      pw.append(text.toString)

    val mainCall = runtime.start ~ "()"
    pw.append(mainCall.toString)
    pw.append("\n")

    pw.close()

  def compile(word: Word)(using Context)(using localScope: UniqueName): Text = Debug.trace("Compiling " + word.show, enable = false):
    word match
      case Literal(c)  =>
        c match
          case Constant.Bool(b) => cont(Text(b.toString))

          case Constant.String(s) =>
            cont("\"" ~ StringUtil.escape(s) ~ "\"")

          case Constant.Int(n) =>
            cont(Text(n.toString))

          case Constant.Float(d) =>
            cont(Text(d.toString))

      case Select(qual, name) =>
        run(qual): v =>
          val memberName = word.tpe match
            case Types.MemberRef(_, sym) => rubyMemberName(sym)
            case _ => throw new Exception("Unexpected select: " + word.show)

          cont(v ~ "." ~ memberName)

      case Block(words) =>
        words match
          case Nil =>
            cont()

          case _ =>
            if word.tpe.isValueType then
              val stats :+ expr = words: @unchecked
              val sep = if stats.isEmpty then Text.Empty else Text.BreakLine
              stats.join(Text.BreakLine) ~ sep ~ compile(expr)

            else
              words.join(Text.BreakLine) ~ cont()

      case encoded @ Encoded(repr) =>
        if encoded.isEmpty then
          cont()

        else if encoded.isValueDrop then
          repr ~ Text.BreakLine ~ cont()

        else if encoded.tpe.isLambdaType && repr.tpe.isClassType then
          run(repr): v =>
            cont("lambda { |*args| " ~ v ~ ".apply(*args) }")

        else
          run(repr): v =>
            cont(v)

      case Apply(Select(New(classType), _), args, autos) =>
        val classSym = classType.tpe.asClassInfo.classSymbol
        run(args ++ autos): vs =>
          val newExpr = rubyName(classSym) ~ ".new(" ~ vs.join(", ") ~ ")"
          cont(newExpr, sideEffect = true)

      case Apply(TypeApply(Ident(sym), tpt :: Nil), arg :: Nil, Nil) if sym == defn.Internal_typeTest =>
        // Handle type test for union types
        val classInfo = tpt.tpe.asClassInfo
        val cls = classInfo.classSymbol
        run(arg): v =>
          if cls == defn.String_String then
            cont("(" ~ v ~ ".is_a?(String))")

          else if cls == defn.Float_Float then
            cont("(" ~ v ~ ".is_a?(Float))")

          else if cls == defn.Int_Int || cls == defn.Byte_Byte || cls == defn.Char_Char then
            cont("(" ~ v ~ ".is_a?(Integer))")

          else
            cont("(" ~ v ~ ".is_a?(" ~ rubyName(cls) ~ "))")

      case Apply(fun, args, autos) =>
        call(fun, args ++ autos)

      case TypeApply(fun, _) =>
        compile(fun)

      case Assign(Ident(sym), rhs) =>
        runLast(rhs): t =>
          if sym.isMutable then
            sym ~ " = " ~ t ~ Text.BreakLine ~ cont()
          else
            // Uniqueness of symbol names is guaranteed by the name generator.
            sym ~ " = " ~ t ~ Text.BreakLine ~ cont()

      case FieldAssign(lhs @ Select(qual, _), rhs) =>
        val memberName = lhs.tpe match
          case Types.MemberRef(_, sym) => rubyMemberName(sym)
          case _ => throw new Exception("Unexpected lhs of assign: " + lhs.show)

        qual match
          case Ident(sym) if symbol2UniqueName.get(sym).contains("self") =>
            // Field assignment on self - use instance variable syntax
            runLast(rhs): v2 =>
              "@" ~ memberName ~ " = " ~ v2 ~ Text.BreakLine ~ cont()

          case _ =>
            // Field assignment on other object - use setter syntax
            run(qual): v1 =>
              runLast(rhs): v2 =>
                v1 ~ "." ~ memberName ~ " = " ~ v2 ~ Text.BreakLine ~ cont()

      case If(cond, thenp, elsep) =>
        run(cond): v =>
          if word.tpe.isValueType then
            val resName = localScope.freshName("res")
            resName ~ " = nil" ~ Text.BreakLine ~
            "if " ~ v ~ Text.BreakLine ~ indent:
                run(thenp): v =>
                  resName ~ " = " ~ v
            ~ Text.BreakLine ~ "else" ~ Text.BreakLine ~ indent:
                run(elsep): v =>
                  resName ~ " = " ~ v
            ~ Text.BreakLine ~ "end" ~ Text.BreakLine ~
            cont(Text(resName))

          else
            "if " ~ v ~ Text.BreakLine ~
               indent(thenp) ~ Text.BreakLine ~
            (if elsep.isEmpty then Text("end") else "else" ~ Text.BreakLine ~
               indent(elsep) ~ Text.BreakLine ~
            Text("end"))
            ~ Text.BreakLine ~ cont()

      case While(cond, body) =>
        "while true" ~ Text.BreakLine ~ indent:
          run(cond): c =>
            "break unless " ~ c ~ Text.BreakLine ~ body
        ~ Text.BreakLine ~ "end"
        ~ Text.BreakLine ~ cont()

      case Ident(sym) =>
        assert(!sym.is(Flags.Context), "Unexpected context parameter")
        cont(Text(sym), sideEffect = sym.isMutable)

      case _: TypeDef =>
        cont()

      case _: Def | _: With | _: Allow | _: Match |
           _: New | _: IsExpr | _: CaseDef | _: Lambda | _: RecordLit =>

        throw new Exception("Unexpected " + word)

  /** Compile a function */
  def compileFunction(fdef: FunDef)(using UniqueName): Text = try
    val sym = fdef.symbol

    val funType = sym.info.asProcType
    val resCount = funType.resCount

    // object accessor
    if sym.is(Flags.Object) then
      val classInfo = funType.resultType.asClassInfo
      val classSym = classInfo.classSymbol
      val className = rubyName(classSym)
      return "def " ~ rubyName(sym) ~ "()" ~ Text.BreakLine ~ indent:
        "return " ~ className ~ ".instance"
      ~ Text.BreakLine ~ "end"

    val prefix =
      if sym.isConstructor then
        Text("def initialize")

      else
        // create the name outside of the new scope to avoid conflicting names
        if sym.isMethod then "def " ~ rubyMemberName(sym) else "def " ~ rubyName(sym)

    locally:
      given UniqueName = reservedNames.newScope(separator = "")

      val bodyText: Text =
        if resCount == 0 then
          compile(fdef.body)(using StatContext(() => Text.Empty))
        else
          runLast(fdef.body) { v =>
            "return " ~ v ~ Text.BreakLine
          }

      prefix ~ "(" ~ fdef.allParams.join(", ") ~ ")" ~ Text.BreakLine ~ indent(bodyText) ~ Text.BreakLine ~ "end"
  catch case ex: Exception =>
    println(fdef.body.show)
    throw ex

  /** Compile a class */
  def compileClass(cdef: ClassDef)(using UniqueName): Text =
    val classSym = cdef.symbol
    val rubyClassName = rubyName(classSym)

    symbol2UniqueName(cdef.self) = "self"

    // Get all fields from the class definition
    val fieldNames = cdef.vals.map(rubyMemberName)

    // Generate class member names in a fresh scope
    locally:
      val accessors =
        if fieldNames.nonEmpty then
          "attr_accessor " ~ fieldNames.sorted.map(":" + _).join(", ")
        else
          Text.Empty

      var members = cdef.funs.map(compileFunction)

      if classSym.is(Flags.Object) then
        // create static instance
        val staticInstance = "@instance = " ~ rubyClassName ~ ".new"
        val classMethod = "def self.instance" ~ Text.BreakLine ~ indent:
          "@instance"
        ~ Text.BreakLine ~ "end"
        members = staticInstance :: classMethod :: members

      "class " ~ rubyClassName ~ Text.BreakLine ~ indent:
        if accessors != Text.Empty then
          accessors ~ Text.BlankLine ~ members.join(Text.BlankLine)
        else
          members.join(Text.BlankLine)
      ~ Text.BreakLine ~ "end"


  /** Compile Bool primitive */
  def callBoolPrimitive(sym: Symbol, args: List[Word])(using Context)(using UniqueName): Text =

    def binary(op: String): Text =
      val a :: b :: Nil = args: @unchecked
      run(a): v1 =>
        run(b): v2 =>
          cont("(" ~ v1 ~ " " ~ op ~ " " ~ v2 ~ ")")

    def unary(op: String): Text =
      val operand :: Nil = args: @unchecked
      run(operand): v =>
        cont("(" ~ op ~ v ~ ")")

    sym match
      case defn.Bool_both   =>   binary("&&")
      case defn.Bool_either =>   binary("||")
      case defn.Bool_not    =>   unary("!")

      case _ => throw new Exception("Unknown Bool method: " + sym.name)

  /** Compile Int method calls to Ruby operators */
  def callIntPrimitive(name: String, qual: Word, args: List[Word])(using Context)(using UniqueName): Text =
    def binary(op: String): Text =
      val arg :: Nil = args: @unchecked
      run(qual): v1 =>
        run(arg): v2 =>
          cont("(" ~ v1 ~ " " ~ op ~ " " ~ v2 ~ ")")

    def unary(rubyCode: Text => Text): Text =
      run(qual): v =>
        rubyCode(v)

    name match
      case "+"    => binary("+")
      case "-"    => binary("-")
      case "*"    => binary("*")
      case "/"    => binary("/")
      case "%"    => binary("%")
      case ">"    => binary(">")
      case "<"    => binary("<")
      case ">="   => binary(">=")
      case "<="   => binary("<=")
      case "=="   => binary("==")
      case "!="   => binary("!=")
      case ">>"   => binary(">>")
      case "<<"   => binary("<<")
      case "&"    => binary("&")
      case "|"    => binary("|")
      case "^"    => binary("^")
      case "toChar"   => unary(v => cont(v))
      case "toByte"   => unary(v => cont("(" ~ v ~ " & 0xFF)"))
      case "toFloat"  => unary(v => cont(v ~ ".to_f"))
      case "toInt"    => unary(v => cont(v))
      case "toString" => unary(v => cont(v ~ ".to_s"))
      case _ => throw new Exception(s"Unknown Int method: $name")
    end match
  end callIntPrimitive

  /** Compile Char method calls to Ruby operators */
  def callCharPrimitive(name: String, qual: Word, args: List[Word])(using Context)(using UniqueName): Text =
    name match
      case "toString" =>
        run(qual): v =>
          cont(v ~ ".chr")

      case _ => callIntPrimitive(name, qual, args)
    end match
  end callCharPrimitive

  /** Compile Float method calls to Ruby operators */
  def callFloatPrimitive(name: String, qual: Word, args: List[Word])(using Context)(using UniqueName): Text =
    def binary(op: String): Text =
      val arg :: Nil = args: @unchecked
      run(qual): v1 =>
        run(arg): v2 =>
          cont("(" ~ v1 ~ " " ~ op ~ " " ~ v2 ~ ")")

    def unary(rubyCode: Text => Text): Text =
      run(qual): v =>
        rubyCode(v)

    name match
      case "+"    => binary("+")
      case "-"    => binary("-")
      case "*"    => binary("*")
      case "/"    => binary("/")
      case ">"    => binary(">")
      case "<"    => binary("<")
      case ">="   => binary(">=")
      case "<="   => binary("<=")
      case "=="   => binary("==")
      case "!="   => binary("!=")
      case "toInt" => unary(v => cont(v ~ ".to_i"))
      case "toString" => unary(v => cont(v ~ ".to_s"))
      case _ => throw new Exception(s"Unknown Float method: $name")
    end match
  end callFloatPrimitive

  def bnot(args: List[Word])(using Context)(using UniqueName): Text =
    val operand :: Nil = args: @unchecked
    run(operand): v =>
      cont("(!" ~ v  ~ ")")

  def call(fun: Word, args: List[Word])(using Context)(using UniqueName): Text =
    fun match
      case Ident(sym) =>
        if sym.owner == defn.Bool then
          callBoolPrimitive(sym, args)

        else if sym == runtime.ruby then
          val Literal(Constant.String(code)) :: Nil = args : @unchecked
          cont(Text(code))

        else
          call(sym, args)

      case Select(qual, name) if qual.tpe.isSubtype(defn.IntType) =>
        // Handle Int method calls with Ruby operators
        callIntPrimitive(name, qual, args)

      case Select(qual, name) if qual.tpe.isSubtype(defn.ByteType) =>
        // Handle Byte method calls (with numeric coercion to Int)
        callIntPrimitive(name, qual, args)

      case Select(qual, name) if qual.tpe.isSubtype(defn.CharType) =>
        // Handle Char method calls
        callCharPrimitive(name, qual, args)

      case Select(qual, name) if qual.tpe.isSubtype(defn.FloatType) =>
        // Handle Float method calls with Ruby operators
        callFloatPrimitive(name, qual, args)

      case Select(qual, name) if qual.tpe.isSubtype(defn.StringType) =>
        // Lower String operations to runtime calls
        val stringOpSym = runtime.StringOps.termMember(name)
        call(stringOpSym, qual :: args)

      case Select(qual, name) =>
        // Regular method/function call on an object
        run(qual): v =>
          val memberName = fun.tpe match
            case Types.MemberRef(_, sym) => rubyMemberName(sym)
            case _ => name

          if args.isEmpty then
            cont(v ~ "." ~ memberName ~ "()", sideEffect = true)
          else
            run(args): vs =>
              cont(v ~ "." ~ memberName ~ "(" ~ vs.join(", ") ~ ")", sideEffect = true)

      case TypeApply(fun2, _) =>
        // Strip type application (Ruby doesn't have generics) and recurse
        call(fun2, args)

      case Encoded(repr) =>
        // Strip encoding and recurse
        call(repr, args)

      case _ =>
        // For complex expressions, assume they evaluate to lambdas and use .call() syntax
        run(fun): f =>
          if args.isEmpty then
            cont(f ~ ".call()", sideEffect = true)
          else
            run(args): vs =>
              cont(f ~ ".call(" ~ vs.join(", ") ~ ")", sideEffect = true)

  def call(sym: Symbol, args: List[Word])(using Context)(using UniqueName): Text =
    if args.isEmpty then
      cont(sym ~ "()", sideEffect = true)
    else
      run(args): vs =>
        cont(sym ~ "(" ~ vs.join(", ") ~ ")", sideEffect = true)

object RubyOptimized:
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

package js

import sast.*
import sast.Trees.*
import sast.Symbols.*

import common.Debug
import common.StringUtil
import common.Text
import common.Text.*
import common.UniqueName
import common.WorkList

import JSOptimized.encodeSymbolic

import java.io.PrintWriter
import scala.collection.mutable

/**
  * JavaScript platform with code optimization
  */
class JSOptimized(outFile: String, runtime: JSRuntime, rewire: Map[Symbol, Symbol])(using defn: Definitions):
  private val reservedNames = new UniqueName

  val keywords = List(
    "for", "while", "function", "var", "let", "break", "continue", "if",
    "const", "class", "constructor", "with", "this", "Buffer", "require"
  )

  // Make keywords unavailable
  for word <- keywords do reservedNames.freshName(word)

  // Make runtime symbols unavailable
  for name <- runtime.runtimeNames do reservedNames.freshName(name)

  val globalScope = reservedNames.newScope
  var localScope = reservedNames.newScope // reset to `reservedNames.newScope` for each function

  private val symbol2UniqueName: mutable.Map[Symbol, String] = mutable.Map.empty

  def jsName(sym: Symbol): String =
    symbol2UniqueName.get(sym) match
      case Some(name) => name

      case None =>
        rewire.get(sym) match
          case Some(target) => jsName(target)

          case None =>
            val rawName = sym.fullName
            val uniqueName =
              if sym.isMethod then
                if sym.name == "constructor" then globalScope.freshName("constructor")
                else encodeSymbolic(sym.name)
              else if sym.isLocal then
                localScope.freshName(encodeSymbolic(rawName))
              else
                globalScope.freshName(encodeSymbolic(rawName))

            symbol2UniqueName(sym) = uniqueName

            // Add function or class to work list
            if (sym.isFunction && !sym.owner.isOneOf(Flags.Class | Flags.Interface)) || sym.isClass then
              workList.add(sym)

            uniqueName

  //----------------------------------------------------------------------------

  given Text.Maker[Word] = word =>
    val ctx = new StatContext(() => Text.Empty)
    compile(word)(using ctx)

  given Text.Maker[Symbol] = sym => Text(jsName(sym))

  //----------------------------------------------------------------------------

  /** A context where a result value is expected to continue execution
    *
    * @param isLast whether the context is the last poistion of an expression
    *
    * Typically, the continuation should be called with a value as it is the
    * the case in big-step semantics. It can be literals, immutable variable
    * names, etc., which are free of side effects.
    *
    * Functions calls, mutable variable references will change semantics except
    * in last positions.
    *
    * The flag `isLast` is intented to generate better JS code. A function call
    * in a last position does not need to introduce a temporary because no
    * later calls can beyond the last position.
    *
    * Typical last positions are assignments and statements in blocks.
    */
  case class ValueContext(cont: Text => Text, isLast: Boolean)

  /** A statement context where no result value is expected to continue execution */
  case class StatContext(cont: ()=> Text)

  type Context = ValueContext | StatContext

  def cont(text: Text, sideEffect: Boolean = false)(using cont1: Context): Text =
    cont1 match
      case ValueContext(cont2, isLast) =>
        if isLast || !sideEffect then
          cont2(text)
        else
          val resName = localScope.freshName("res")
          "const " ~ resName ~ " = " ~ text ~ ";" ~ Text.BreakLine
          ~ cont2(Text(resName))

      case StatContext(cont2)  =>
        text ~ cont2()

  def cont()(using cont1: Context): Text =
    cont1 match
      case ValueContext(_, _) => throw new Exception("Value expected, found none")
      case StatContext(cont2)  => cont2()

  def run(expr: Word)(cont1: Text => Text): Text =
    compile(expr)(using ValueContext(cont1, isLast = false))

  def runLast(expr: Word)(cont1: Text => Text): Text =
    compile(expr)(using ValueContext(cont1, isLast = true))

  def run(exprs: List[Word])(c: List[Text] => Text): Text =
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

    workList.add(runtime.JS_start)

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

    pw.append("(function() {")

    // runtime code
    pw.append(indent(runtime.globalDefCode).toString)

    // user code
    workList.run: sym =>
      val code =
         if sym.isFunction then
           compileFunction(funDefMap(sym))

         else if sym.isClass then
           compileClass(classDefMap(sym))

         else
           throw new Exception("Symbol is neither a function nor class: " + sym)

      val text = indent(Text.BreakLine ~ code)
      pw.append(text.toString)

    val mainCall = indent(Text.BreakLine ~ runtime.JS_start ~ "();")
    pw.append(mainCall.toString)

    pw.append("})()")

    pw.close()

  def compile(word: Word)(using Context): Text = Debug.trace("Compiling " + word.show, enable = false):
    word match
      case Literal(c)  =>
        c match
          case Constant.Bool(b) => cont(Text(b.toString))

          case Constant.String(s) =>
            cont("\"" ~ StringUtil.escape(s) ~ "\"")

          case Constant.Int(n) =>
            // JS does not have char literal
            cont(Text(n.toString))

      case RecordLit(fields) =>
        run(fields.map(_._2)): vs =>
          val fields2 = fields.map(_._1).zip(vs).map(encodeSymbolic(_) ~ ": " ~ _)
          cont("{" ~ fields2.join(", ") ~ "}")

      case Select(qual, name) =>
        run(qual): v =>
          val memberName = word.tpe match
            case Types.MemberRef(_, sym) if qual.tpe.isClassInfoType && sym.isMethod => jsName(sym)
            case _ => encodeSymbolic(name)

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
          repr ~ ";" ~ cont()

        else
          run(repr): v =>
            cont(v)

      case Apply(Select(New(classRef, _), _), args, autos) =>
        run(args ++ autos): vs =>
          val newExpr = "new " ~ jsName(classRef.symbol) ~ "(" ~ vs.join(", ") ~ ")"
          cont(newExpr, sideEffect = true)

      case Apply(fun, args, autos) =>
        call(fun, args ++ autos)

      case TypeApply(fun, _) =>
        compile(fun)

      case Assign(Ident(sym), rhs) =>
        runLast(rhs): t =>
          if sym.isMutable then
            sym ~ " = " ~ t ~ ";" ~ cont()
          else
            // Use `var` because pattern desugared variables are out of scope.
            //
            // Uniqueness of symbol names is guaranteed by the name generator.
            "var " ~ sym ~ " = " ~ t ~ ";" ~ cont()

      case FieldAssign(Select(qual, name), rhs) =>
        run(qual): v1 =>
          runLast(rhs): v2 =>
            v1 ~ "." ~ encodeSymbolic(name) ~ " = " ~ v2 ~ cont()

      case If(cond, thenp, elsep) =>
        run(cond): v =>
          if word.tpe.isValueType then
            val resName = localScope.freshName("res")
            "let " ~ resName ~ ";" ~ Text.BreakLine ~
            "if (" ~ v ~ ")" ~ " {" ~ indent:
                run(thenp): v =>
                  resName ~ " = " ~ v ~ ";"
            ~ "}" ~ " else {" ~ indent:
                run(elsep): v =>
                  resName ~ " = " ~ v ~ ";"
            ~ "}" ~ Text.BreakLine ~
            cont(Text(resName))

          else
            "if (" ~ v ~ ")" ~ " {" ~
               indent(thenp)
            ~ "}" ~ (if elsep.isEmpty then Text.Empty else " else {" ~
               indent(elsep)
            ~ "}")
            ~ cont()

      case While(cond, body) =>
        "while (true) {" ~ indent:
          run(cond): c =>
            "if (!" ~ c ~ ") break;" ~ Text.BreakLine ~ body
        ~ "}"
        ~ cont()

      case Ident(sym) =>
        assert(!sym.is(Flags.Context), "Unexpected context parameter")
        cont(Text(sym), sideEffect = sym.isMutable)

      case _: TypeDef =>
        cont()

      case _: Def       |  _: With | _: Allow | _: Object | _: Match |
           _: TaggedLit |  _: New =>

        throw new Exception("Unexpected " + word)

  /** Compile a function */
  def compileFunction(fdef: FunDef): Text = try
    val sym = fdef.symbol

    val funType = sym.info.asProcType
    val resCount = funType.resCount

    val prefix =
      if sym.isConstructor then
        Text("constructor")

      else
        // create the name outside of the new scope to avoid conflicting names
        val jsFunName = jsName(sym)
        if sym.isMethod then Text(jsFunName) else "function " ~ jsFunName

    localScope = reservedNames.newScope

    val locals = fdef.locals.filter(_.isMutable).map(sym => "var " ~ jsName(sym) ~ ";" ~ Text.BreakLine)
    prefix ~ "(" ~ fdef.allParams.join(", ") ~ ")" ~ " {" ~ indent:
        if resCount == 0 then
          locals.join(Text.Empty) ~ fdef.body
        else
          locals.join(Text.Empty) ~ runLast(fdef.body) { v =>
            "return " ~ v ~ ";" ~  Text.BreakLine
          }
    ~ "}"
  catch case ex: Exception =>
    println(fdef.body.show)
    throw ex

  /** Compile a class */
  def compileClass(cdef: ClassDef): Text =
    val classSym = cdef.symbol
    val jsClassName = jsName(classSym)

    symbol2UniqueName(cdef.self) = "this"

    // Generate class member names in a fresh scope
    localScope = reservedNames.newScope
    for fdef <- cdef.funs do jsName(fdef.symbol)

    "class " ~ jsClassName ~ " {" ~ indent:
      cdef.funs.map(compileFunction).join(Text.BlankLine)
    ~ "}"


  def div(args: List[Word])(using Context): Text =
    val a :: b :: Nil = args: @unchecked
    run(a): v1 =>
      run(b): v2 =>
        cont("((" ~ v1 ~ " / " ~ v2 ~ ")" ~ " >> 0" ~ ")")

  def bnot(args: List[Word])(using Context): Text =
    val operand :: Nil = args: @unchecked
    run(operand): v =>
      cont("(!" ~ v  ~ ")")

  def call(fun: Word, args: List[Word])(using Context): Text =
    fun match
      case Ident(sym) =>
        if sym.owner == defn.Int || sym.owner == defn.Bool then
          callPrimitive(sym, args)

        else if sym == runtime.JS_js then
          val Literal(Constant.String(code)) :: Nil = args : @unchecked
          cont(Text(code))

        else
          call(sym, args)

      case _ =>
        run(fun): v =>
          run(args): vs =>
            val call = v ~ "(" ~ vs.join(", ") ~ ")"
            if fun.tpe.asProcType.resCount == 1 then
              cont(call, sideEffect = true)
            else
              call ~ ";"  ~ cont()

  /** Compile a primitive */
  def call(sym: Symbol, args: List[Word])(using Context): Text =
    run(args): vs =>
      val call = sym ~ "(" ~ vs.join(", ") ~ ")"
      if sym.info.asProcType.resCount == 1 then
        cont(call, sideEffect = true)
      else
        call ~ ";" ~ cont()

  /** Compile a primitive */
  def callPrimitive(sym: Symbol, args: List[Word])(using Context): Text =

    def binary(op: String): Text =
      val a :: b :: Nil = args: @unchecked
      run(a): v1 =>
        run(b): v2 =>
          cont("(" ~ v1 ~ " " ~ op ~ " " ~ v2 ~ ")")

    sym match
      case defn.Int_add    =>   binary("+")
      case defn.Int_sub    =>   binary("-")
      case defn.Int_mul    =>   binary("*")
      case defn.Int_div    =>   div(args)
      case defn.Int_mod    =>   binary("%")
      case defn.Int_eql    =>   binary("===")
      case defn.Int_gt     =>   binary(">")
      case defn.Int_lt     =>   binary("<")
      case defn.Int_ge     =>   binary(">=")
      case defn.Int_le     =>   binary("<=")
      case defn.Int_srl    =>   binary(">>")
      case defn.Int_sll    =>   binary("<<")
      case defn.Int_land   =>   binary("&")
      case defn.Int_lor    =>   binary("|")
      case defn.Int_lxor   =>   binary("^")

      case defn.Bool_both   =>   binary("&&")
      case defn.Bool_either =>   binary("||")
      case defn.Bool_not    =>   bnot(args)

      case _ => call(sym, args)
    end match
  end callPrimitive


end JSOptimized

object JSOptimized:
  def encodeSymbolic(operator: String): String =
    val sb = new StringBuilder
    for c <- operator do sb.append(encodeOperatorChar(c))
    sb.toString

  def encodeOperatorChar(c: Char): String =
    if isDigit(c) || isLetter(c) || c == '_' then
      c.toString
    else
      extension (base: String) def wrap: String = "_" + base + "_"

      c match
        case '+' => "plus".wrap
        case '-' => "minus".wrap
        case '*' => "mul".wrap
        case '/' => "div".wrap
        case '%' => "mod".wrap
        case '|' => "or".wrap
        case '&' => "and".wrap
        case '^' => "xor".wrap
        case '>' => "gt".wrap
        case '<' => "lt".wrap
        case '=' => "eq".wrap
        case '!' => "not".wrap
        case '$' => "dollar".wrap
        case '?' => "question".wrap
        case ':' => "colon".wrap
        case '~' => "tilde".wrap
        case '.' => "_"
        case _   => throw new Exception("Not supported, c = " + c)

  def isDigit(c: Char): Boolean =
    c >= '0' && c <= '9'

  def isLetter(c: Char): Boolean =
    c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z'

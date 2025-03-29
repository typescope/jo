package js

import sast.*
import sast.Sast.*
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
class JSOptimized(outFile: String, runtime: JSRuntime):
  private val unique = new UniqueName

  val defn = Definitions.instance

  val keywords = List(
    "for", "while", "function", "var", "let", "break", "continue", "if",
    "const", "class", "constructor", "with", "this", "Buffer", "require"
  )

  // Make keywords unavailable
  for word <- keywords do unique.freshName(word)

  private val symbol2UniqueName: mutable.Map[Symbol, String] = mutable.Map.empty

  def jsName(sym: Symbol): String =
    symbol2UniqueName.get(sym) match
      case Some(name) => name

      case None =>
        runtime.link(sym) match
          case Some(sym) => jsName(sym)

          case None =>
            val rawName = if sym.isFunction then sym.fullName else sym.name
            val uniqueName = unique.freshName(encodeSymbolic(rawName))
            symbol2UniqueName(sym) = uniqueName

            // Add function to work list
            if sym.isFunction then
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
          val resName = unique.freshName("res")
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

    // Make runtime symbols unavailable
    for name <- runtime.runtimeNames do unique.freshName(name)

    workList.add(runtime.JS_start)

    val symbolDefMap = mutable.Map.empty[Symbol, FunDef]
    for
      ns <- nss
      case fdef: FunDef <- ns.defs
    do
      symbolDefMap(fdef.symbol) = fdef

    pw.append("(function() {")

    // runtime code
    pw.append(indent(runtime.globalDefCode).toString)

    // user code
    workList.run: funSym =>
      val funText = indent(Text.BreakLine ~ compile(symbolDefMap(funSym)))
      pw.append(funText.toString)

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
          cont("{" ~ rep(fields2, Text(", ")) ~ "}")

      case Select(qual, name) =>
        run(qual): v =>
          cont(v ~ "." ~ encodeSymbolic(name))

      case Block(words) =>
        words match
          case Nil =>
            cont()

          case _ =>
            if word.tpe.isValueType then
              val stats :+ expr = words: @unchecked
              val sep = if stats.isEmpty then Text.Empty else Text.BreakLine
              rep(stats, Text.BreakLine) ~ sep ~ compile(expr)

            else
              rep(words, Text.BreakLine) ~ cont()

      case encoded @ Encoded(repr) =>
        if encoded.isValueDrop then
          repr ~ ";" ~ cont()
        else
          run(repr): v =>
            cont(v)

      case app @ Apply(fun, args) =>
        call(fun, args)

      case TypeApply(fun, _) =>
        compile(fun)

      case Assign(Ident(sym), rhs) =>
        runLast(rhs): t =>
          if sym.isMutable then
            sym ~ " = " ~ t ~ ";" ~ cont()
          else
            "const " ~ sym ~ " = " ~ t ~ ";" ~ cont()

      case FieldAssign(qual, name, rhs) =>
        runLast(qual): v =>
          v ~ "." ~ encodeSymbolic(name) ~ " = " ~ rhs ~ cont()

      case If(cond, thenp, elsep) =>
        run(cond): v =>
          if word.tpe.isValueType then
            val resName = unique.freshName("res")
            "var " ~ resName ~ ";" ~ Text.BreakLine ~
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
        assert(!sym.isAllOf(Flags.Context | Flags.Param), "Unexpected context parameter")
        cont(Text(sym), sideEffect = sym.isMutable)

      case _: TypeDef =>
        cont()

      case _: ValDef | _: FunDef |  _: With | _: Allow | _: Object =>
        throw new Exception("Unexpected " + word)

  /** Compile a function */
  def compile(fdef: FunDef): Text =
    val sym = fdef.symbol

    val funType = sym.info.asProcType
    val resCount = funType.resCount

    // create the name outside of the new scope to avoid conflicting names
    val jsFunName = jsName(sym)

    unique.newScope:
      val locals = fdef.locals.filter(_.isMutable).map("var " ~ _ ~ ";" ~ Text.BreakLine)
      "function " ~ jsFunName ~ "(" ~ rep(fdef.params, Text(", ")) ~ ")" ~ " {" ~ indent:
          if resCount == 0 then
            rep(locals, Text.Empty) ~ fdef.body
          else
            rep(locals, Text.Empty) ~ runLast(fdef.body) { v =>
              "return " ~ v ~ ";" ~  Text.BreakLine
            }
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
      case Ident(sym) if sym.owner == Definitions.instance.Predef =>
        callPredef(sym, args)

      case Ident(sym) if sym == runtime.JS_js  =>
        val Literal(Constant.String(code)) :: Nil = args : @unchecked
        cont(Text(code))

      case _ =>
        run(fun): v =>
          run(args): vs =>
            val call = v ~ "(" ~ rep(vs, Text(", ")) ~ ")"
            if fun.tpe.asProcType.resCount == 1 then
              cont(call, sideEffect = true)
            else
              call ~ ";"  ~ cont()

  /** Compile a primitive */
  def call(sym: Symbol, args: List[Word])(using Context): Text =
    run(args): vs =>
      val call = sym ~ "(" ~ rep(vs, Text(", ")) ~ ")"
      if sym.info.asProcType.resCount == 1 then
        cont(call, sideEffect = true)
      else
        call ~ ";" ~ cont()

  /** Compile a primitive */
  def callPredef(sym: Symbol, args: List[Word])(using Context): Text =
    val defn = Definitions.instance

    def binary(op: String): Text =
      val a :: b :: Nil = args: @unchecked
      run(a): v1 =>
        run(b): v2 =>
          cont("(" ~ v1 ~ " " ~ op ~ " " ~ v2 ~ ")")

    sym match
      case defn.Predef_add    =>   binary("+")
      case defn.Predef_sub    =>   binary("-")
      case defn.Predef_mul    =>   binary("*")
      case defn.Predef_div    =>   div(args)
      case defn.Predef_mod    =>   binary("%")
      case defn.Predef_gt     =>   binary(">")
      case defn.Predef_lt     =>   binary("<")
      case defn.Predef_ge     =>   binary(">=")
      case defn.Predef_le     =>   binary("<=")
      case defn.Predef_srl    =>   binary(">>")
      case defn.Predef_sll    =>   binary("<<")
      case defn.Predef_land   =>   binary("&")
      case defn.Predef_lor    =>   binary("|")
      case defn.Predef_lxor   =>   binary("^")
      case defn.Predef_bnot   =>   bnot(args)
      case defn.Predef_eql    =>   binary("===")

      case _ => call(sym, args)
    end match
  end callPredef


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
        case '.' => "_"
        case _   => throw new Exception("Not supported, c = " + c)

  def isDigit(c: Char): Boolean =
    c >= '0' && c <= '9'

  def isLetter(c: Char): Boolean =
    c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z'

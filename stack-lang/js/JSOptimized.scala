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
    "const", "class", "constructor", "with", "this"
  )

  // Make keywords unavailable
  for word <- keywords do unique.freshName(word)

  private val symbol2UniqueName: mutable.Map[Symbol, String] = mutable.Map.empty

  def jsName(sym: Symbol): String =
    symbol2UniqueName.get(sym) match
      case Some(name) => name

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

  case class ValueContext(cont: Text => Text)
  case class StatContext(cont: ()=> Text)
  type Context = ValueContext | StatContext

  def cont(text: Text)(using cont1: Context): Text =
    cont1 match
      case ValueContext(cont2) => cont2(text)
      case StatContext(cont2)  => text ~ cont2()

  def cont()(using cont1: Context): Text =
    cont1 match
      case ValueContext(cont2) => throw new Exception("Value expected, found none")
      case StatContext(cont2)  => cont2()

  def run(expr: Word)(cont1: Text => Text): Text =
    compile(expr)(using ValueContext(cont1))

  def run(exprs: List[Word])(c: List[Text] => Text): Text =
    exprs match
      case Nil => c(Nil)
      case expr :: exprs =>
        run(expr): t =>
          run(exprs): ts =>
            c(t :: ts)

  //----------------------------------------------------------------------------
  val workList = new WorkList[Symbol]

  def compile(nss: List[Namespace], main: Symbol): Unit =
    val pw =  new PrintWriter(outFile)

    // Make runtime symbols unavailable
    for name <- runtime.runtimeNames do unique.freshName(name)

    workList.add(main)

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

    val mainCall = indent(Text.BreakLine ~ main ~ "();")
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
              rep(stats, Text.BreakLine) ~ sep ~ run(expr): v =>
                cont(v)

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
        run(rhs): t =>
          if sym.isMutable then
            sym ~ " = " ~ t ~ ";" ~ cont()
          else
            "const " ~ sym ~ " = " ~ t ~ ";" ~ cont()

      case FieldAssign(qual, name, rhs) =>
        run(qual): v =>
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
        run(cond): t =>
          "while (" ~ t ~ ") {" ~ indent(body) ~ "}"
          ~ cont()

      case Ident(sym) =>
        assert(!sym.isAllOf(Flags.Context | Flags.Param), "Unexpected context parameter")
        cont(Text(sym))

      case _: TypeDef =>
        cont()

      case _: ValDef | _: FunDef |  _: With | _: Object =>
        throw new Exception("Unexpected " + word)

  /** Compile a function */
  def compile(fdef: FunDef): Text =
    val sym = fdef.symbol

    val funType = TypeOps.erasePolyType(sym.info).asProcType
    val resCount = funType.resCount

    // create the name outside of the new scope to avoid conflicting names
    val jsFunName = jsName(sym)

    unique.newScope:
      val locals = fdef.locals.filter(_.isMutable).map("var " ~ _ ~ ";" ~ Text.BreakLine)
      "function " ~ jsFunName ~ "(" ~ rep(fdef.params, Text(", ")) ~ ")" ~ " {" ~ indent:
          if resCount == 0 then
            rep(locals, Text.Empty) ~ fdef.body
          else
            rep(locals, Text.Empty) ~ run(fdef.body) { v =>
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

      case _ =>
        run(fun): v =>
          run(args): vs =>
            val call = v ~ "(" ~ rep(vs, Text(", ")) ~ ")"
            if fun.tpe.asProcType.resCount == 1 then
              val resName = unique.freshName("res")
              "const " ~ resName ~ " = " ~ call ~ ";" ~ Text.BreakLine
              ~ cont(Text(resName))
            else
              call ~ ";"  ~ cont()

  /** Compile a primitive */
  def call(sym: Symbol, args: List[Word])(using Context): Text =
    run(args): vs =>
      val call = sym ~ "(" ~ rep(vs, Text(", ")) ~ ")"
      if sym.info.asProcType.resCount == 1 then
        val resName = unique.freshName("res")
        "const " ~ resName ~ " = " ~ call ~ ";" ~ Text.BreakLine ~ cont(Text(resName))
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
      case defn.Predef_band   =>   binary("&&")
      case defn.Predef_bor    =>   binary("||")
      case defn.Predef_bnot   =>   bnot(args)
      case defn.Predef_eql    =>   binary("===")

      case defn.Predef_js  =>
        val Literal(Constant.String(code)) :: Nil = args : @unchecked
        cont(Text(code))

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
        case '.' => "_"
        case _   => throw new Exception("Not supported, c = " + c)

  def isDigit(c: Char): Boolean =
    c >= '0' && c <= '9'

  def isLetter(c: Char): Boolean =
    c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z'

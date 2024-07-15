import java.io.PrintWriter
import scala.collection.mutable

import Sast.*
import Symbols.*
import Types.*
import Text.*

import JSOptimized.encodeSymbolic

/**
  * JavaScript platform with code optimization
  */
class JSOptimized(outFile: String):
  private  val uniqueName = new UniqueName
  export uniqueName.freshName

  // Make keywords unavailable
  for word <- List(
      "for", "while", "function", "var", "let", "break", "continue", "if",
      "const", "class", "constructor")
  do
    freshName(word)

  private val symbol2UniqueName: mutable.Map[Symbol, String] = mutable.Map(
    predef.p -> "console.log"
  )

  def jsName(sym: Symbol): String =
    symbol2UniqueName.get(sym) match
      case Some(name) => name

      case None =>
        val uniqueName = freshName(encodeSymbolic(sym.name))
        symbol2UniqueName(sym) = uniqueName
        uniqueName

  //----------------------------------------------------------------------------

  given Text.Maker[Word] = word =>
    val ctx: Context = vs => vs.headOption.getOrElse(Text.Empty)
    compile(word)(using ctx)

  given Text.Maker[Symbol] = sym => Text(jsName(sym))

  given Text.Maker[FunDef] = fdef => compile(fdef)

  given Text.Maker[ValDef] = vdef => "var " ~ vdef.symbol ~ ";"

  //----------------------------------------------------------------------------

  type Context = List[Text] => Text

  def cont(text: Text)(using cont: Context): Text = cont(text :: Nil)
  def cont()(using cont: Context): Text = cont(Nil)
  def cont(expr: Word)(cont: Text => Text): Text =
    val cont2: Context = (vs: List[Text]) =>
      val text :: Nil = vs: @unchecked
      cont(text)
    compile(expr)(using cont2)

  def cont(exprs: List[Word])(c: List[Text] => Text): Text =
    exprs match
      case Nil => c(Nil)
      case expr :: exprs =>
        cont(expr): t =>
          cont(exprs): ts =>
            c(t :: ts)

  //----------------------------------------------------------------------------

  def compile(prog: Prog): Unit =
    val pw =  new PrintWriter(outFile)

    val globals = rep(prog.vals.map(_.symbol), Text.BreakLine)
    val funs = rep(prog.funs, Text.BlankLine)

    val text =
      "(function() {" ~ indent:
           globals ~ funs ~ Text.BreakLine ~ prog.main ~ ";"
      ~ "})()"

    pw.append(text.toString)
    pw.close()

  def compile(word: Word)(using Context): Text =
    word match
      case IntLit(v)  =>
        cont(Text(v))

      case BoolLit(v) =>
        cont(Text(v))

      case RecordLit(fields) =>
        cont(fields.map(_._2)): ts =>
          val fields2 = fields.map(_._1).zip(ts).map(_ ~ ": " ~ _)
          cont("{" ~ rep(fields2, Text(", ")) ~ "}")

      case Select(qual, name) =>
        cont(qual): t =>
          cont(t ~ "." ~ encodeSymbolic(name))

      case Phrase(words) =>
        words match
          case Nil =>
            cont()

          case _ =>
            if word.tpe.isValueType then
              val stats :+ expr = words: @unchecked
              val sep = if stats.isEmpty then Text.Empty else Text.BreakLine
              rep(stats, Text.BreakLine) ~ sep ~ compile(expr)

            else
              rep(words, Text.BreakLine)

      case Encoded(repr) =>
        compile(repr)

      case app @ Apply(fun, args) =>
        if app.isPrimitiveCall then
          primitive(app.primitive, args)
        else
          cont(fun): v =>
            cont(args): vs =>
              val call = v ~ "(" ~ rep(vs, Text(", ")) ~ ")"
              if app.tpe.isValueType then cont(call)
              else call ~ cont()

      case TypeApply(fun, _) =>
        compile(fun)

      case Assign(sym, rhs) =>
        cont(rhs): t =>
          sym ~ " = " ~ t ~ ";" ~ cont()

      case If(cond, thenp, elsep) =>
        cont(cond): v =>
          if word.tpe.isValueType then
            val resName = freshName("res")
            "var " ~ resName ~ ";" ~ Text.BreakLine ~
            "if (" ~ v ~ ")" ~ " {" ~ indent:
                cont(thenp): v =>
                  resName ~ " = " ~ v ~ ";"
            ~ "}" ~ " else {" ~ indent:
                cont(elsep): v =>
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
        cont(cond): t =>
          "while (" ~ t ~ ") {" ~ indent(body) ~ "}"
          ~ cont()

      case Ident(sym) =>
        cont(Text(sym))

      case _: ValDef | _: FunDef =>
        throw new Exception("Unexpected " + word)

  /** Compile a function
    *
    * Calling the passed function will compile the body of the function.
    */
  def compile(fdef: FunDef): Text =
    val sym = fdef.symbol

    val funType = TypeOps.erasePolyType(sym.info).asProcType
    val resCount = funType.resCount

    uniqueName.newScope:
      val locals = fdef.locals.map("var " ~ _ ~ ";" ~ Text.BreakLine)
      "function " ~ sym ~ "(" ~ rep(fdef.params, Text(", ")) ~ ")" ~ " {" ~ indent:
          if resCount == 0 then
            rep(locals, Text.Empty) ~ fdef.body
          else
            rep(locals, Text.Empty) ~ cont(fdef.body) { v =>
              "return " ~ v ~ ";" ~  Text.BreakLine
            }
      ~ "}"

  def div(args: List[Word])(using Context): Text =
    val a :: b :: Nil = args: @unchecked
    cont(a): v1 =>
      cont(b): v2 =>
        cont("((" ~ v1 ~ " / " ~ v2 ~ ")" ~ " >> 0" ~ ")")

  def bnot(args: List[Word])(using Context): Text =
    val operand :: Nil = args: @unchecked
    cont(operand): v =>
      cont("(!" ~ v  ~ ")")

  def abort(args: List[Word])(using Context): Text =
    val arg :: Nil = args: @unchecked
    cont(arg): v =>
      "throw "  ~ v ~ ";" ~ Text.BreakLine ~ cont(Text("null"))

  def print(args: List[Word])(using Context): Text =
    val arg :: Nil = args: @unchecked
    cont(arg): v =>
      predef.p ~ "(" ~ rep(args, Text(", "))  ~ ");" ~ cont()

  /**
    * Compile a primitive
    *
    */
  def primitive(sym: Symbol, args: List[Word])(using Context): Text =
    def binary(op: String): Text =
      val a :: b :: Nil = args: @unchecked
      cont(a): v1 =>
        cont(b): v2 =>
          cont("(" ~ v1 ~ " " ~ op ~ " " ~ v2 ~ ")")

    sym match
      case predef.add    =>   binary("+")
      case predef.sub    =>   binary("-")
      case predef.mul    =>   binary("*")
      case predef.div    =>   div(args)
      case predef.mod    =>   binary("%")
      case predef.gt     =>   binary(">")
      case predef.lt     =>   binary("<")
      case predef.ge     =>   binary(">=")
      case predef.le     =>   binary("<=")
      case predef.srl    =>   binary(">>")
      case predef.sll    =>   binary("<<")
      case predef.land   =>   binary("&")
      case predef.lor    =>   binary("|")
      case predef.lxor   =>   binary("^")
      case predef.band   =>   binary("&&")
      case predef.bor    =>   binary("||")
      case predef.bnot   =>   bnot(args)
      case predef.eql    =>   binary("===")
      case predef.p      =>   print(args)
      case runtime.abort =>   abort(args)
      case _             =>   throw new Exception("Unknown primitive: " + sym.name)
  end primitive


end JSOptimized

object JSOptimized:
  def encodeSymbolic(operator: String): String =
    val sb = new StringBuilder
    for c <- operator do sb.append(encodeOperatorChar(c))
    sb.toString

  def encodeOperatorChar(c: Char): String =
    if isDigit(c) || isLetter(c) || c == '_' then c.toString
    else c match
      case '+' => "plus"
      case '-' => "minus"
      case '*' => "mul"
      case '/' => "div"
      case '%' => "mod"
      case '|' => "or"
      case '&' => "and"
      case '^' => "xor"
      case '>' => "gt"
      case '<' => "lt"
      case '=' => "eq"
      case '!' => "not"
      case _   => throw new Exception("Not supported, c = " + c)

  def isDigit(c: Char): Boolean =
    c >= '0' && c <= '9'

  def isLetter(c: Char): Boolean =
    c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z'

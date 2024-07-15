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
  private val pw =  new PrintWriter(outFile)

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

  given Text.Maker[Word] = word => compile(word)

  given Text.Maker[Symbol] = sym => Text(jsName(sym))

  given Text.Maker[FunDef] = fdef => compile(fdef)

  given Text.Maker[ValDef] = vdef => "var " ~ vdef.symbol ~ ";"

  //----------------------------------------------------------------------------

  def compile(prog: Prog): Unit =
    val text =
      "(function() {" ~ indent:
          rep(prog.vals.map(_.symbol), Text.BreakLine) ~
              rep(prog.funs, Text.BlankLine) ~ prog.main
      ~ "})()"

    pw.append(text.toString)
    pw.close()

  def compile(word: Word): Text =
    word match
      case IntLit(v)  =>
        Text(v)

      case BoolLit(v) =>
        Text(v)

      case RecordLit(fields) =>
        given Text.Maker[(String, Word)] =
          case (name, rhs) =>
            val encodedName = encodeSymbolic(name)
            encodedName ~ ":" ~ rhs

        "{" ~ rep(fields, Text(", ")) ~ "}"

      case Select(qual, name) =>
        qual ~ "." ~ encodeSymbolic(name)

      case Phrase(words) =>
        "(" ~ rep(words, Text(", ")) ~ ")"

      case Encoded(repr) =>
        compile(repr)

      case app @ Apply(fun, args) =>
        if app.isPrimitiveCall then
          primitive(app.primitive, args)
        else
          fun ~ "(" ~ rep(args, Text(", ")) ~ ")"

      case TypeApply(fun, _) =>
        compile(fun)

      case Assign(sym, rhs) =>
        sym ~ " = " ~ rhs ~ ";"

      case If(cond, thenp, elsep) =>
        // TODO: return value
        "if (" ~ cond ~ ")" ~ " {" ~
            indent(thenp) ~
        "}" ~ " else {" ~
            indent(elsep) ~
        "}"

      case While(cond, body) =>
        "while (" ~ cond ~ ") {" ~ indent(body) ~ "}"

      case Ident(sym) =>
        Text(sym)

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

    // TODO: return
    uniqueName.newScope:
      val locals = fdef.locals.map("var " ~ _ ~ ";")
      "function " ~ sym ~ "(" ~ rep(fdef.params, Text(", ")) ~ ")" ~ " {" ~ indent:
          rep(locals, Text.BreakLine) ~ fdef.body
      ~ "}"

  def div(args: List[Word]): Text =
    val a :: b :: Nil = args: @unchecked
    "((" ~ a ~ " / " ~ b ~ ")" ~ " >> 0" ~ ")"

  def bnot(args: List[Word]): Text =
    val operand :: Nil = args: @unchecked
    "(!" ~ operand  ~ ")"

  def abort(args: List[Word]): Text =
    val operand :: Nil = args: @unchecked
    "throw "  ~ operand ~ ";"

  /**
    * Compile a primitive
    *
    */
  def primitive(sym: Symbol, args: List[Word]): Text =
    def binary(op: String): Text =
      val a :: b :: Nil = args: @unchecked
      "(" ~ a ~ op ~ b ~ ")"

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
      case predef.p      =>   predef.p ~ "(" ~ rep(args, Text(", "))  ~ ")"
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
    if isDigit(c) || isLetter(c) then c.toString
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

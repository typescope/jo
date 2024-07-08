import scala.collection.immutable.Vector

import Sast.*
import Types.Type
import Symbols.Symbol

object Printing:

  def show(word: Word): String = showWord(word).toString

  def show(prog: Prog): String = showProg(prog).toString

  //----------------------------------------------------------------------------

  // DSL for print

  enum Text:
    case BlankLine
    case BreakLine
    case Group(parts: Vector[Text])
    case Indent(text: Text)
    case Atom(content: String)
    case Empty

    def ~(that: Text): Text =
      (this, that) match
        case (Text.Empty, _) => that
        case (_, Text.Empty) => this
        case (Group(parts1), Group(parts2)) => Group(parts1 ++ parts2)
        case (Group(parts1), _) => Group(parts1 :+ that)
        case (_, Group(parts2)) => Group(this +: parts2)
        case _ => Group(Vector(this, that))

    def ~[T](that: T)(using TextMaker[T]): Text =
      this ~ Text(that)

    override def toString =
      val sb = new StringBuilder
      var indent = 0
      var isNewLine = false

      def convert(text: Text): Unit =
        text match
        case Empty =>
        case BlankLine =>
          sb.append("\n\n")
          isNewLine = true
        case BreakLine =>
          sb.append("\n")
          isNewLine = true
        case Indent(text) =>
          indent += 1
          sb.append("\n")
          isNewLine = true
          convert(text)
          sb.append("\n")
          isNewLine = true
          indent -= 1
        case Group(parts) =>
          for part <- parts
          do convert(part)
        case Atom(content) =>
          for line <- content.linesWithSeparators do
            if isNewLine then sb.append("  " * indent)
            sb.append(line)
            isNewLine = line.endsWith("\n")
      end convert
      convert(this)
      sb.toString
    end toString
  end Text

  object Text:
    def apply[T](v: T)(using maker: TextMaker[T]): Text = maker(v)

  type TextMaker[T] = (v: T) =>  Text

  given stringTextMaker: TextMaker[String] = (v) =>
    if v.isEmpty then Text.Empty
    else Text.Atom(v)

  given wordTextMaker: TextMaker[Word] = v => showWord(v)

  given defTextMaker: TextMaker[Def] = v => showDef(v)

  given typeTextMaker: TextMaker[Type] = v => Text(v.show)

  given symbolTextMaker: TextMaker[Symbol] = v => Text(v.name)

  extension [T](t: T)(using TextMaker[T])
    def ~[S](s: S)(using TextMaker[S]): Text =
      Text(t) ~ Text(s)

  def indent(text: Text): Text = Text.Indent(text)

  def rep[T](list: List[T], separator: String)(using TextMaker[T]): Text =
    rep(list, Text(separator), acc = Text.Empty)

  def rep[T](list: List[T], separator: Text, acc: Text = Text.Empty)(using maker: TextMaker[T]): Text =
    list match
    case x :: xs => rep(xs, separator, if acc == Text.Empty then maker(x) else acc ~ separator ~ maker(x))
    case Nil     => acc

  //----------------------------------------------------------------------------

  // implementation

  def showProg(prog: Prog): Text =
    rep(prog.defs, Text.BlankLine) ~ showWord(prog.main)

  def showDef(defn: Def): Text =
    defn match
      case ValDef(sym, rhs) =>
        val mod = if sym.isMutable then "var" else "val"
        mod ~ " " ~ sym.name ~ ": " ~ sym.info ~ " = " ~ rhs ~ Text.BreakLine

      case fdef: FunDef =>
        val tparams = fdef.tparams.map(sym => sym.name + " " + sym.info.show)
        val tparamStr = if tparams.isEmpty then "" else tparams.mkString("[", ", ", "]")
        val params = fdef.params.map(sym => sym.name + ": " + sym.info.show)
        val resType = TypeOps.finalResultType(fdef.symbol.info)
        val locals = rep(fdef.locals.map(sym => sym ~ ": " ~ sym.info), Text(", "))
        val captures = rep(fdef.captures, Text(", "))
        "@locals(" ~ locals ~ ")" ~ Text.BreakLine ~
        "@captures(" ~ captures ~ ")" ~ Text.BreakLine ~
        "fun " ~ fdef.name ~ " " ~ tparamStr ~ params.mkString("(", ", ", "): ") ~ resType.show ~ " ="
        ~ indent(Text(fdef.body))

      case tdef: TypeDef =>
        "type " ~ tdef.symbol.name ~ " = " ~ tdef.symbol.info.show


  def showWord(word: Word): Text =
    word match
      case IntLit(n) => Text(n.toString)

      case BoolLit(b) => Text(b.toString)

      case Ident(sym) => Text(sym.name)

      case FunRef(sym) => Text(sym.name)

      case Select(qual, name) =>
        qual ~ "." ~ name

      case RecordLit(fields) =>
        "{" ~ indent:
            rep(
              fields.map { (f, rhs) => f ~ " = " ~ rhs },
              Text(", ")
            )
        ~ "}"

      case Encoded(repr) =>
        "(" ~ repr ~ ": " ~ word.tpe ~ ")"

      case Call(fun) =>
        "=> " ~ fun

      case Assign(sym, rhs) =>
        Text.BreakLine ~ sym.name ~ " = " ~ rhs ~ Text.BreakLine

      case vdef: ValDef =>
        showDef(vdef)

      case fdef: FunDef =>
        showDef(fdef)

      case If(cond, thenp, elsep) =>
        "if " ~ cond ~ " then" ~ indent:
           showWord(thenp)
        ~ "else" ~ indent:
          showWord(elsep)

      case While(cond, body) =>
        "while " ~ cond ~ " do" ~ indent:
          showWord(body)

      case Phrase(words) =>
        if words.size == 1 then
          showWord(words.head)
        else if words.size > 1 then
          rep(words, Text(" "))
        else
          Text.Empty

/** DSL for string construction  */
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

  def ~[T](that: T)(using Text.Maker[T]): Text =
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
  def apply[T](v: T)(using maker: Maker[T]): Text = maker(v)

  type Maker[T] = (v: T) =>  Text

  given stringTextMaker: Maker[String] = (v) =>
    if v.isEmpty then Text.Empty
    else Text.Atom(v)

  extension [T](t: T)(using Maker[T])
    def ~[S](s: S)(using Maker[S]): Text =
      Text(t) ~ Text(s)

  def indent(text: Text): Text = Text.Indent(text)

  def rep[T](list: List[T], separator: String)(using Maker[T]): Text =
    rep(list, Text(separator), acc = Text.Empty)

  def rep[T](list: List[T], separator: Text, acc: Text = Text.Empty)(using maker: Maker[T]): Text =
    list match
    case x :: xs => rep(xs, separator, if acc == Text.Empty then maker(x) else acc ~ separator ~ maker(x))
    case Nil     => acc

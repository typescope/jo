package common

/** DSL for string construction  */
enum Text:
  case BlankLine
  case BreakLine
  case Group(parts: Vector[Text])
  case Indent(text: Text)
  case Atom(content: String)
  case Empty

  def join(that: Text): Text =
    (this, that) match
      case (Text.Empty, _) => that
      case (_, Text.Empty) => this
      case (Group(parts1), Group(parts2)) => Group(parts1 ++ parts2)
      case (Group(parts1), _) => Group(parts1 :+ that)
      case (_, Group(parts2)) => Group(this +: parts2)
      case _ => Group(Vector(this, that))

  override def toString() =
    val sw = new java.io.StringWriter()
    val pw = new java.io.PrintWriter(sw)
    write(pw)
    sw.toString

  def write(pw: java.io.PrintWriter): Unit =
    var indent = 0
    var isNewLine = false

    def convert(text: Text): Unit =
      text match
      case Empty =>
      case BlankLine =>
        pw.println()
        pw.println()
        isNewLine = true
      case BreakLine =>
        pw.println()
        isNewLine = true
      case Indent(text) =>
        indent += 1
        pw.println()
        isNewLine = true
        convert(text)
        pw.println()
        isNewLine = true
        indent -= 1
      case Group(parts) =>
        for part <- parts
        do convert(part)
      case Atom(content) =>
        for line <- content.linesWithSeparators do
          if isNewLine then pw.print("  " * indent)
          pw.print(line)
          isNewLine = line.endsWith("\n")
    end convert
    convert(this)

end Text

object Text:
  def apply[T](v: T)(using maker: Maker[T]): Text = maker(v)

  type Maker[T] = (v: T) =>  Text

  given Maker[String] = (v) =>
    if v.isEmpty then Text.Empty
    else Text.Atom(v)

  given Maker[Text] = (v) => v

  given Maker[Int] = (v) => Text.Atom(v.toString)

  given Maker[Boolean] = (v) => Text.Atom(v.toString)

  extension [T](t: T)(using Maker[T])
    def ~[S](s: S)(using Maker[S]): Text =
      Text(t).join(Text(s))

  extension [T](list: Seq[T])
    def join(sep: String)(using Maker[T]): Text =
      rep(list, Text(sep))

    def join(sep: Text)(using Maker[T]): Text =
      rep(list, sep)

  def indent[T](v: T)(using Maker[T]): Text = Text.Indent(Text(v))

  private def rep[T](list: Seq[T], separator: Text, acc: Text = Text.Empty)(using maker: Maker[T]): Text =
    list match
    case x :: xs => rep(xs, separator, if acc == Text.Empty then maker(x) else acc ~ separator ~ maker(x))
    case Nil     => acc

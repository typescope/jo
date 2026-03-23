package tool.toml

/** Minimal TOML value ADT covering the subset used by jo.toml / jo.lock / meta.toml */
enum TomlValue:
  case Str(value: String)
  case Integer(value: Long)
  case Bool(value: Boolean)
  case Arr(items: List[TomlValue])
  case Tbl(fields: Map[String, TomlValue])

/** A TOML document is a top-level table mapping keys to values. */
type TomlDoc = Map[String, TomlValue]

/** Parse or decode error with a line number (1-based; 0 = unknown / decode phase). */
case class TomlError(message: String, line: Int = 0) extends Exception(
  if line > 0 then s"line $line: $message" else message
)

object TomlPrinter:
  def print(doc: TomlDoc): String =
    val sb = new StringBuilder
    printFields(doc, sb, indent = 0)
    sb.toString.stripTrailing()

  private def printFields(fields: Map[String, TomlValue], sb: StringBuilder, indent: Int): Unit =
    val pad = "  " * indent
    for (k, v) <- fields.toSeq.sortBy(_._1) do
      v match
        case TomlValue.Tbl(nested) =>
          sb.append(s"$pad$k:\n")
          printFields(nested, sb, indent + 1)
        case TomlValue.Arr(items) if items.forall(_.isInstanceOf[TomlValue.Tbl]) =>
          for item <- items do
            sb.append(s"$pad$k:\n")
            printFields(item.asInstanceOf[TomlValue.Tbl].fields, sb, indent + 1)
        case _ =>
          sb.append(s"$pad$k = ${printValue(v)}\n")

  private def printValue(v: TomlValue): String = v match
    case TomlValue.Str(s)      => s"\"${s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\t", "\\t")}\""
    case TomlValue.Integer(n)  => n.toString
    case TomlValue.Bool(b)     => b.toString
    case TomlValue.Arr(items)  => items.map(printValue).mkString("[", ", ", "]")
    case TomlValue.Tbl(fields) => fields.map((k, v) => s"$k = ${printValue(v)}").mkString("{", ", ", "}")

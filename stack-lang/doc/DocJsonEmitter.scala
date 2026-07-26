package doc

import sast.Constant

import java.io.PrintWriter

object DocJsonEmitter:
  def emit(entries: List[DocEntry], out: PrintWriter): Unit =
    out.println("[")
    emitEntryList(entries, out, "  ")
    if entries.nonEmpty then out.println()
    out.println("]")

  private def emitEntryList(entries: List[DocEntry], out: PrintWriter, indent: String): Unit =
    var first = true
    for entry <- entries do
      if !first then out.println(",")
      first = false
      emitEntry(entry, out, indent)

  private def emitEntry(entry: DocEntry, out: PrintWriter, indent: String): Unit =
    val next = indent + "  "
    out.println(indent + "{")
    emitField("name", JsonUtil.string(entry.name), out, next)
    emitField("kind", JsonUtil.string(entry.kind), out, next)
    emitField("signature", JsonUtil.string(entry.signature), out, next)
    emitField("source", sourceJson(entry.source), out, next)
    emitField("visibility", JsonUtil.string(entry.visibility), out, next)
    emitField("flags", stringArray(entry.flags), out, next)
    emitField("annotations", annotationsJson(entry.annotations), out, next)
    emitField("doc", entry.doc.map(JsonUtil.string).getOrElse("null"), out, next, comma = entry.views.nonEmpty || entry.members.nonEmpty)

    if entry.views.nonEmpty then
      emitField("views", stringArray(entry.views), out, next, comma = entry.members.nonEmpty)

    if entry.members.nonEmpty then
      out.println(next + JsonUtil.string("members") + ": [")
      emitEntryList(entry.members, out, next + "  ")
      out.println()
      out.println(next + "]")

    out.print(indent + "}")

  private def emitField(name: String, value: String, out: PrintWriter, indent: String, comma: Boolean = true): Unit =
    out.print(indent)
    out.print(JsonUtil.string(name))
    out.print(": ")
    out.print(value)
    if comma then out.print(",")
    out.println()

  private def sourceJson(source: Option[SourceLoc]): String =
    source match
      case Some(SourceLoc(file, line)) =>
        s"""{ "file": ${JsonUtil.string(file)}, "line": $line }"""
      case None =>
        "null"

  private def stringArray(values: List[String]): String =
    values.map(JsonUtil.string).mkString("[", ", ", "]")

  private def annotationsJson(annotations: List[DocAnnotation]): String =
    annotations.map { annot =>
      s"""{ "name": ${JsonUtil.string(annot.name)}, "args": ${valuesJson(annot.args)} }"""
    }.mkString("[", ", ", "]")

  private def valuesJson(values: List[Constant]): String =
    values.map(constantJson).mkString("[", ", ", "]")

  private def constantJson(value: Constant): String =
    value match
      case Constant.String(v) => JsonUtil.string(v)
      case Constant.Int(v)    => v.toString
      case Constant.Float(v)  => v.toString
      case Constant.Bool(v)   => v.toString

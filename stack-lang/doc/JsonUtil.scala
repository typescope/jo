package doc

import java.io.PrintWriter

object JsonUtil:
  def string(s: String): String =
    val sb = new StringBuilder("\"")
    for c <- s do
      c match
        case '"'  => sb.append("\\\"")
        case '\\' => sb.append("\\\\")
        case '\b' => sb.append("\\b")
        case '\f' => sb.append("\\f")
        case '\n' => sb.append("\\n")
        case '\r' => sb.append("\\r")
        case '\t' => sb.append("\\t")
        case c if c < 32 => sb.append("\\u%04x".format(c.toInt))
        case c => sb.append(c)
    sb.append("\"")
    sb.toString

  def emitString(s: String, out: PrintWriter): Unit =
    out.print(string(s))

package common

import java.io.{ BufferedReader, PrintWriter }

object Debug:
  private var indent: Int = 0
  private def indentPrefix: String = " " * indent

  inline def trace[T](inline msg: => String, show: T => String, inline enable: Boolean)(inline op: T): T =
    inline if enable then {
      val m = msg
      println(indentPrefix + "==> " + m )
      indent = indent + 1
      val res = op
      indent = indent - 1
      println(indentPrefix + "<== " + m + " = " + show(res))
      res
    }
    else op

  inline def trace[T](inline msg: => String, inline enable: Boolean)(inline op: T): T =
    Debug.trace[T](msg, t => t.toString, enable)(op)

  inline def trace(inline msg: => String, inline enable: Boolean): Unit =
     inline if enable then println(indentPrefix +  msg)

  def displayPrompt(reader: BufferedReader = Console.in, writer: PrintWriter = PrintWriter(Console.err, true)): Unit =
    writer.println()
    writer.print("a)bort, s)tack, r)esume: ")
    writer.flush()

    def loop(): Unit = reader.read match
      case 'a' | 'A' =>
        new Throwable().printStackTrace(writer)
        System.exit(1)
      case 's' | 'S' =>
        new Throwable().printStackTrace(writer)
        writer.println()
        writer.flush()
      case 'r' | 'R' =>
        ()
      case _ =>
        loop()
    end loop

    if reader != null then loop()

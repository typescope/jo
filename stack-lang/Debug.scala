import java.io.{ BufferedReader, PrintWriter }

object Debug:
  private var indent: Int = 0
  private def indentPrefix: String = " " * indent

  inline def trace[T](msg: => String, show: T => String, inline enable: Boolean)(inline op: T): T =
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

  inline def trace[T](msg: => String, inline enable: Boolean)(inline op: T): T =
    Debug.trace[T](msg, t => t.toString, enable)(op)

  inline def trace(msg: => String, inline enable: Boolean): Unit =
     inline if enable then println(indentPrefix +  msg)

  inline def measure[T](msg: => String, inline enable: Boolean)(inline op: T): T =
    inline if enable then {
      val m = msg
      indent = indent + 1
      val before = System.nanoTime()
      val res = op
      val after = System.nanoTime()
      indent = indent - 1

      println(indentPrefix + m + ": " + (after - before) / 1000000 + " ms")
      res
    }
    else op

  extension [T](inline v: T)
    inline def <|(msg: => String, inline enable: Boolean): T = measure(msg, enable)(v)

  def peek(enable: Boolean): Sast.Prog => Sast.Prog = prog =>
    if enable then println(Printing.show(prog))
    prog

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

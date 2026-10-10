import java.io.{PrintWriter, StringWriter}
import java.nio.file.Files
import scala.sys.process.Process
import ruby.Trees.*

object RubyArithmeticPrinterCheck:
  private def render(expr: Tree): String =
    val buffer = new StringWriter
    val writer = new PrintWriter(buffer)
    ruby.Printer.emitTree(expr)(using ruby.Printer.Context(0, writer))
    writer.flush()
    buffer.toString

  def main(args: Array[String]): Unit =
    val a = IntLit(3)
    val b = IntLit(4)
    val c = IntLit(5)
    val cases = List(
      (BinOp(a, "+", b), "3 + 4", 7),
      (BinOp(BinOp(a, "*", b), "+", c), "(3 * 4) + 5", 17),
      (BinOp(a, "+", BinOp(b, "*", c)), "3 + (4 * 5)", 23),
      (BinOp(BinOp(a, "+", b), "*", c), "(3 + 4) * 5", 35),
      (BinOp(a, "*", BinOp(b, "+", c)), "3 * (4 + 5)", 27),
      (BinOp(BinOp(a, "-", b), "-", c), "(3 - 4) - 5", -6),
      (BinOp(a, "-", BinOp(b, "-", c)), "3 - (4 - 5)", 4),
      (BinOp(BinOp(a, "*", b), "+", BinOp(b, "*", c)), "(3 * 4) + (4 * 5)", 32),
      (BinOp(BinOp(BinOp(a, "+", b), "*", c), "-", a), "((3 + 4) * 5) - 3", 32),
      (BinOp(BinOp(a, "+", b), "<<", IntLit(1)), "(3 + 4) << 1", 14),
      (BinOp(BinOp(a, "&", b), "|", c), "(3 & 4) | 5", 5),
      (BinOp(a, "%", BinOp(b, "+", c)), "3 % (4 + 5)", 3),
      (UnaryOp("-", UnaryOp("~", a)), "-(~3)", 4),
      (BinOp(UnaryOp("-", a), "*", b), "(-3) * 4", -12),
      (Call(Some(BinOp(a, "-", b)), "abs", List.empty), "(3 - 4).abs", 1),
      (Call(Some(UnaryOp("-", a)), "abs", List.empty), "(-3).abs", 3)
    )
    val checks = cases.map: (expr, expected, value) =>
      val code = render(expr)
      assert(code == expected, s"Expected '$expected', got '$code'")
      s"raise 'Wrong result: $code' unless ($code) == $value"

    assert(render(BinOp(Ident("x"), "+", Call(None, "f", List(a)))) == "x + f(3)")
    assert(render(Call(None, "f", List(BinOp(a, "+", b)))) == "f(3 + 4)")
    assert(render(UnaryOp("-", BinOp(a, "+", b))) == "-(3 + 4)")

    val file = Files.createTempFile("jo-ruby-arithmetic-printer-", ".rb")

    try
      Files.writeString(file, checks.mkString("\n"))
      assert(Process(Seq("ruby", file.toString)).! == 0)
    finally
      Files.deleteIfExists(file)

    println(s"PASS: ${cases.size} Ruby arithmetic formatting and execution checks")

end RubyArithmeticPrinterCheck

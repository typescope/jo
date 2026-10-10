import java.io.{PrintWriter, StringWriter}
import java.nio.file.Files
import scala.sys.process.Process
import python.Trees.*

object PythonArithmeticPrinterCheck:
  private def render(expr: Expr): String =
    val buffer = new StringWriter
    val writer = new PrintWriter(buffer)
    python.Printer.emitExpr(expr)(using python.Printer.Context(0, writer))
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
      (BinOp(BinOp(IntLit(2), "**", a), "**", IntLit(2)), "(2 ** 3) ** 2", 64),
      (BinOp(IntLit(2), "**", BinOp(a, "**", IntLit(2))), "2 ** (3 ** 2)", 512)
    )
    val checks = cases.map: (expr, expected, value) =>
      val code = render(expr)
      assert(code == expected, s"Expected '$expected', got '$code'")
      s"assert ($code) == $value"

    assert(render(BinOp(Ident("x"), "+", Call(None, "f", List(a)))) == "x + f(3)")
    assert(render(Call(None, "f", List(BinOp(a, "+", b)))) == "f(3 + 4)")
    assert(render(UnaryOp("-", BinOp(a, "+", b))) == "- (3 + 4)")

    val file = Files.createTempFile("jo-python-arithmetic-printer-", ".py")

    try
      Files.writeString(file, checks.mkString("\n"))
      assert(Process(Seq("python3", file.toString)).! == 0)
    finally
      Files.deleteIfExists(file)

    println(s"PASS: ${cases.size} Python arithmetic formatting and execution checks")

end PythonArithmeticPrinterCheck

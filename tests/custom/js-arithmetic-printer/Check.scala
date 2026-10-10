import java.io.{PrintWriter, StringWriter}
import java.nio.file.Files
import scala.sys.process.Process
import js.Trees.*

object JSArithmeticPrinterCheck:
  private def render(expr: Expr): String =
    val buffer = new StringWriter
    val writer = new PrintWriter(buffer)
    js.Printer.emitExpr(expr)(using js.Printer.Context(0, writer))
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
      (BinOp(IntLit(2), "**", BinOp(a, "**", IntLit(2))), "2 ** (3 ** 2)", 512),
      (BinOp(UnaryOp("-", a), "**", IntLit(2)), "(-3) ** 2", 9),
      (Call(Some(UnaryOp("-", a)), "valueOf", Nil), "(-3).valueOf()", -3),
      (Call(Some(a), "valueOf", Nil), "(3).valueOf()", 3),
      (Call(Some(FloatLit(-3.5)), "valueOf", Nil), "(-3.5).valueOf()", -3.5),
      (BinOp(Conditional(BoolLit(true), a, b), "*", c), "(true ? 3 : 4) * 5", 15),
      (Call(Some(Arrow(List("x"), BinOp(Ident("x"), "+", IntLit(1)))), "", List(a)),
        "(x => x + 1)(3)", 4)
    )
    val checks = cases.map: (expr, expected, value) =>
      val code = render(expr)
      assert(code == expected, s"Expected '$expected', got '$code'")
      s"if (($code) !== $value) throw new Error('Wrong result: $code');"

    assert(render(BinOp(Ident("x"), "+", Call(None, "f", List(a)))) == "x + f(3)")
    assert(render(Call(None, "f", List(BinOp(a, "+", b)))) == "f(3 + 4)")
    assert(render(UnaryOp("-", BinOp(a, "+", b))) == "-(3 + 4)")

    assert(render(Call(Some(BigIntLit(3)), "toString", Nil)) == "(3n).toString()")
    assert(render(Call(Some(BigIntLit(-3)), "toString", Nil)) == "(-3n).toString()")
    assert(render(BinOp(UnaryOp("typeof", a), "===", StringLit("number"))) ==
      "(typeof 3) === \"number\"")

    val file = Files.createTempFile("jo-js-arithmetic-printer-", ".js")

    try
      Files.writeString(file, checks.mkString("\n"))
      assert(Process(Seq("node", file.toString)).! == 0)
    finally
      Files.deleteIfExists(file)

    println(s"PASS: ${cases.size} JS arithmetic formatting and execution checks")

end JSArithmeticPrinterCheck

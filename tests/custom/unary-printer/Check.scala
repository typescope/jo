import java.io.{PrintWriter, StringWriter}
import java.nio.file.Files
import scala.sys.process.Process

object UnaryPrinterCheck:
  enum Expr:
    case Num(value: Long)
    case Unary(op: String, operand: Expr)
    case Binary(left: Expr, op: String, right: Expr)

  import Expr.*

  private def evaluate(expr: Expr): Long = expr match
    case Num(value) => value
    case Unary("+", operand) => evaluate(operand)
    case Unary("-", operand) => -evaluate(operand)
    case Unary("~", operand) => ~evaluate(operand)
    case Binary(left, "+", right) => evaluate(left) + evaluate(right)
    case Binary(left, "-", right) => evaluate(left) - evaluate(right)
    case Binary(left, "*", right) => evaluate(left) * evaluate(right)
    case _ => throw new IllegalArgumentException(s"Unsupported expression: $expr")

  private def pythonTree(expr: Expr): python.Trees.Expr = expr match
    case Num(value) => python.Trees.IntLit(value)
    case Unary(op, operand) => python.Trees.UnaryOp(op, pythonTree(operand))
    case Binary(left, op, right) => python.Trees.BinOp(pythonTree(left), op, pythonTree(right))

  private def jsTree(expr: Expr): js.Trees.Expr = expr match
    case Num(value) => js.Trees.IntLit(value)
    case Unary(op, operand) => js.Trees.UnaryOp(op, jsTree(operand))
    case Binary(left, op, right) => js.Trees.BinOp(jsTree(left), op, jsTree(right))

  private def printPython(expr: Expr): String =
    val buffer = new StringWriter
    val writer = new PrintWriter(buffer)
    python.Printer.emitExpr(pythonTree(expr))(using python.Printer.Context(0, writer))
    writer.flush()
    buffer.toString

  private def printJS(expr: Expr): String =
    val buffer = new StringWriter
    val writer = new PrintWriter(buffer)
    js.Printer.emitExpr(jsTree(expr))(using js.Printer.Context(0, writer))
    writer.flush()
    buffer.toString

  private def runChecks(command: String, suffix: String, code: String): Unit =
    val file = Files.createTempFile("jo-unary-printer-", suffix)

    try
      Files.writeString(file, code)
      assert(Process(Seq(command, file.toString)).! == 0, s"$command unary checks failed")
    finally
      Files.deleteIfExists(file)

  def main(args: Array[String]): Unit =
    // Unary plus has no numeric intrinsic in Jo. Construct printer trees
    // directly to test every ordering of +, -, and ~ through four levels.
    val bases = List(
      Num(-3),
      Binary(Num(2), "+", Num(4)),
      Binary(Num(2), "-", Num(4)),
      Binary(Num(3), "*", Binary(Num(2), "+", Num(4)))
    )
    val levels = (1 to 4).scanLeft(bases): (operands, _) =>
      for
        op <- List("+", "-", "~")
        operand <- operands
      yield Unary(op, operand)

    val chains = levels.flatten.toList
    // Also check unary chains as the left and right operands of a product.
    val expressions = chains.flatMap: expr =>
      List(expr, Binary(expr, "*", Num(2)), Binary(Num(2), "*", expr))

    val pythonChecks = expressions.zipWithIndex.map: (expr, index) =>
      val code = printPython(expr)
      s"assert ($code) == ${evaluate(expr)}, 'Expression $index: $code'"

    val jsChecks = expressions.zipWithIndex.map: (expr, index) =>
      val code = printJS(expr)
      s"if (($code) !== ${evaluate(expr)}) throw new Error('Expression $index: $code');"

    runChecks("python3", ".py", pythonChecks.mkString("\n"))
    runChecks("node", ".js", jsChecks.mkString("\n"))
    println(s"PASS: ${expressions.size} mixed unary expressions in Python and JavaScript")

end UnaryPrinterCheck

package python

import python.Trees.*

/** Pretty printer for Python AST with precedence-aware parenthesization
  *
  * Generates clean, idiomatic Python code from the AST.
  *
  * - Precedence-aware: Only adds parentheses when needed
  * - Proper indentation: Uses 4-space indentation (PEP 8 standard)
  *
  * Invariants:
  *
  * - printing of indentation is always preceded by newline
  * - a construct never add ending newline --- that comes from context
  */
object Printer:
  private val INDENT = "    "  // 4 spaces (PEP 8)

  case class Context(indent: Int, pw: java.io.PrintWriter):
    def indented: Context = Context(this.indent + 1, this.pw)

  def emit(args: String*)(using ctx: Context): Unit =
    for arg <- args do ctx.pw.write(arg)

  /** Invariant: indent is always preceded with newline */
  def emitIndented(args: String*)(using ctx: Context): Unit =
    emitNewline()
    ctx.pw.write(INDENT * ctx.indent)
    for arg <- args do ctx.pw.write(arg)

  def emitLine(args: String*)(using ctx: Context): Unit =
    emitNewline()
    ctx.pw.write(INDENT * ctx.indent)
    for arg <- args do ctx.pw.write(arg)

  def emitInline(args: String*)(using ctx: Context): Unit =
    for arg <- args do ctx.pw.write(arg)

  def emitNewline()(using ctx: Context): Unit = ctx.pw.write("\n")

  def emitBlankLine()(using Context): Unit =
    emitNewline()
    emitNewline()

  def indented(work: Context ?=> Unit)(using ctx: Context): Unit =
    val ctx2 = Context(ctx.indent + 1, ctx.pw)
    work(using ctx2)

  /** Operator precedence levels (higher = tighter binding)
    *
    * Python precedence (from lowest to highest):
    *   lambda
    *   if-else (ternary)
    *   or
    *   and
    *   not
    *   in, not in, is, is not, <, <=, >, >=, !=, ==
    *   |
    *   ^
    *   &
    *   <<, >>
    *   +, -
    *   *, /, //, %
    *   -(unary), +(unary), ~
    *   **
    *   x[index], x.attr, x(args)
    *
    * https://docs.python.org/3/reference/expressions.html#operator-precedence
    */
  private def precedence(op: String): Int = op match
    case "or" => 1
    case "and" => 2
    case "not" => 3
    case "=="|"!="|"<"|">"|"<="|">="|"is"|"in" => 4
    case "|" => 5
    case "^" => 6
    case "&" => 7
    case ">>"|"<<" => 8
    case "+"|"-" => 9
    case "*"|"/"|"//"|"%" => 10
    case "**" => 11
    case _ => 100  // Atomic expressions (no parens needed)

  /** Print a complete Python program */
  def print(program: Program, pw: java.io.PrintWriter): Unit =
    given ctx: Context = Context(0, pw)

    // Comment header
    emit("# Generated Python code")
    emitBlankLine()

    // Import sys for command-line arguments
    emit("import sys")
    emitBlankLine()

    // Global initialization
    if program.globalInit.nonEmpty then
      program.globalInit.foreach: stat =>
        emitStat(stat)
      emitBlankLine()

    // Definitions
    program.defs.foreach: defn =>
      emitDef(defn)
      emitBlankLine()

    // Entry point comment
    emitLine("# Entry point")
    emitStat(program.mainCall)
    emitNewline()

  /** Emit a top-level definition */
  private def emitDef(defn: Def)(using ctx: Context): Unit = defn match
    case FunDef(name, params, body) =>
      emitLine("def ", name, "(", params.mkString(", "), "):")
      indented:
        emitBlock(body)

    case ClassDef(name, fields, methods) =>
      emitLine("class ", name, ":")
      indented:
        // Methods (including __init__ if present)
        if methods.nonEmpty then
          methods.foreach: method =>
            emitDef(method)
            if method != methods.last then emitNewline()
        else
          // If class has no methods, add pass
          emitLine("pass")

  /** Emit a statement */
  private def emitStat(stat: Stat)(using ctx: Context): Unit = stat match
    case Assign(name, rhs) =>
      emitLine(name, " = ")
      emitExpr(rhs, 0)

    case AttrAssign(receiver, attr, rhs) =>
      emitNewline()
      emit(INDENT * ctx.indent)
      emitExpr(receiver, 100)
      emitInline(".", attr, " = ")
      emitExpr(rhs, 0)

    case IndexAssign(receiver, index, rhs) =>
      emitNewline()
      emit(INDENT * ctx.indent)
      emitExpr(receiver, 100)
      emitInline("[")
      emitExpr(index, 0)
      emitInline("] = ")
      emitExpr(rhs, 0)

    case IfStat(cond, thenBranch, elseBranch) =>
      emitLine("if ")
      emitExpr(cond, 0)
      emitInline(":")
      indented:
        emitStat(thenBranch)
      emitLine("else:")
      indented:
        emitStat(elseBranch)

    case While(cond, body) =>
      emitLine("while ")
      emitExpr(cond, 0)
      emitInline(":")
      indented:
        emitStat(body)

    case Return(value) =>
      emitLine("return ")
      emitExpr(value, 0)

    case ExprStat(expr) =>
      emitNewline()
      emit(INDENT * ctx.indent)
      emitExpr(expr, 0)

    case Block(statements) =>
      if statements.isEmpty then
        emitLine("pass")
      else
        statements.foreach(emitStat)

  /** Emit a block (list of statements) */
  private def emitBlock(block: Block)(using ctx: Context): Unit =
    if block.statements.isEmpty then
      emitLine("pass")
    else
      block.statements.foreach(emitStat)

  /** Emit an expression with precedence context */
  def emitExpr(expr: Expr, parentPrec: Int = 0)(using ctx: Context): Unit =
    def withParenthesisOpt(op: String)(work: Int => Unit): Unit =
      val myPrec = precedence(op)
      val needsParens = myPrec < parentPrec
      if needsParens then
        emitInline("(")
        work(myPrec)
        emitInline(")")
      else
        work(myPrec)

    expr match
      case IntLit(n) => emitInline(n.toString)
      case FloatLit(d) => emitInline(d.toString)
      case StringLit(s) => emitInline("\"" + escape(s) + "\"")
      case BoolLit(b) => emitInline(if b then "True" else "False")
      case NoneLit => emitInline("None")
      case Ident(name) => emitInline(name)

      case BinOp(left, op, right) =>
        withParenthesisOpt(op): myPrec =>
          emitExpr(left, myPrec)
          emitInline(" ", op, " ")
          // Right-associative operators need special handling
          val rightPrec = if op == "**" then myPrec - 1 else myPrec
          emitExpr(right, rightPrec)

      case UnaryOp(op, operand) =>
        withParenthesisOpt(op): myPrec =>
          emitInline(op, " ")
          emitExpr(operand, myPrec)

      case IfExpr(cond, thenBranch, elseBranch) =>
        // Python ternary: thenBranch if cond else elseBranch
        // Wrap in parens for complex cases
        val needsParens = parentPrec > 0
        if needsParens then emitInline("(")
        emitExpr(thenBranch, 1)
        emitInline(" if ")
        emitExpr(cond, 1)
        emitInline(" else ")
        emitExpr(elseBranch, 1)
        if needsParens then emitInline(")")

      case Call(receiver, method, args) =>
        receiver match
          case Some(recv) =>
            emitExpr(recv, 100)
            emitInline(".", method)

          case None =>
            emitInline(method)

        emitInline("(")
        args.zipWithIndex.foreach: (arg, i) =>
          if i > 0 then emitInline(", ")
          emitExpr(arg, 0)
        emitInline(")")

      case LambdaCall(fun, args) =>
        emitExpr(fun, 100)
        emitInline("(")
        args.zipWithIndex.foreach: (arg, i) =>
          if i > 0 then emitInline(", ")
          emitExpr(arg, 0)
        emitInline(")")

      case Lambda(params, body) =>
        emitInline("lambda ", params.mkString(", "), ": ")
        emitExpr(body, 0)

      case New(className, args) =>
        emitInline(className, "(")
        args.zipWithIndex.foreach: (arg, i) =>
          if i > 0 then emitInline(", ")
          emitExpr(arg, 0)
        emitInline(")")

      case Select(receiver, member) =>
        emitExpr(receiver, 100)
        emitInline(".", member)

      case Index(receiver, index) =>
        emitExpr(receiver, 100)
        emitInline("[")
        index match
          case Slice(start, end) =>
            // Python slice: receiver[start:end]
            emitExpr(start, 0)
            emitInline(":")
            emitExpr(end, 0)
          case _ =>
            emitExpr(index, 0)
        emitInline("]")

      case Slice(start, end) =>
        // Slice should only appear inside Index
        throw new Exception("Slice should only appear as index argument")

      case InstanceOf(value, className) =>
        emitInline("isinstance(")
        emitExpr(value, 0)
        emitInline(", ", className, ")")

      case RawCode(code) =>
        // Emit raw Python code directly without modification
        emitInline(code)

  /** Escape special characters in strings */
  private def escape(s: String): String =
    val sb = new StringBuilder
    s.codePoints().forEach: cp =>
      cp match
        case '\n' => sb ++= "\\n"
        case '\r' => sb ++= "\\r"
        case '\t' => sb ++= "\\t"
        case '"'  => sb ++= "\\\""
        case '\\' => sb ++= "\\\\"
        case _ if cp < 32 || cp > 126 => sb ++= f"\\u{${cp}%04x}"
        case _ => sb += cp.toChar

    sb.toString

end Printer

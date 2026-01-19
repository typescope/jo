package ruby

import ruby.Trees.*

/** Pretty printer for Ruby AST with precedence-aware parenthesization
  *
  * Generates clean, idiomatic Ruby code from the AST.
  * Key features:
  * - Precedence-aware: Only adds parentheses when needed
  * - Proper indentation: Uses 2-space indentation (Ruby standard)
  * - Expression-oriented: Leverages Ruby's implicit returns
  */
object Printer:
  private val INDENT = "  "

  case class Context(indent: Int, pw: java.io.PrintWriter):
    def emit(args: String*): Unit =
      for arg <- args do pw.write(arg)

    def emitLine(args: String*): Unit =
      pw.write(INDENT * indent)
      for arg <- args do pw.write(arg)
      emitNewline()

    def emitInline(args: String*): Unit =
      for arg <- args do pw.write(arg)

    def emitNewline(): Unit = pw.write("\n")

    def emitBlankLine(): Unit =
      emitNewline()
      emitNewline()

  def indented(work: Context ?=> Unit)(using ctx: Context): Unit =
    val ctx2 = Context(ctx.indent + 1, ctx.pw)
    work(using ctx2)

  def emitIndentedExpr(expr: Expr)(using ctx: Context): Unit =
    ctx.emit(INDENT * ctx.indent)
    emitExpr(expr)
    ctx.emitNewline()

  /** Operator precedence levels (higher = tighter binding)
    * Based on Ruby operator precedence table
    */
  private def precedence(expr: Expr): Int = expr match
    case BinOp("||", _, _) => 1
    case BinOp("&&", _, _) => 2
    case BinOp("=="|"!=", _, _) => 4
    case BinOp("<"|">"|"<="|">=", _, _) => 5
    case BinOp("+"|"-", _, _) => 6
    case BinOp("*"|"/"|"%", _, _) => 7
    case UnaryOp("!"|"-", _) => 8
    case _ => 100  // Atomic expressions (no parens needed)

  /** Print a complete Ruby program */
  def print(program: Program): String =
    val sw = new java.io.StringWriter()
    val pw = new java.io.PrintWriter(sw)
    given ctx: Context = Context(0, pw)

    // Comment header
    ctx.emit("# Generated Ruby code")
    ctx.emitBlankLine()

    // Global initialization
    if program.globalInit.nonEmpty then
      program.globalInit.foreach: stat =>
        emitStat(stat)
      ctx.emitNewline()

    // Definitions
    program.defs.foreach: defn =>
      emitDef(defn)
      ctx.emitBlankLine()

    // Main call
    emitIndentedExpr(program.mainCall)

    pw.flush()
    sw.toString

  /** Emit a top-level definition */
  private def emitDef(defn: Def)(using ctx: Context): Unit = defn match
    case FunDef(name, params, body) =>
      ctx.emitLine("def ", name, "(", params.mkString(", "), ")")
      indented:
        emitIndentedExpr(body)
      ctx.emitLine("end")

    case ClassDef(name, fields, methods, isObject) =>
      ctx.emitLine("class ", name)
      indented:
        // attr_accessor for fields
        if fields.nonEmpty then
          ctx.emitLine("attr_accessor ", fields.sorted.map(":" + _).mkString(", "))
          if methods.nonEmpty then ctx.emitNewline()

        // Methods
        methods.foreach: method =>
          emitDef(method)
          if method != methods.last then ctx.emitNewline()

        // Special handling for singleton objects
        if isObject then
          if methods.nonEmpty then ctx.emitNewline()
          ctx.emitLine("@instance = ", name, ".new")
          ctx.emitNewline()
          ctx.emitLine("def self.instance")
          indented:
            ctx.emitLine("@instance")
          ctx.emitLine("end")

      ctx.emitLine("end")

  /** Emit an expression with precedence context */
  def emitExpr(expr: Expr, parentPrec: Int = 0)(using ctx: Context): Unit =
    val myPrec = precedence(expr)
    val needsParens = myPrec < parentPrec

    if needsParens then ctx.emitInline("(")

    expr match
      case IntLit(n) => ctx.emitInline(n.toString)
      case FloatLit(d) => ctx.emitInline(d.toString)
      case StringLit(s) => ctx.emitInline("\"" + escape(s) + "\"")
      case BoolLit(b) => ctx.emitInline(b.toString)
      case Nil => ctx.emitInline("nil")
      case Ident(name) => ctx.emitInline(name)

      case BinOp(op, left, right) =>
        emitExpr(left, myPrec)
        ctx.emitInline(" ", op, " ")
        emitExpr(right, myPrec)

      case UnaryOp(op, operand) =>
        val operandNeedsParens = precedence(operand) < myPrec
        ctx.emitInline(op)
        if operandNeedsParens then ctx.emitInline("(")
        emitExpr(operand, myPrec)
        if operandNeedsParens then ctx.emitInline(")")

      case If(cond, thenBranch, elseBranch) =>
        ctx.emit(INDENT * ctx.indent, "if ")
        emitExpr(cond, 0)
        ctx.emitNewline()
        indented:
          emitIndentedExpr(thenBranch)
        ctx.emitLine("else")
        indented:
          emitIndentedExpr(elseBranch)
        ctx.emit(INDENT * ctx.indent, "end")


      case Call(receiver, method, args, isLambdaCall) =>
        receiver match
          case Some(recv) =>
            val recvNeedsParens = precedence(recv) < 100
            if recvNeedsParens then ctx.emitInline("(")
            emitExpr(recv, 0)
            if recvNeedsParens then ctx.emitInline(")")
            if isLambdaCall then
              ctx.emitInline(".call")
            else
              ctx.emitInline(".", method)
          case None =>
            ctx.emitInline(method)

        ctx.emitInline("(")
        args.zipWithIndex.foreach: (arg, i) =>
          if i > 0 then ctx.emitInline(", ")
          emitExpr(arg, 0)
        ctx.emitInline(")")

      case Lambda(params, body) =>
        ctx.emitInline("lambda { |", params.mkString(", "), "| ")
        emitExpr(body, 0)
        ctx.emitInline(" }")

      case New(className, args) =>
        ctx.emitInline(className, ".new(")
        args.zipWithIndex.foreach: (arg, i) =>
          if i > 0 then ctx.emitInline(", ")
          emitExpr(arg, 0)
        ctx.emitInline(")")

      case Select(receiver, member) =>
        val recvNeedsParens = precedence(receiver) < 100
        if recvNeedsParens then ctx.emitInline("(")
        emitExpr(receiver, 0)
        if recvNeedsParens then ctx.emitInline(")")
        ctx.emitInline(".", member)

      case Block(statements, result) =>
        statements.foreach: stat =>
          emitStat(stat)
          ctx.emitNewline()
        ctx.emit(INDENT * ctx.indent)
        emitExpr(result)

      case InstanceOf(value, className) =>
        emitExpr(value, 0)
        ctx.emitInline(".is_a?(", className, ")")

      case RawCode(code) =>
        // Emit raw Ruby code directly without modification
        ctx.emitInline(code)

    if needsParens then ctx.emitInline(")")

  /** Emit a statement */
  private def emitStat(stat: Stat)(using ctx: Context): Unit =
    ctx.emit(INDENT * ctx.indent)

    stat match
      case Assign(name, rhs) =>
        ctx.emitInline(name, " = ")
        emitExpr(rhs, 0)

      case FieldAssign(receiver, field, rhs) =>
        receiver match
          case Some(recv) =>
            emitExpr(recv, 0)
            ctx.emitInline(".", field)
          case None =>
            ctx.emitInline("@", field)
        ctx.emitInline(" = ")
        emitExpr(rhs, 0)

      case While(cond, body) =>
        ctx.emitInline("while true")
        ctx.emitNewline()
        indented:
          ctx.emitLine("break unless ")
          emitExpr(cond, 0)
          ctx.emitNewline()
          body.foreach: stat =>
            emitStat(stat)
            ctx.emitNewline()
        ctx.emit(INDENT * ctx.indent, "end")

      case ExprStat(expr) =>
        emitExpr(expr, 0)

  /** Escape special characters in strings */
  private def escape(s: String): String =
    s.flatMap:
      case '\n' => "\\n"
      case '\r' => "\\r"
      case '\t' => "\\t"
      case '"' => "\\\""
      case '\\' => "\\\\"
      case c if c < 32 || c > 126 => f"\\u${c.toInt}%04x"
      case c => c.toString
    .mkString

end Printer

package js

import js.Trees.*

/** Pretty printer for JavaScript AST with explicit expression grouping
  *
  * Generates clean, idiomatic JavaScript code from the AST.
  *
  * - Parenthesizes nested compound expressions
  * - Proper indentation: Uses 2-space indentation (JavaScript standard)
  *
  * Invariants:
  *
  * - printing of indentation is always preceded by newline
  * - a construct never adds ending newline --- that comes from context
  */
object Printer:
  private val INDENT = "  "  // 2 spaces (JavaScript standard)

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

  /** A `+`/`-` operator, which can merge with a following sign into `++`/`--`. */
  private def isSign(op: String): Boolean = op == "-" || op == "+"

  /** Whether an expression is emitted starting with a `-` sign (a negative
    * Int/Float literal); such operands need separating from a preceding sign.
    * BigInt literals parenthesize their own negatives, so they don't count. */
  private def startsWithSign(expr: Expr): Boolean = expr match
    case IntLit(n)   => n < 0
    case FloatLit(d) => d < 0
    case _           => false

  /** Print a complete JavaScript program */
  def print(program: Program, pw: java.io.PrintWriter): Unit =
    given ctx: Context = Context(0, pw)

    // Comment header
    emit("// Generated JavaScript code")
    emitBlankLine()

    // Definitions
    program.defs.foreach: defn =>
      emitDef(defn)
      emitNewline()

    // Entry point (includes initialization + start call)
    emitLine("// Entry point")
    emitStat(program.mainCall)
    emitNewline()

  /** Emit a top-level definition */
  private def emitDef(defn: Def)(using ctx: Context): Unit = defn match
    case FunDef(name, params, body) =>
      emitLine("function ", name, "(", params.mkString(", "), ") {")
      indented:
        emitBlock(body)
      emitLine("}")

    case ClassDef(name, fields, methods, staticFields) =>
      emitLine("class ", name, " {")
      indented:
        // Static field initializations
        if staticFields.nonEmpty then
          staticFields.foreach: field =>
            val Assign(fieldName, value) = field
            emitLine("static ", fieldName, " = ")
            emitExpr(value)
            emitInline(";")

          if methods.nonEmpty then emitNewline()

        // Methods (including constructor if present)
        if methods.nonEmpty then
          methods.foreach: method =>
            // Emit method without "function" keyword
            emitLine(method.name, "(", method.params.mkString(", "), ") {")
            indented:
              emitBlock(method.body)
            emitLine("}")

            if method != methods.last then emitNewline()

        else if staticFields.isEmpty then
          // Empty class (shouldn't happen, but handle gracefully)
          emitLine("// Empty class")
      emitLine("}")

  /** Emit a statement */
  private def emitStat(stat: Stat)(using ctx: Context): Unit = stat match
    case VarDecl(keyword, name, init) =>
      emitLine(keyword, " ", name, " = ")
      emitExpr(init)
      emitInline(";")

    case Assign(name, value) =>
      emitIndented(name, " = ")
      emitExpr(value)
      emitInline(";")

    case FieldAssign(receiver, field, value) =>
      emitIndented("")
      emitReceiver(receiver)
      emitInline(".", field, " = ")
      emitExpr(value)
      emitInline(";")

    case IndexAssign(receiver, index, value) =>
      emitIndented("")
      emitReceiver(receiver)
      emitInline("[")
      emitExpr(index)
      emitInline("] = ")
      emitExpr(value)
      emitInline(";")

    case TryCatch(body, errName, handler) =>
      emitLine("try {")
      indented:
        emitStat(body)
      emitLine("} catch (", errName, ") {")
      indented:
        emitStat(handler)
      emitLine("}")

    case IfStat(cond, thenBranch, elseBranch) =>
      emitLine("if (")
      emitExpr(cond)
      emitInline(") {")
      indented:
        emitStat(thenBranch)
      emitLine("}")
      emitLine("else {")
      indented:
        emitStat(elseBranch)
      emitLine("}")

    case While(cond, body) =>
      emitLine("while (")
      emitExpr(cond)
      emitInline(") {")
      indented:
        emitStat(body)
      emitLine("}")

    case Break =>
      emitLine("break;")

    case Continue =>
      emitLine("continue;")

    case BreakTo(label) =>
      emitLine("break ", label, ";")

    case Labeled(label, body) =>
      emitLine(label, ": {")
      indented:
        emitStat(body)
      emitLine("}")

    case Return(value) =>
      emitLine("return ")
      emitExpr(value)
      emitInline(";")

    case Throw(exception) =>
      emitLine("throw ")
      emitExpr(exception)
      emitInline(";")

    case ExprStat(expr) =>
      emitIndentedExpr(expr)
      emitInline(";")

    case blk: Block =>
      emitBlock(blk)

  /** Emit a block (list of statements) */
  private def emitBlock(block: Block)(using ctx: Context): Unit =
    def newLineForControl(stat: Tree) =
      stat match
        case _: IfStat | _: While | _: Labeled =>
          emitNewline()
          true

        case _ =>
          false

    val statements = block.statements
    if statements.isEmpty then
      emitLine("// Empty block")
    else
      statements.zipWithIndex.foreach: (stat, i) =>
        if i > 0 then
          newLineForControl(stat) || newLineForControl(statements(i - 1))

        emitStat(stat)

  /** An indented expression */
  private def emitIndentedExpr(expr: Expr, nested: Boolean = false)(using ctx: Context): Unit =
    emitNewline()
    emit(INDENT * ctx.indent)
    emitExpr(expr, nested)

  /** Group numeric receivers so member access cannot merge with the literal. */
  private def emitReceiver(expr: Expr)(using Context): Unit =
    val needsParens = expr match
      case _: IntLit | _: FloatLit => true
      case BigIntLit(n) => n >= 0
      case _ => false

    if needsParens then emitInline("(")

    emitExpr(expr, nested = true)

    if needsParens then emitInline(")")

  /** Emit an expression, grouping compound expressions when nested. */
  def emitExpr(expr: Expr, nested: Boolean = false)(using ctx: Context): Unit =
    val needsParens = nested && (expr match
      case _: BinOp | _: UnaryOp | _: Conditional | _: Arrow | _: Function | _: ObjectLit | _: InstanceOf => true
      case _ => false
    )

    if needsParens then emitInline("(")

    expr match
      case IntLit(n) => emitInline(n.toString)

      case BigIntLit(n) =>
        if n < 0 then emitInline(s"(${n}n)")
        else emitInline(s"${n}n")

      case FloatLit(d) => emitInline(d.toString)

      case StringLit(s) => emitInline("\"" + escape(s) + "\"")

      case BoolLit(b) => emitInline(if b then "true" else "false")

      case NullLit => emitInline("null")

      case UndefinedLit => emitInline("undefined")

      case Ident(name) => emitInline(name)

      case BinOp(left, op, right) =>
        emitExpr(left, nested = true)
        emitInline(" ", op, " ")
        emitExpr(right, nested = true)

      case UnaryOp(op, operand) =>
        emitInline(op)
        // Keep word operators and adjacent signs separate from their operands.
        if op.head.isLetter || (isSign(op) && startsWithSign(operand)) then
          emitInline(" ")

        emitExpr(operand, nested = true)

      case Conditional(cond, thenBranch, elseBranch) =>
        // JavaScript ternary: cond ? thenBranch : elseBranch
        emitExpr(cond, nested = true)
        emitInline(" ? ")
        emitExpr(thenBranch, nested = true)
        emitInline(" : ")
        emitExpr(elseBranch, nested = true)

      case Call(receiver, method, args) =>
        receiver match
          case Some(recv) =>
            emitReceiver(recv)
            if method.nonEmpty then
              emitInline(".", method)

          case None =>
            emitInline(method)

        emitInline("(")
        args.zipWithIndex.foreach: (arg, i) =>
          if i > 0 then emitInline(", ")
          emitExpr(arg)
        emitInline(")")

      case Arrow(params, body) =>
        // Arrow function: (params) => body
        // Always use parentheses if there are 0 params, multiple params, or rest parameters
        if params.length == 1 && !params.head.startsWith("...") then
          emitInline(params.head)
        else
          emitInline("(", params.mkString(", "), ")")
        emitInline(" => ")
        emitExpr(body)

      case Function(params, body) =>
        // Function expression: function(params) { body }
        emitInline("function(", params.mkString(", "), ") {")
        indented:
          emitBlock(body)
        emitInline("}")

      case New(className, args) =>
        emitInline("new ", className, "(")
        args.zipWithIndex.foreach: (arg, i) =>
          if i > 0 then emitInline(", ")
          emitExpr(arg)
        emitInline(")")

      case Select(receiver, member) =>
        emitReceiver(receiver)
        emitInline(".", member)

      case Index(receiver, index) =>
        emitReceiver(receiver)
        emitInline("[")
        emitExpr(index)
        emitInline("]")

      case ArrayLit(elements) =>
        emitInline("[")
        elements.zipWithIndex.foreach: (elem, i) =>
          if i > 0 then emitInline(", ")
          emitExpr(elem)
        emitInline("]")

      case ObjectLit(fields) =>
        emitInline("{")
        fields.zipWithIndex.foreach: (field, i) =>
          val (key, value) = field
          if i > 0 then emitInline(", ")
          emitInline(key, ": ")
          emitExpr(value)
        emitInline("}")

      case InstanceOf(value, className) =>
        emitExpr(value, nested = true)
        emitInline(" instanceof ", className)

      case Spread(expr) =>
        emitInline("...")
        emitExpr(expr, nested = true)

      case RawCode(code) =>
        // Emit raw JavaScript code directly without modification
        emitInline(code)

    if needsParens then emitInline(")")

  /** Escape special characters in strings */
  private def escape(s: String): String =
    val sb = new StringBuilder
    s.codePoints().forEach: cp =>
      cp match
        case '\f' => sb ++= "\\f"
        case '\n' => sb ++= "\\n"
        case '\r' => sb ++= "\\r"
        case '\t' => sb ++= "\\t"
        case '"'  => sb ++= "\\\""
        case '\\' => sb ++= "\\\\"
        case _ if cp < 32 || cp > 126 => sb ++= f"\\u{${cp}%x}"
        case _ => sb += cp.toChar

    sb.toString

end Printer

package js

import js.Trees.*

/** Pretty printer for JavaScript AST with precedence-aware parenthesization
  *
  * Generates clean, idiomatic JavaScript code from the AST.
  *
  * - Precedence-aware: Only adds parentheses when needed
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

  /** Operator precedence levels (higher = tighter binding)
    *
    * JavaScript precedence (from lowest to highest):
    *   , (comma)
    *   = (assignment - not used in our AST, assignment is a statement)
    *   ?: (conditional)
    *   ||
    *   &&
    *   |
    *   ^
    *   &
    *   ==, !=, ===, !==
    *   <, <=, >, >=, instanceof, in
    *   <<, >>, >>>
    *   +, -
    *   *, /, %
    *   ** (exponentiation)
    *   unary: !, ~, +, -, typeof, void, delete
    *   new, function call, member access
    *
    * https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Operators/Operator_Precedence
    */
  private def precedence(op: String): Int = op match
    case "," => 0        // Comma (lowest in expressions)
    case "||" => 1       // Logical OR
    case "&&" => 2       // Logical AND
    case "|" => 3        // Bitwise OR
    case "^" => 4        // Bitwise XOR
    case "&" => 5        // Bitwise AND
    case "=="|"!="|"==="|"!==" => 6  // Equality
    case "<"|">"|"<="|">="|"instanceof"|"in" => 7  // Relational
    case "<<"|">>"|">>>" => 8  // Bitwise shift
    case "+"|"-" => 9     // Additive
    case "*"|"/"|"%" => 10  // Multiplicative
    case "**" => 11      // Exponentiation (right-associative)
    case _ => 100        // Atomic expressions (no parens needed)

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
            emitExpr(value, 0)
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
      emitExpr(init, 0)
      emitInline(";")

    case Assign(name, value) =>
      emitIndented(name, " = ")
      emitExpr(value, 0)
      emitInline(";")

    case FieldAssign(receiver, field, value) =>
      emitIndentedExpr(receiver, 0)
      emitInline(".", field, "= ")
      emitExpr(value, 0)
      emitInline(";")

    case IfStat(cond, thenBranch, elseBranch) =>
      emitLine("if (")
      emitExpr(cond, 0)
      emitInline(") {")
      indented:
        emitStat(thenBranch)
      emitLine("} else {")
      indented:
        emitStat(elseBranch)
      emitLine("}")

    case While(cond, body) =>
      emitLine("while (")
      emitExpr(cond, 0)
      emitInline(") {")
      indented:
        emitStat(body)
      emitLine("}")

    case Break =>
      emitLine("break;")

    case Return(value) =>
      emitLine("return ")
      emitExpr(value, 0)
      emitInline(";")

    case Throw(exception) =>
      emitLine("throw ")
      emitExpr(exception, 0)
      emitInline(";")

    case ExprStat(expr) =>
      emitIndentedExpr(expr, 0)
      emitInline(";")

    case blk: Block =>
      emitBlock(blk)

  /** Emit a block (list of statements) */
  private def emitBlock(block: Block)(using ctx: Context): Unit =
    def newLineForControl(stat: Tree) =
      stat match
        case _: IfStat | _: While =>
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
  private def emitIndentedExpr(expr: Expr, prec: Int)(using ctx: Context): Unit =
    emitNewline()
    emit(INDENT * ctx.indent)
    emitExpr(expr, prec)

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
      case BoolLit(b) => emitInline(if b then "true" else "false")
      case NullLit => emitInline("null")
      case UndefinedLit => emitInline("undefined")
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
          emitInline(op)
          // Add space if operator is a word (typeof, void, delete)
          if op.head.isLetter then emitInline(" ")
          emitExpr(operand, myPrec)

      case Conditional(cond, thenBranch, elseBranch) =>
        // JavaScript ternary: cond ? thenBranch : elseBranch
        // Wrap in parens for complex cases
        val needsParens = parentPrec > 0
        if needsParens then emitInline("(")
        emitExpr(cond, 1)
        emitInline(" ? ")
        emitExpr(thenBranch, 1)
        emitInline(" : ")
        emitExpr(elseBranch, 1)
        if needsParens then emitInline(")")

      case Call(receiver, method, args) =>
        receiver match
          case Some(recv) =>
            emitExpr(recv, 100)
            if method.nonEmpty then
              emitInline(".", method)

          case None =>
            emitInline(method)

        emitInline("(")
        args.zipWithIndex.foreach: (arg, i) =>
          if i > 0 then emitInline(", ")
          emitExpr(arg, 0)
        emitInline(")")

      case Arrow(params, body) =>
        // Arrow function: (params) => body
        val needsParens = parentPrec > 0
        if needsParens then emitInline("(")
        // Always use parentheses if there are 0 params, multiple params, or rest parameters
        if params.length == 1 && !params.head.startsWith("...") then
          emitInline(params.head)
        else
          emitInline("(", params.mkString(", "), ")")
        emitInline(" => ")
        emitExpr(body, 0)
        if needsParens then emitInline(")")

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
          emitExpr(arg, 0)
        emitInline(")")

      case Select(receiver, member) =>
        emitExpr(receiver, 100)
        emitInline(".", member)

      case Index(receiver, index) =>
        emitExpr(receiver, 100)
        emitInline("[")
        emitExpr(index, 0)
        emitInline("]")

      case ArrayLit(elements) =>
        emitInline("[")
        elements.zipWithIndex.foreach: (elem, i) =>
          if i > 0 then emitInline(", ")
          emitExpr(elem, 0)
        emitInline("]")

      case ObjectLit(fields) =>
        emitInline("{")
        fields.zipWithIndex.foreach: (field, i) =>
          val (key, value) = field
          if i > 0 then emitInline(", ")
          emitInline(key, ": ")
          emitExpr(value, 0)
        emitInline("}")

      case InstanceOf(value, className) =>
        emitExpr(value, 7)
        emitInline(" instanceof ", className)

      case RawCode(code) =>
        // Emit raw JavaScript code directly without modification
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
        case _ if cp < 32 || cp > 126 => sb ++= f"\\u${cp}%04x"
        case _ => sb += cp.toChar

    sb.toString

end Printer

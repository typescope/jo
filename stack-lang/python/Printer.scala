package python

import python.Trees.*

/** Pretty printer for Python AST with explicit expression grouping
  *
  * Generates clean, idiomatic Python code from the AST.
  *
  * - Parenthesizes nested compound expressions
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

  /** Print a complete Python program */
  def print(program: Program, pw: java.io.PrintWriter): Unit =
    given ctx: Context = Context(0, pw)

    // Comment header
    emit("# Generated Python code")
    emitBlankLine()

    // Hoisted module imports
    if program.imports.nonEmpty then
      program.imports.foreach: imp =>
        emitLine("import ", imp.module, " as ", imp.alias)

      emitNewline()

    // Definitions
    program.defs.foreach: defn =>
      emitDef(defn)
      emitNewline()

    // Entry point (includes initialization + start call)
    emitLine("# Entry point")
    emitStat(program.mainCall)
    emitNewline()

  /** Emit a top-level definition */
  private def emitDef(defn: Def)(using ctx: Context): Unit = defn match
    case FunDef(name, params, body) =>
      emitLine("def ", name, "(", params.mkString(", "), "):")
      indented:
        emitBlock(body)

    case ClassDef(name, fields, methods, base) =>
      val header = base match
        case Some(parent) => s"class $name($parent):"
        case None => s"class $name:"
      emitLine(header)
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
      emitExpr(rhs)

    case AttrAssign(receiver, attr, rhs) =>
      emitIndentedExpr(receiver, nested = true)
      emitInline(".", attr, " = ")
      emitExpr(rhs)

    case IndexAssign(receiver, index, rhs) =>
      emitIndentedExpr(receiver, nested = true)
      emitInline("[")
      emitExpr(index)
      emitInline("] = ")
      emitExpr(rhs)

    case IfStat(cond, thenBranch, elseBranch) =>
      emitLine("if ")
      emitExpr(cond)
      emitInline(":")
      indented:
        emitStat(thenBranch)
      emitLine("else:")
      indented:
        emitStat(elseBranch)

    case While(cond, body) =>
      emitLine("while ")
      emitExpr(cond)
      emitInline(":")
      indented:
        emitStat(body)

    case Break =>
      emitLine("break")

    case Continue =>
      emitLine("continue")

    case Return(value) =>
      emitLine("return ")
      emitExpr(value)

    case Raise(exception) =>
      emitLine("raise ")
      emitExpr(exception)

    case TryExcept(body, exceptionType, binder, handler) =>
      emitLine("try:")
      indented:
        emitStat(body)
      emitLine("except ")
      emitExpr(exceptionType)
      binder.foreach(name => emitInline(" as ", name))
      emitInline(":")
      indented:
        emitStat(handler)

    case ExprStat(expr) =>
      emitIndentedExpr(expr)

    case blk: Block =>
      emitBlock(blk)

  /** Emit a block (list of statements) */
  private def emitBlock(block: Block)(using ctx: Context): Unit =
    def newLineForControl(stat: Tree) =
      stat match
        case _: IfStat | _: While | _: TryExcept =>
          emitNewline()
          true

        case _ =>
          false

    val statements = block.statements
    if statements.isEmpty then
      emitLine("pass")
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

  /** Emit an expression, grouping compound expressions when nested. */
  def emitExpr(expr: Expr, nested: Boolean = false)(using ctx: Context): Unit =
    val needsParens = nested && (expr match
      case _: BinOp | _: UnaryOp | _: IfExpr | _: Lambda => true
      case _ => false
    )

    if needsParens then emitInline("(")

    expr match
      case IntLit(n) => emitInline(n.toString)
      case FloatLit(d) => emitInline(d.toString)
      case StringLit(s) => emitInline("\"" + escape(s) + "\"")
      case BoolLit(b) => emitInline(if b then "True" else "False")
      case NoneLit => emitInline("None")
      case Ident(name) => emitInline(name)

      case BinOp(left, op, right) =>
        emitExpr(left, nested = true)
        emitInline(" ", op, " ")
        emitExpr(right, nested = true)

      case UnaryOp(op, operand) =>
        emitInline(op, " ")
        emitExpr(operand, nested = true)

      case IfExpr(cond, thenBranch, elseBranch) =>
        // Python ternary: thenBranch if cond else elseBranch
        emitExpr(thenBranch, nested = true)
        emitInline(" if ")
        emitExpr(cond, nested = true)
        emitInline(" else ")
        emitExpr(elseBranch, nested = true)

      case Call(receiver, method, args) =>
        receiver match
          case Some(recv) =>
            emitExpr(recv, nested = true)
            emitInline(".", method)

          case None =>
            emitInline(method)

        emitInline("(")
        args.zipWithIndex.foreach: (arg, i) =>
          if i > 0 then emitInline(", ")
          emitExpr(arg)
        emitInline(")")

      case LambdaCall(fun, args) =>
        emitExpr(fun, nested = true)
        emitInline("(")
        args.zipWithIndex.foreach: (arg, i) =>
          if i > 0 then emitInline(", ")
          emitExpr(arg)
        emitInline(")")

      case Lambda(params, body) =>
        emitInline("lambda ", params.mkString(", "), ": ")
        emitExpr(body)

      case New(className, args) =>
        emitInline(className, "(")
        args.zipWithIndex.foreach: (arg, i) =>
          if i > 0 then emitInline(", ")
          emitExpr(arg)
        emitInline(")")

      case Select(receiver, member) =>
        emitExpr(receiver, nested = true)
        emitInline(".", member)

      case Index(receiver, index) =>
        emitExpr(receiver, nested = true)
        emitInline("[")
        index match
          case Slice(start, end) =>
            // Python slice: receiver[start:end]
            emitExpr(start)
            emitInline(":")
            emitExpr(end)
          case _ =>
            emitExpr(index)
        emitInline("]")

      case Slice(start, end) =>
        // Slice should only appear inside Index
        throw new Exception("Slice should only appear as index argument")

      case InstanceOf(value, className) =>
        emitInline("isinstance(")
        emitExpr(value)
        emitInline(", ", className, ")")

      case Starred(expr) =>
        emitInline("*")
        emitExpr(expr)

      case KwArg(key, value) =>
        emitInline(key, "=")
        emitExpr(value)

      case TupleLit(elems) =>
        emitInline("(")
        elems.zipWithIndex.foreach: (elem, i) =>
          if i > 0 then emitInline(", ")
          emitExpr(elem)
        if elems.size == 1 then emitInline(",")
        emitInline(")")

      case RawCode(code) =>
        // Emit raw Python code directly without modification
        emitInline(code)

    if needsParens then emitInline(")")

  /** Escape special characters in strings */
  private def escape(s: String): String =
    val sb = new StringBuilder
    s.codePoints().forEach: cp =>
      cp match
        case '\b' => sb ++= "\\b"
        case '\f' => sb ++= "\\f"
        case '\n' => sb ++= "\\n"
        case '\r' => sb ++= "\\r"
        case '\t' => sb ++= "\\t"
        case '"'  => sb ++= "\\\""
        case '\\' => sb ++= "\\\\"
        case _ if cp < 32 || cp > 126 =>
          if cp < 0xFFFF then
            sb ++= f"\\u${cp}%04x"
          else
            sb ++= f"\\U${cp}%08x"

        case _ => sb += cp.toChar

    sb.toString

end Printer

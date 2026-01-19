package ruby

import ruby.Trees.*

/** Pretty printer for Ruby AST with precedence-aware parenthesization
  *
  * Generates clean, idiomatic Ruby code from the AST.
  *
  * - Precedence-aware: Only adds parentheses when needed
  * - Proper indentation: Uses 2-space indentation (Ruby standard)
  *
  * Invariants:
  *
  * - printing of indentation is always preceded by newline
  * - a construct never add ending newline --- that comes from context
  */
object Printer:
  private val INDENT = "  "

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

  /** Invariant: indent is always preceded with newline */
  def emitIndentedTree(tree: Tree)(using ctx: Context): Unit =
    tree match
      case _: If | _: Block | _: Assign | _: While | _: FieldAssign =>
       emitTree(tree)

      case _ =>
        emitNewline()
        emit(INDENT * ctx.indent)
        emitTree(tree)

  /** Operator precedence levels (higher = tighter binding)
    * Based on Ruby operator precedence table
    */
  private def precedence(op: String): Int = op match
    case "||" => 1
    case "&&" => 2
    case "=="|"!=" => 4
    case "<"|">"|"<="|">=" => 5
    case "+"|"-" => 6
    case "*"|"/"|"%" => 7
    case "!"|"-" => 8
    case _ => 100  // Atomic expressions (no parens needed)

  /** Print a complete Ruby program */
  def print(program: Program, pw: java.io.PrintWriter): Unit =
    given ctx: Context = Context(0, pw)

    // Comment header
    emit("# Generated Ruby code")
    emitBlankLine()

    // Global initialization
    if program.globalInit.nonEmpty then
      program.globalInit.foreach: stat =>
        emitTree(stat)
      emitNewline()

    // Definitions
    program.defs.foreach: defn =>
      emitDef(defn)
      emitNewline()

    // Main call
    emitIndentedTree(program.mainCall)
    emitNewline()

  /** Emit a top-level definition */
  private def emitDef(defn: Def)(using ctx: Context): Unit = defn match
    case FunDef(name, params, body) =>
      emitLine("def ", name, "(", params.mkString(", "), ")")
      indented:
        emitIndentedTree(body)
      emitLine("end")

    case ClassDef(name, fields, methods, isObject) =>
      emitLine("class ", name)
      indented:
        // attr_accessor for fields
        if fields.nonEmpty then
          emitLine("attr_accessor ", fields.sorted.map(":" + _).mkString(", "))
          if methods.nonEmpty then emitNewline()

        // Methods
        methods.foreach: method =>
          emitDef(method)
          if method != methods.last then emitNewline()

        // Special handling for singleton objects
        if isObject then
          if methods.nonEmpty then emitNewline()
          emitLine("@instance = ", name, ".new")
          emitNewline()
          emitLine("def self.instance")
          indented:
            emitLine("@instance")
          emitLine("end")

      emitLine("end")

  /** Emit an expression with precedence context */
  def emitTree(tree: Tree, parentPrec: Int = 0)(using ctx: Context): Unit =
    def withParenthesisOpt(op: String)(work: Int => Unit): Unit =
      val myPrec = precedence(op)
      val needsParens = myPrec < parentPrec
      if needsParens then
        emitInline("(")
        work(myPrec)
        emitInline(")")

    tree match
      case IntLit(n) => emitInline(n.toString)
      case FloatLit(d) => emitInline(d.toString)
      case StringLit(s) => emitInline("\"" + escape(s) + "\"")
      case BoolLit(b) => emitInline(b.toString)
      case Nil => emitInline("nil")
      case Ident(name) => emitInline(name)

      case BinOp(op, left, right) =>
        withParenthesisOpt(op): myPrec =>
          emitTree(left, myPrec)
          emitInline(" ", op, " ")
          emitTree(right, myPrec)

      case UnaryOp(op, operand) =>
        withParenthesisOpt(op): myPrec =>
          emitInline(op)
          emitTree(operand, myPrec)

      case If(cond, thenBranch, elseBranch) =>
        emitIndented("if ")
        emitTree(cond, 0)(using ctx.indented)
        indented:
          emitIndentedTree(thenBranch)
        emitLine("else")
        indented:
          emitIndentedTree(elseBranch)
        emitLine("end")

      case Call(receiver, method, args) =>
        receiver match
          case Some(recv) =>
            emitTree(recv, 0)
            emitInline(".", method)

          case None =>
            emitInline(method)

        emitInline("(")
        args.zipWithIndex.foreach: (arg, i) =>
          if i > 0 then emitInline(", ")
          emitTree(arg, 0)
        emitInline(")")

      case LambdaCall(fun, args) =>
        emitTree(fun, 0)
        emitInline(".call")

        emitInline("(")
        args.zipWithIndex.foreach: (arg, i) =>
          if i > 0 then emitInline(", ")
          emitTree(arg, 0)
        emitInline(")")

      case Lambda(params, body) =>
        emitInline("lambda { |", params.mkString(", "), "| ")
        emitTree(body, 0)
        emitInline(" }")

      case New(className, args) =>
        emitInline(className, ".new(")
        args.zipWithIndex.foreach: (arg, i) =>
          if i > 0 then emitInline(", ")
          emitTree(arg, 0)
        emitInline(")")

      case Select(receiver, member) =>
        emitTree(receiver, 0)
        emitInline(".", member)

      case Block(statements) =>
        def newLineForControl(stat: Tree) =
          stat match
            case _: If | _: While =>
              emitNewline()
              true

            case _ =>
              false

        statements.zipWithIndex.foreach: (stat, i) =>
          if i > 0 then
            newLineForControl(stat) || newLineForControl(statements(i - 1))

          emitIndentedTree(stat)

      case InstanceOf(value, className) =>
        emitTree(value, 0)
        emitInline(".is_a?(", className, ")")

      case RawCode(code) =>
        // Emit raw Ruby code directly without modification
        emitInline(code)

      case Assign(name, rhs) =>
        emitIndented(name, " = ")
        emitTree(rhs, 0)(using ctx.indented)

      case FieldAssign(receiver, field, rhs) =>
        receiver match
          case Some(recv) =>
            emitIndentedTree(recv)
            emitInline(".", field)
          case None =>
            emitIndented("@", field)
        emitInline(" = ")
        emitTree(rhs, 0)(using ctx.indented)

      case While(cond, body) =>
        emitLine("while ")
        emitTree(cond, 0)(using ctx.indented)
        indented:
          emitIndentedTree(body)
        emitLine("end")

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

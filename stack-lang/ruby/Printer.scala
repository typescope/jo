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

  /** Check if an expression needs parentheses in a given context
    *
    * @param child The child expression
    * @param parentPrec The precedence of the parent context
    * @return true if parentheses are needed
    */
  private def needsParens(child: Expr, parentPrec: Int): Boolean =
    precedence(child) < parentPrec

  /** Print a complete Ruby program */
  def print(program: Program): String =
    val sb = new StringBuilder

    // Comment header
    sb.append("# Generated Ruby code\n\n")

    // Global initialization
    if program.globalInit.nonEmpty then
      program.globalInit.foreach: stat =>
        sb.append(printStat(stat, 0))
        sb.append("\n")
      sb.append("\n")

    // Definitions
    program.defs.foreach: defn =>
      sb.append(printDef(defn, 0))
      sb.append("\n\n")

    // Main call
    sb.append(printExpr(program.mainCall, 0))
    sb.append("\n")

    sb.toString

  /** Print a top-level definition */
  private def printDef(defn: Def, indent: Int): String = defn match
    case FunDef(name, params, body) =>
      val sb = new StringBuilder
      sb.append(INDENT * indent)
      sb.append("def ")
      sb.append(name)
      sb.append("(")
      sb.append(params.mkString(", "))
      sb.append(")")
      sb.append("\n")
      // For multi-line constructs (If, Block, While), indentation is handled internally
      // For atomic expressions, we need to add indentation
      val bodyStr = printExpr(body, indent + 1)
      val needsIndent = !body.isInstanceOf[If] && !body.isInstanceOf[Block] && !body.isInstanceOf[While]
      if needsIndent then
        sb.append(INDENT * (indent + 1))
      sb.append(bodyStr)
      sb.append("\n")
      sb.append(INDENT * indent)
      sb.append("end")
      sb.toString

    case ClassDef(name, fields, methods, isObject) =>
      val sb = new StringBuilder
      sb.append(INDENT * indent)
      sb.append("class ")
      sb.append(name)
      sb.append("\n")

      // attr_accessor for fields
      if fields.nonEmpty then
        sb.append(INDENT * (indent + 1))
        sb.append("attr_accessor ")
        sb.append(fields.sorted.map(":" + _).mkString(", "))
        sb.append("\n")
        if methods.nonEmpty then
          sb.append("\n")

      // Methods
      methods.foreach: method =>
        sb.append(printDef(method, indent + 1))
        sb.append("\n")
        if method != methods.last then
          sb.append("\n")

      // Special handling for singleton objects
      if isObject then
        if methods.nonEmpty then
          sb.append("\n")
        sb.append(INDENT * (indent + 1))
        sb.append("@instance = ")
        sb.append(name)
        sb.append(".new\n\n")
        sb.append(INDENT * (indent + 1))
        sb.append("def self.instance\n")
        sb.append(INDENT * (indent + 2))
        sb.append("@instance\n")
        sb.append(INDENT * (indent + 1))
        sb.append("end\n")

      sb.append(INDENT * indent)
      sb.append("end")
      sb.toString

  /** Print an expression with precedence context
    *
    * @param expr The expression to print
    * @param indent Current indentation level
    * @param parentPrec Precedence of parent context (for parenthesization)
    * @return String representation of the expression
    */
  def printExpr(expr: Expr, indent: Int, parentPrec: Int = 0): String =
    val result = expr match
      case IntLit(n) => n.toString
      case FloatLit(d) => d.toString
      case StringLit(s) => "\"" + escape(s) + "\""
      case BoolLit(b) => b.toString
      case Nil => "nil"
      case Ident(name) => name

      case BinOp(op, left, right) =>
        val myPrec = precedence(expr)
        val l = printExpr(left, indent, myPrec)
        val r = printExpr(right, indent, myPrec)
        s"$l $op $r"

      case UnaryOp(op, operand) =>
        val myPrec = precedence(expr)
        val needsParens = precedence(operand) < myPrec
        val o = printExpr(operand, indent, myPrec)
        if needsParens then s"$op($o)" else s"$op$o"

      case If(cond, thenBranch, elseBranch) =>
        val sb = new StringBuilder
        val c = printExpr(cond, indent, 0)
        sb.append(INDENT * indent)
        sb.append("if ")
        sb.append(c)
        sb.append("\n")
        // Add indentation for branches if they're not multi-line constructs
        val thenNeedsIndent = !thenBranch.isInstanceOf[If] && !thenBranch.isInstanceOf[Block] && !thenBranch.isInstanceOf[While]
        if thenNeedsIndent then
          sb.append(INDENT * (indent + 1))
        sb.append(printExpr(thenBranch, indent + 1))
        sb.append("\n")
        sb.append(INDENT * indent)
        sb.append("else\n")
        val elseNeedsIndent = !elseBranch.isInstanceOf[If] && !elseBranch.isInstanceOf[Block] && !elseBranch.isInstanceOf[While]
        if elseNeedsIndent then
          sb.append(INDENT * (indent + 1))
        sb.append(printExpr(elseBranch, indent + 1))
        sb.append("\n")
        sb.append(INDENT * indent)
        sb.append("end")
        sb.toString

      case Call(receiver, method, args, isLambdaCall) =>
        val sb = new StringBuilder
        receiver match
          case Some(recv) =>
            val needsParens = precedence(recv) < 100
            if needsParens then
              sb.append("(")
              sb.append(printExpr(recv, indent, 0))
              sb.append(")")
            else
              sb.append(printExpr(recv, indent, 0))
            if isLambdaCall then
              sb.append(".call")
            else
              sb.append(".")
              sb.append(method)
          case None =>
            sb.append(method)

        sb.append("(")
        sb.append(args.map(printExpr(_, indent, 0)).mkString(", "))
        sb.append(")")
        sb.toString

      case Lambda(params, body) =>
        val sb = new StringBuilder
        sb.append("lambda { |")
        sb.append(params.mkString(", "))
        sb.append("| ")
        sb.append(printExpr(body, indent, 0))
        sb.append(" }")
        sb.toString

      case New(className, args) =>
        val sb = new StringBuilder
        sb.append(className)
        sb.append(".new(")
        sb.append(args.map(printExpr(_, indent, 0)).mkString(", "))
        sb.append(")")
        sb.toString

      case Select(receiver, member) =>
        val needsParens = precedence(receiver) < 100
        val recv = if needsParens then
          s"(${printExpr(receiver, indent, 0)})"
        else
          printExpr(receiver, indent, 0)
        s"$recv.$member"

      case Block(statements, result) =>
        val sb = new StringBuilder
        statements.foreach: stat =>
          sb.append(printStat(stat, indent))
          sb.append("\n")
        sb.append(printExpr(result, indent))
        sb.toString

      case InstanceOf(value, className) =>
        val v = printExpr(value, indent, 0)
        s"$v.is_a?($className)"

      case RawCode(code) =>
        // Emit raw Ruby code directly without modification
        code

    // Add parentheses if needed based on context
    if needsParens(expr, parentPrec) then s"($result)" else result

  /** Print a statement */
  private def printStat(stat: Stat, indent: Int): String =
    val sb = new StringBuilder
    sb.append(INDENT * indent)

    stat match
      case Assign(name, rhs) =>
        sb.append(name)
        sb.append(" = ")
        sb.append(printExpr(rhs, indent, 0))

      case FieldAssign(receiver, field, rhs) =>
        receiver match
          case Some(recv) =>
            sb.append(printExpr(recv, indent, 0))
            sb.append(".")
            sb.append(field)
          case None =>
            sb.append("@")
            sb.append(field)
        sb.append(" = ")
        sb.append(printExpr(rhs, indent, 0))

      case While(cond, body) =>
        sb.append("while true\n")
        sb.append(INDENT * (indent + 1))
        sb.append("break unless ")
        sb.append(printExpr(cond, indent + 1, 0))
        sb.append("\n")
        body.foreach: stat =>
          sb.append(printStat(stat, indent + 1))
          sb.append("\n")
        sb.append(INDENT * indent)
        sb.append("end")

      case ExprStat(expr) =>
        sb.append(printExpr(expr, indent, 0))

    sb.toString

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

package python

object Trees:

  // Base trait
  sealed trait Tree

  // Statements don't produce values
  sealed trait Stat extends Tree

  // Expressions produce values
  sealed trait Expr extends Tree

  //==========================================================================
  // EXPRESSIONS - produce values
  //==========================================================================

  /** Literals */
  case class IntLit(value: Long) extends Expr
  case class FloatLit(value: Double) extends Expr
  case class StringLit(value: String) extends Expr
  case class BoolLit(value: Boolean) extends Expr
  case object NoneLit extends Expr

  /** Variable reference */
  case class Ident(name: String) extends Expr

  /** Binary operation: left op right
    * Examples: a + b, x == y, a and b
    */
  case class BinOp(left: Expr, op: String, right: Expr) extends Expr

  /** Unary operation: op operand
    * Examples: not x, -y
    */
  case class UnaryOp(op: String, operand: Expr) extends Expr

  /** If-expression (ternary): thenBranch if cond else elseBranch
    * Both branches must be simple expressions (no statements allowed)
    * Examples: x if a > 0 else y
    */
  case class IfExpr(cond: Expr, thenBranch: Expr, elseBranch: Expr) extends Expr

  /** Function/method call: receiver.method(args) or function(args)
    * - For functions: receiver = None
    * - For methods: receiver = Some(obj)
    */
  case class Call(receiver: Option[Expr], method: String, args: List[Expr]) extends Expr

  /** Lambda call: fun(args) where fun is a lambda value */
  case class LambdaCall(fun: Expr, args: List[Expr]) extends Expr

  /** Lambda expression: lambda params: body
    * Body must be a single expression (Python limitation)
    */
  case class Lambda(params: List[String], body: Expr) extends Expr

  /** Object creation: ClassName(args) */
  case class New(className: String, args: List[Expr]) extends Expr

  /** Attribute access: receiver.member
    * This is for field/attribute access; method calls use Call
    */
  case class Select(receiver: Expr, member: String) extends Expr

  /** Index access: receiver[index]
    * For array/string indexing: arr[i], s[index]
    */
  case class Index(receiver: Expr, index: Expr) extends Expr

  /** Slice: start:end for Python slicing
    * Only valid as the index in Index: receiver[Slice(start, end)] → receiver[start:end]
    */
  case class Slice(start: Expr, end: Expr) extends Expr

  /** Type test: isinstance(value, ClassName) */
  case class InstanceOf(value: Expr, className: String) extends Expr

  /** Raw Python code: embed a string directly as Python code
    * Used for the `python "..."` primitive to inline platform-specific code
    */
  case class RawCode(code: String) extends Expr

  //==========================================================================
  // STATEMENTS - don't produce values
  //==========================================================================

  /** Local variable assignment: name = rhs */
  case class Assign(name: String, rhs: Expr) extends Stat

  /** Attribute assignment: receiver.field = rhs or self.field = rhs */
  case class AttrAssign(receiver: Expr, attr: String, rhs: Expr) extends Stat

  /** Index assignment: receiver[index] = rhs
    * For array element assignment: arr[i] = value
    */
  case class IndexAssign(receiver: Expr, index: Expr, rhs: Expr) extends Stat

  /** If-statement: if/elif/else with statement blocks
    * Used in statement context for complex control flow
    */
  case class IfStat(cond: Expr, thenBranch: Stat, elseBranch: Stat) extends Stat

  /** While loop: while cond: body
    * Python while is a statement (returns None)
    */
  case class While(cond: Expr, body: Stat) extends Stat

  /** Return statement: return value */
  case class Return(value: Expr) extends Stat

  /** Raise statement: raise exception
    * Used to throw exceptions. Cannot be used as an expression.
    */
  case class Raise(exception: Expr) extends Stat

  /** Expression statement: evaluate expr for side effects
    * Used when an expression is used as a statement (e.g., function call)
    */
  case class ExprStat(expr: Expr) extends Stat

  /** Block: sequence of statements
    * Executes statements in order. In Python, this is just a list of statements
    * at the same indentation level.
    */
  case class Block(statements: List[Stat]) extends Stat

  //==========================================================================
  // TOP-LEVEL DEFINITIONS
  //==========================================================================

  /** Base trait for top-level definitions */
  sealed trait Def

  /** Function definition: def name(params): body
    * Body is a Block (sequence of statements)
    */
  case class FunDef(name: String, params: List[String], body: Block) extends Def

  /** Class definition:
    * class Name:
    *     [methods]  # Including __init__ if present
    */
  case class ClassDef(
    name: String,
    fields: List[String],           // Field names (for reference, not auto-generated)
    methods: List[FunDef]           // Method definitions (including __init__)
  ) extends Def

  /** Complete Python program */
  case class Program(
    defs: List[Def],               // Function and class definitions
    mainCall: Stat                 // Entry point (initialization + start call in a Block)
  )

end Trees

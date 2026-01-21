package js

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
  case object NullLit extends Expr
  case object UndefinedLit extends Expr

  /** Variable reference */
  case class Ident(name: String) extends Expr

  /** Binary operation: left op right
    * Examples: a + b, x === y, a && b
    */
  case class BinOp(left: Expr, op: String, right: Expr) extends Expr

  /** Unary operation: op operand
    * Examples: !x, -y, typeof x
    */
  case class UnaryOp(op: String, operand: Expr) extends Expr

  /** Conditional expression (ternary): cond ? thenBranch : elseBranch
    * Both branches must be simple expressions (no statements allowed)
    * Examples: x > 0 ? a : b
    */
  case class Conditional(cond: Expr, thenBranch: Expr, elseBranch: Expr) extends Expr

  /** Function/method call: receiver.method(args) or function(args)
    * - For functions: receiver = None
    * - For methods: receiver = Some(obj)
    */
  case class Call(receiver: Option[Expr], method: String, args: List[Expr]) extends Expr

  /** Arrow function (simple): (params) => body
    * Body must be a single expression (implicit return)
    * Examples: (x) => x * 2, (a, b) => a + b
    */
  case class Arrow(params: List[String], body: Expr) extends Expr

  /** Function expression (complex): function(params) { body }
    * Body is a block of statements
    * Used when function needs multiple statements
    */
  case class Function(params: List[String], body: Block) extends Expr

  /** Object creation: new ClassName(args) */
  case class New(className: String, args: List[Expr]) extends Expr

  /** Property access: receiver.member
    * This is for field/property access; method calls use Call
    */
  case class Select(receiver: Expr, member: String) extends Expr

  /** Index access: receiver[index]
    * For array/object indexing: arr[i], obj[key]
    */
  case class Index(receiver: Expr, index: Expr) extends Expr

  /** Array literal: [elements]
    * Examples: [1, 2, 3], [a, b, c]
    */
  case class ArrayLit(elements: List[Expr]) extends Expr

  /** Object literal: { key1: value1, key2: value2 }
    * Examples: { x: 1, y: 2 }, { name: "foo", age: 42 }
    */
  case class ObjectLit(fields: List[(String, Expr)]) extends Expr

  /** Type test: value instanceof ClassName */
  case class InstanceOf(value: Expr, className: String) extends Expr

  /** Raw JavaScript code: embed a string directly as JavaScript code
    * Used for the `javascript "..."` primitive to inline platform-specific code
    */
  case class RawCode(code: String) extends Expr

  //==========================================================================
  // STATEMENTS - don't produce values
  //==========================================================================

  /** Variable declaration: keyword name = init
    * JavaScript supports const (default), let (mutable), and var (function-scoped)
    */
  case class VarDecl(keyword: String, name: String, init: Expr) extends Stat

  /** Assignment: target = value */
  case class Assign(name: String, value: Expr) extends Stat

  /** Field assignment: receiver.field = rhs */
  case class FieldAssign(receiver: Expr, field: String, rhs: Expr) extends Stat

  /** If-statement: if/else with statement blocks
    * Used in statement context for complex control flow
    */
  case class IfStat(cond: Expr, thenBranch: Stat, elseBranch: Stat) extends Stat

  /** While loop: while (cond) { body }
    * JavaScript while is a statement (returns undefined)
    */
  case class While(cond: Expr, body: Stat) extends Stat

  /** Break inside while loop */
  case object Break extends Stat

  /** Return statement: return value */
  case class Return(value: Expr) extends Stat

  /** Throw statement: throw exception
    * Used to throw exceptions. Cannot be used as an expression.
    */
  case class Throw(exception: Expr) extends Stat

  /** Expression statement: evaluate expr for side effects
    * Used when an expression is used as a statement (e.g., function call)
    */
  case class ExprStat(expr: Expr) extends Stat

  /** Block: sequence of statements
    * Executes statements in order. In JavaScript, this is just a list of statements
    * at the same brace level.
    */
  case class Block(statements: List[Stat]) extends Stat

  //==========================================================================
  // TOP-LEVEL DEFINITIONS
  //==========================================================================

  /** Base trait for top-level definitions */
  sealed trait Def

  /** Function definition: function name(params) { body }
    * Body is a Block (sequence of statements)
    */
  case class FunDef(name: String, params: List[String], body: Block) extends Def

  /** Class definition:
    * class Name {
    *     [static fields]            # Static field initializations
    *     [methods]                  # Including constructor if present
    * }
    */
  case class ClassDef(
    name: String,
    fields: List[String],           // Field names (for reference, not auto-generated)
    methods: List[FunDef],          // Method definitions (including constructor)
    staticFields: List[Assign]      // Static field initializations: (fieldName, value)
  ) extends Def

  /** Complete JavaScript program */
  case class Program(
    defs: List[Def],               // Function and class definitions
    mainCall: Stat                 // Entry point (initialization + start call in a Block)
  )

end Trees

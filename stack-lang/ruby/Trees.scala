package ruby

/** Abstract Syntax Tree for Ruby code generation
  *
  * This is a minimal AST representing Ruby constructs needed for Jo compilation.
  * Design principles:
  * - Expression-oriented: Most constructs return values
  * - Minimal: Only what we need, expand as needed
  * - Clean separation: Semantics separate from pretty printing
  */
object Trees:

  /** Base trait for Ruby expressions (things that produce values) */
  sealed trait Expr

  /** Literals */
  case class IntLit(value: Long) extends Expr
  case class FloatLit(value: Double) extends Expr
  case class StringLit(value: String) extends Expr
  case class BoolLit(value: Boolean) extends Expr
  case object Nil extends Expr

  /** Variable/function reference */
  case class Ident(name: String) extends Expr

  /** Binary operation: left op right
    * Examples: a + b, x == y, a && b
    */
  case class BinOp(op: String, left: Expr, right: Expr) extends Expr

  /** Unary operation: op operand
    * Examples: !x, -y
    */
  case class UnaryOp(op: String, operand: Expr) extends Expr

  /** If expression: if cond then thenBranch else elseBranch end
    * Ruby if is an expression, returns the value of the taken branch
    */
  case class If(cond: Expr, thenBranch: Expr, elseBranch: Expr) extends Expr

  /** Function/method call: receiver.method(args) or function(args)
    * - For functions: receiver = None
    * - For methods: receiver = Some(obj)
    * - For lambdas: use isLambdaCall = true to generate .call() syntax
    */
  case class Call(
    receiver: Option[Expr],
    method: String,
    args: List[Expr],
    isLambdaCall: Boolean = false
  ) extends Expr

  /** Lambda expression: lambda { |params| body }
    * Ruby blocks/procs/lambdas - using lambda for clarity
    */
  case class Lambda(params: List[String], body: Expr) extends Expr

  /** Object creation: ClassName.new(args) */
  case class New(className: String, args: List[Expr]) extends Expr

  /** Field/method access: receiver.member
    * This is just for field access; method calls use Call
    */
  case class Select(receiver: Expr, member: String) extends Expr

  /** Block expression: stats; ...; result
    * Executes statements in sequence, returns result
    * Empty block (no statements, no result) is represented as Nil
    */
  case class Block(statements: List[Stat], result: Expr) extends Expr

  /** Type test: value.is_a?(ClassName) */
  case class InstanceOf(value: Expr, className: String) extends Expr

  /** Raw Ruby code: embed a string directly as Ruby code
    * Used for the `ruby "..."` primitive to inline platform-specific code
    * Example: ruby "str[index].ord" becomes the raw Ruby expression str[index].ord
    */
  case class RawCode(code: String) extends Expr

  /** Base trait for Ruby statements (things that don't produce useful values) */
  sealed trait Stat

  /** Local variable assignment: name = rhs */
  case class Assign(name: String, rhs: Expr) extends Stat

  /** Field assignment: receiver.field = rhs or @field = rhs */
  case class FieldAssign(receiver: Option[Expr], field: String, rhs: Expr) extends Stat

  /** While loop: while true do cond check and body end
    * Ruby while is a statement (returns nil)
    */
  case class While(cond: Expr, body: List[Stat]) extends Stat

  /** Expression as statement (evaluate for side effects, ignore result) */
  case class ExprStat(expr: Expr) extends Stat

  /** Base trait for top-level definitions */
  sealed trait Def

  /** Function definition: def name(params) body end
    * Body is an expression (Ruby functions return last expression value)
    */
  case class FunDef(name: String, params: List[String], body: Expr) extends Def

  /** Class definition:
    * class Name
    *   attr_accessor :field1, :field2
    *   [methods]
    * end
    */
  case class ClassDef(
    name: String,
    fields: List[String],      // Field names for attr_accessor
    methods: List[FunDef],     // Method definitions (including initialize)
    isObject: Boolean = false  // True for singleton objects (Jo objects)
  ) extends Def

  /** Complete Ruby program */
  case class Program(
    globalInit: List[Stat],  // Global initialization code (e.g., $runtime_contextParams = {})
    defs: List[Def],         // Function and class definitions
    mainCall: Expr           // Entry point call (e.g., jo_runtime_Ruby_start())
  )

end Trees

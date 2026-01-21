package ruby

/** Abstract Syntax Tree for Ruby code generation
  *
  * This is a minimal AST representing Ruby constructs needed for Jo compilation.
  *
  * Design principles:
  * - Minimal: Only what we need, expand as needed
  * - Clean separation: Semantics separate from pretty printing
  */
object Trees:

  sealed trait Tree

  /** Literals */
  case class IntLit(value: Long) extends Tree
  case class FloatLit(value: Double) extends Tree
  case class StringLit(value: String) extends Tree
  case class BoolLit(value: Boolean) extends Tree
  case object Nil extends Tree

  /** Variable/function reference */
  case class Ident(name: String) extends Tree

  /** Binary operation: left op right
    * Examples: a + b, x == y, a && b
    */
  case class BinOp(op: String, left: Tree, right: Tree) extends Tree

  /** Unary operation: op operand
    * Examples: !x, -y
    */
  case class UnaryOp(op: String, operand: Tree) extends Tree

  /** If expression: if cond then thenBranch else elseBranch end
    * Ruby if is an expression, returns the value of the taken branch
    */
  case class If(cond: Tree, thenBranch: Tree, elseBranch: Tree) extends Tree

  /** Function/method call: receiver.method(args) or function(args)
    * - For functions: receiver = None
    * - For methods: receiver = Some(obj)
    */
  case class Call(receiver: Option[Tree], method: String, args: List[Tree]) extends Tree

  case class LambdaCall(fun: Tree, args: List[Tree]) extends Tree

  /** Lambda expression: lambda { |params| body }
    * Ruby blocks/procs/lambdas - using lambda for clarity
    */
  case class Lambda(params: List[String], body: Tree) extends Tree

  /** Object creation: ClassName.new(args) */
  case class New(className: String, args: List[Tree]) extends Tree

  /** Field/method access: receiver.member
    * This is just for field access; method calls use Call
    */
  case class Select(receiver: Tree, member: String) extends Tree

  /** Index access: receiver[args...]
    * For array/string indexing and slicing
    * Examples: arr[i], str[i], str[start, len]
    */
  case class Index(receiver: Tree, args: List[Tree]) extends Tree

  /** Block expression: stats; ...; result
    * Executes statements in sequence, returns result
    */
  case class Block(statements: List[Tree]) extends Tree

  /** Type test: value.is_a?(ClassName) */
  case class InstanceOf(value: Tree, className: String) extends Tree

  /** Raw Ruby code: embed a string directly as Ruby code
    * Used for the `ruby "..."` primitive to inline platform-specific code
    * Example: ruby "str[index].ord" becomes the raw Ruby expression str[index].ord
    */
  case class RawCode(code: String) extends Tree

  /** Local variable assignment: name = rhs */
  case class Assign(name: String, rhs: Tree) extends Tree

  /** Field assignment: receiver.field = rhs or @field = rhs */
  case class FieldAssign(receiver: Option[Tree], field: String, rhs: Tree) extends Tree

  /** While loop: while true do cond check and body end
    * Ruby while is a statement (returns nil)
    */
  case class While(cond: Tree, body: Tree) extends Tree

  /** Base trait for top-level definitions */
  sealed trait Def

  /** Function definition: def name(params) body end
    * Body is an expression (Ruby functions return last expression value)
    */
  case class FunDef(name: String, params: List[String], body: Tree) extends Def

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
    globalInit: List[Tree],  // Global initialization code (e.g., $runtime_contextParams = {})
    defs: List[Def],         // Function and class definitions
    mainCall: Tree           // Entry point call (e.g., jo_runtime_Ruby_start())
  )

end Trees

/************************************************************************
 *                                                                      *
 * The implementation of the stack-oriented language in an interpreter. *
 *                                                                      *
 * Run the interpreter with scala-cli:                                  *
 *                                                                      *
 *     scala-cli interpreter.scala -- "3 5 + p"                         *
 *                                                                      *
 ************************************************************************/

//> using file Ast.scala
//> using file Sast.scala
//> using file Symbols.scala
//> using file Types.scala
//> using file Namer.scala
//> using file Checker.scala
//> using file Parser.scala
//> using file IO.scala
//> using file Reporter.scala

import scala.collection.mutable

import Sast.*
import Symbols.*

def err(msg: String) = throw new Exception(msg)

/***********************************************************************
 *
 * Value Definitions
 *
 ***********************************************************************/

class ValueStack:
  val stack: mutable.ArrayBuffer[Value] = new mutable.ArrayBuffer

  def pop(): Value =
    if stack.nonEmpty then stack.remove(stack.size - 1)
    else err("Stack is empty")

  def push(v: Value): Unit = stack.append(v)

sealed abstract class Denotation

enum Value extends Denotation:
  case IntVal(value: Int)
  case BoolVal(value: Boolean)
  case RecordVal(fields: Map[String, Value])

object Uninit extends Denotation

enum Action extends Denotation:
  case Fun(fun: Sast.Fun, scope: Scope)
  case Prim(fun: ValueStack => Unit)

enum Scope:
  case RootScope()
  case NestedScope(outer: Scope)

  private val map: mutable.Map[Symbol, Denotation] = mutable.Map.empty

  def resolve(sym: Symbol): Option[Denotation] =
    map.get(sym) match
      case None =>
        this match
          case NestedScope(outer) => outer.resolve(sym)
          case _ => None

      case res  => res

  def update(sym: Symbol, denot: Denotation): Unit =
    if map.contains(sym) then
      map(sym) = denot
    else
      this match
        case NestedScope(outer) => outer.update(sym, denot)
        case _ => err("Unknown name to update: " + sym.name)

  def bind(sym: Symbol, denot: Denotation): Unit =
    map.get(sym) match
      case None =>
        map(sym) = denot

      case Some(d) =>
        err(sym.name + " is already bound to " + d)

object Primitive:
  import Value.*

  def int2(op: (Int, Int) => Int)(vs: ValueStack): Unit =
    (vs.pop(), vs.pop()) match
      case (IntVal(a), IntVal(b)) => vs.push(IntVal(op(b, a)))
      case (v1, v2) => err("Expect two integers, found " + v1 + " and " + v2)

  def int2bool(op: (Int, Int) => Boolean)(vs: ValueStack): Unit =
    (vs.pop(), vs.pop()) match
      case (IntVal(a), IntVal(b)) => vs.push(BoolVal(op(b, a)))
      case (v1, v2) => err("Expect two integers, found " + v1 + " and " + v2)

  def bool2(op: (Boolean, Boolean) => Boolean)(vs: ValueStack): Unit =
    (vs.pop(), vs.pop()) match
      case (BoolVal(a), BoolVal(b)) => vs.push(BoolVal(op(b, a)))
      case (v1, v2) => err("Expect two booleans, found " + v1 + " and " + v2)

  def bool1(op: Boolean => Boolean)(vs: ValueStack): Unit =
    vs.pop() match
      case BoolVal(a) => vs.push(BoolVal(op(a)))
      case v => err("Expect a boolean, found " + v)

  def add(vs: ValueStack) = int2(_ + _)(vs)
  def sub(vs: ValueStack) = int2(_ - _)(vs)
  def mul(vs: ValueStack) = int2(_ * _)(vs)
  def div(vs: ValueStack) = int2(_ / _)(vs)
  def mod(vs: ValueStack) = int2(_ % _)(vs)

  def lt(vs: ValueStack) = int2bool(_ <  _)(vs)
  def gt(vs: ValueStack) = int2bool(_ >  _)(vs)
  def le(vs: ValueStack) = int2bool(_ <= _)(vs)
  def ge(vs: ValueStack) = int2bool(_ >= _)(vs)

  def sll (vs: ValueStack) = int2(_ << _)(vs)
  def srl (vs: ValueStack) = int2(_ >> _)(vs)
  def land(vs: ValueStack) = int2(_ &  _)(vs)
  def lor (vs: ValueStack) = int2(_ |  _)(vs)
  def lxor(vs: ValueStack) = int2(_ ^  _)(vs)

  def band(vs: ValueStack) = bool2(_ && _)(vs)
  def bor (vs: ValueStack) = bool2(_ || _)(vs)
  def bnot(vs: ValueStack) = bool1(! _   )(vs)

  def eql(vs: ValueStack) = vs.push(BoolVal(vs.pop() == vs.pop()))

  def print(vs: ValueStack) =
    val IntVal(v) = vs.pop(): @unchecked
    println(v)

  def abort(vs: ValueStack) =
    val IntVal(v) = vs.pop(): @unchecked
    throw new Exception(v)

  val operators: Map[Symbol, ValueStack => Unit] = Map(
      predef.add    ->    add,
      predef.sub    ->    sub,
      predef.mul    ->    mul,
      predef.div    ->    div,
      predef.mod    ->    mod,
      predef.gt     ->    gt,
      predef.lt     ->    lt,
      predef.ge     ->    ge,
      predef.le     ->    le,
      predef.srl    ->    srl,
      predef.sll    ->    sll,
      predef.land   ->    land,
      predef.lor    ->    lor,
      predef.lxor   ->    lxor,
      predef.band   ->    band,
      predef.bor    ->    bor,
      predef.bnot   ->    bnot,
      predef.eql    ->    eql,
      predef.p      ->    print
      runtime.abort ->    abort
  )

object Interpreter:
  def exec(prog: Prog): Unit =
    val rootScope = new Scope.RootScope()

    for (k, v) <- Primitive.operators do
      rootScope.bind(k, Action.Prim(v))

    val sc = new Scope.NestedScope(rootScope)
    for fun <- prog.funs do
      sc.bind(fun.symbol, Action.Fun(fun, sc))

    for sym <- prog.vals do
      sc.bind(sym, Uninit)

    val vs = new ValueStack
    exec(prog.main)(using vs, sc)

  def exec(phrase: Phrase)(using ValueStack, Scope): Unit =
    for word <- phrase.words do exec(word)

  def exec(sym: Symbol)(using vs: ValueStack, sc: Scope): Unit =
    val Some(denot) = sc.resolve(sym): @unchecked
    denot match
      case value: Value =>
        vs.push(value)

      case Uninit =>
        err("Accessing uninitialized variable " + sym)

      case Action.Prim(fun) => fun(vs)

      case Action.Fun(Fun(_, params, locals, body), sc2) =>
        val funScope = new Scope.NestedScope(sc2)
        for param <- params.reverse do
          funScope.bind(param, vs.pop())
        for param <- locals do
          funScope.bind(param, Uninit)
        exec(body)(using vs, funScope)

  def exec(word: Word)(using vs: ValueStack, sc: Scope): Unit =
    word match
      case IntLit(v)  => vs.push(Value.IntVal(v))

      case BoolLit(v) => vs.push(Value.BoolVal(v))

      case Encoded(repr) => exec(repr)

      case RecordLit(args) =>
        val fieldValues = mutable.Map.empty[String, Value]
        for (name, arg) <- args do
          exec(arg)
          fieldValues(name) = vs.pop()
        vs.push(Value.RecordVal(fieldValues.toMap))

      case Select(qual, name) =>
        exec(qual)
        val Value.RecordVal(fieldVals) = vs.pop(): @unchecked
        vs.push(fieldVals(name))

      case Assign(sym, words) =>
        exec(words)
        sc.update(sym, vs.pop())

      case If(cond, thenp, elsep) =>
        exec(cond)
        val Value.BoolVal(b) = vs.pop(): @unchecked
        if b then exec(thenp) else exec(elsep)

      case While(cond, body) =>
        exec(cond)
        val Value.BoolVal(b) = vs.pop(): @unchecked
        if b then
          exec(body)
          exec(word)

      case phrase: Phrase =>
        exec(phrase)

      case Ident(sym) =>
        exec(sym)

/***********************************************************************
 *
 * Main entry point
 *
 ***********************************************************************/
@main
def run(file: String) = Reporter.monitor:
  given Reporter = Reporter.withSource(file)
  IO.fileContent(file)    |>
  Parsing.parse           |>
  new Namer().transform   |>
  Interpreter.exec

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
//> using file Namer.scala
//> using file Checker.scala
//> using file Parser.scala
//> using file IO.scala
//> using file Reporter.scala

import scala.collection.mutable

import Sast.*

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

enum Action extends Denotation:
  case Fun(params: List[Symbol], words: List[Word], scope: Scope)
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
    vs.pop() match
      case IntVal(v)  => println(v)
      case BoolVal(v) => println(v)

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
      predef.p      ->    print,
  )

object Interpreter:
  def exec(prog: Prog): Unit =
    val rootScope = new Scope.RootScope()

    for (k, v) <- Primitive.operators do
      rootScope.bind(k, Action.Prim(v))

    val sc = new Scope.NestedScope(rootScope)
    for Fun(sym, params, words) <- prog.funs do
      sc.bind(sym, Action.Fun(params, words, sc))

    val vs = new ValueStack
    exec(Word.Ident(prog.main))(using vs, sc)

  def exec(words: List[Word])(using ValueStack, Scope): Unit =
    for word <- words do exec(word)

  def exec(word: Word)(using vs: ValueStack, sc: Scope): Unit =
    word match
      case Word.IntLit(v)  => vs.push(Value.IntVal(v))

      case Word.BoolLit(v) => vs.push(Value.BoolVal(v))

      case Word.Init(sym, words) =>
        exec(words)
        sc.bind(sym, vs.pop())

      case Word.If(cond, thenp, elsep) =>
        exec(cond)
        vs.pop() match
          case Value.BoolVal(b) =>
            if b then exec(thenp) else exec(elsep)

          case v =>
            err("Boolean value expected for if condition, found " + v)

      case Word.Ident(sym) =>
        sc.resolve(sym) match
          case Some(d) =>
            d match
              case value: Value       => vs.push(value)
              case Action.Prim(fun)   => fun(vs)

              case Action.Fun(params, ws, sc2) =>
                val funScope = new Scope.NestedScope(sc2)
                for param <- params.reverse do
                  funScope.bind(param, vs.pop())
                exec(ws)(using vs, funScope)

          case None =>
            err("Undefined identifier " + sym)

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

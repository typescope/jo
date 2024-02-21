/************************************************************************
 *                                                                      *
 * The implementation of the stack-oriented language in an interpreter. *
 *                                                                      *
 * Run the interpreter with scala-cli:                                  *
 *                                                                      *
 *     scala-cli interpreter.scala -- "3 5 + p"                         *
 *                                                                      *
 ************************************************************************/

//> using file ast.scala
//> using file sast.scala
//> using file namer.scala
//> using file parser.scala

import scala.collection.mutable

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

  def peek(n: Int): Value =
    if n >= stack.size then
      err("peek index out of stack bound, found = " + n)
    if n < 0 then
      err("peek index should be positive, found = " + n)
    else
      stack(stack.size - 1 - n)

sealed abstract class Denotation

enum Value extends Denotation:
  case IntVal(value: Int)
  case BoolVal(value: Boolean)
  case ProcVal(words: List[Sast.Word], scope: Scope)

enum Action extends Denotation:
  case Fun(words: List[Sast.Word], scope: Scope)
  case Prim(fun: ValueStack => Unit)

enum Scope:
  case RootScope()
  case NestedScope(outer: Scope)

  private val map: mutable.Map[Sast.Symbol, Denotation] = mutable.Map.empty

  def resolve(sym: Sast.Symbol): Option[Denotation] =
    map.get(sym) match
      case None =>
        this match
          case NestedScope(outer) => outer.resolve(sym)
          case _ => None

      case res  => res

  def bind(sym: Sast.Symbol, denot: Denotation): Unit =
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

  def run(vs: ValueStack) =
    vs.pop() match
      case ProcVal(ws, sc) => Interpreter.exec(ws)(using vs, sc)
      case v => err("Expect a procedure, found " + v)

  def eql(vs: ValueStack) = vs.push(BoolVal(vs.pop() == vs.pop()))

  def dup(vs: ValueStack) =
    val a = vs.pop()
    vs.push(a)
    vs.push(a)

  def peek(vs: ValueStack) =
    vs.pop() match
      case IntVal(n) =>
        vs.push(vs.peek(n))

      case v =>
        err("Expect a number, found " + v)

  def swap(vs: ValueStack) =
    val a = vs.pop()
    val b = vs.pop()
    vs.push(a)
    vs.push(b)

  def pop(vs: ValueStack) =
    vs.pop()

  def choose(vs: ValueStack) =
    val a = vs.pop()
    val b = vs.pop()
    val c = vs.pop()
    c match
      case BoolVal(cond) => if cond then vs.push(b) else vs.push(a)
      case v => err("Expect a boolean, found " + v)

  def print(vs: ValueStack) =
    vs.pop() match
      case IntVal(v)  => println(v)
      case BoolVal(v) => println(v)
      case v          => println(v)

  import Sast.{ predefs => defn }

  val operators: Map[Sast.Symbol, ValueStack => Unit] = Map(
      defn.add    ->    add,
      defn.sub    ->    sub,
      defn.mul    ->    mul,
      defn.div    ->    div,
      defn.mod    ->    mod,
      defn.gt     ->    gt,
      defn.lt     ->    lt,
      defn.ge     ->    ge,
      defn.le     ->    le,
      defn.srl    ->    srl,
      defn.sll    ->    sll,
      defn.land   ->    land,
      defn.lor    ->    lor,
      defn.lxor   ->    lxor,
      defn.band   ->    band,
      defn.bor    ->    bor,
      defn.bnot   ->    bnot,
      defn.run    ->    run,
      defn.eql    ->    eql,
      defn.dup    ->    dup,
      defn.swap   ->    swap,
      defn.peek   ->    peek,
      defn.pop    ->    pop,
      defn.choose ->    choose,
      defn.p      ->    print,
  )

object Interpreter:
  def exec(prog: Sast.Prog)(vs: ValueStack): Unit =
    val rootScope = new Scope.RootScope()

    for (k, v) <- Primitive.operators do
      rootScope.bind(k, Action.Prim(v))

    val sc = new Scope.NestedScope(rootScope)
    for case Sast.Def.FunDef(sym, words) <- prog.defs do
      sc.bind(sym, Action.Fun(words, sc))

    for case Sast.Def.ValDef(sym, words) <- prog.defs do
      exec(words)(using vs, sc)
      sc.bind(sym, vs.pop())

    exec(prog.main)(using vs, sc)

  def exec(words: List[Sast.Word])(using ValueStack, Scope): Unit =
    for word <- words do exec(word)

  def exec(word: Sast.Word)(using vs: ValueStack, sc: Scope): Unit =
    word match
      case Sast.Word.IntLit(v)  => vs.push(Value.IntVal(v))
      case Sast.Word.BoolLit(v) => vs.push(Value.BoolVal(v))
      case Sast.Word.Proc(ws)   => vs.push(Value.ProcVal(ws, sc))

      case Sast.Word.Ident(sym) =>
        sc.resolve(sym) match
          case Some(d) =>
            d match
              case Action.Fun(ws,sc2) => exec(ws)(using vs, sc2)
              case Action.Prim(fun)   => fun(vs)
              case value: Value       => vs.push(value)

          case None =>
            err("Undefined identifier " + sym)

/***********************************************************************
 *
 * Utilities
 *
 ***********************************************************************/

def err(msg: String) = throw new Exception(msg)

def fileContent(name: String): String =
  val path = java.nio.file.Path.of(name)
  val bytes = java.nio.file.Files.readAllBytes(path)
  new String(bytes)

/***********************************************************************
 *
 * Main entry point
 *
 ***********************************************************************/
@main
def run(file: String) =
  val vs = new ValueStack
  val ast = Parsing.parse(fileContent(file))
  val sast = Namer.transform(ast)
  Interpreter.exec(sast)(vs)

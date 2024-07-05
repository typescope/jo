/************************************************************************
 *                                                                      *
 * The implementation of the stack-oriented language in an interpreter. *
 *                                                                      *
 ************************************************************************/

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
  case FunVal(fun: Sast.FunDef, scope: Scope)

object Uninit extends Denotation
case class PrimAction(op: ValueStack => Unit) extends Denotation

enum Scope:
  case RootScope()
  case NestedScope(outer: Scope)

  private val map: mutable.Map[Symbol, Denotation] = mutable.Map.empty

  def resolve(sym: Symbol): Denotation =
    map.get(sym) match
      case None =>
        this match
          case NestedScope(outer) => outer.resolve(sym)
          case _ => throw new Exception("Not found " + sym)

      case Some(res)  => res

  def update(sym: Symbol, denot: Denotation): Unit =
    if map.contains(sym) then
      map(sym) = denot
    else
      this match
        case NestedScope(outer) => outer.update(sym, denot)
        case _ => err("Unknown name to update: " + sym.name)

  def bind(sym: Symbol, denot: Denotation): Unit =
    map(sym) = denot

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
    throw new Exception(v.toString)

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
      runtime.abort ->    abort
  )

object Interpreter:
  def exec(prog: Prog): Unit =
    val rootScope = new Scope.RootScope()

    for (sym, op) <- Primitive.operators do
      rootScope.bind(sym, PrimAction(op))

    val sc = new Scope.NestedScope(rootScope)
    for fun <- prog.funs do
      sc.bind(fun.symbol, Value.FunVal(fun, sc))

    val vs = new ValueStack
    for ValDef(sym, rhs) <- prog.vals do
      exec(rhs)(using vs, sc)
      sc.bind(sym, vs.pop())

    exec(prog.main)(using vs, sc)

  def exec(phrase: Phrase)(using ValueStack, Scope): Unit =
    for word <- phrase.words do exec(word)

  def call(fdef: FunDef)(using vs: ValueStack, sc: Scope): Unit =
    val funScope = new Scope.NestedScope(sc)
    for param <- fdef.params.reverse do
      funScope.bind(param, vs.pop())
    for param <- fdef.locals do
      funScope.bind(param, Uninit)
    exec(fdef.body)(using vs, funScope)

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
        sc.resolve(sym) match
          case PrimAction(op) => op(vs)

          case fval @ Value.FunVal(fdef, sc2) =>
            if sym.isFunction then
              exec(fdef)(using vs, sc2)
            else
              vs.push(fval)

          case Uninit =>
            err("Accessing uninitialized variable " + sym)

          case value: Value =>
            vs.push(value)

      case ValDef(sym, rhs) =>
        exec(rhs)
        sc.bind(sym, vs.pop())

      case Call(word) =>
        exec(word)
        val Value.FunVal(fdef, sc2) = vs.pop(): @unchecked
        call(fdef)(using vs, sc2)

      case fdef: FunDef =>
        sc.bind(fdef.symbol, Value.FunVal(fdef, sc))

      case FunRef(sym) =>
        val (funVal: Value.FunVal) = sc.resolve(sym): @unchecked
        vs.push(funVal)

/***********************************************************************
 *
 * Main entry point
 *
 ***********************************************************************/
@main
def eval(file: String) = Reporter.monitor(file):
  IO.fileContent(file)    |>
  Parsing.parse           |>
  Namer.transform         |>
  Interpreter.exec

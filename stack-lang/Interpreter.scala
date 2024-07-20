/************************************************************************
 *                                                                      *
 * The implementation of the stack-oriented language in an interpreter. *
 *                                                                      *
 ************************************************************************/

import scala.collection.mutable

import Ast.*
import Symbols.{ predef, runtime }

/***********************************************************************
 *
 * Value Definitions
 *
 ***********************************************************************/
object Interpreter:
  import Denotation.*

  def err(msg: String) = throw new Exception(msg)

  class ValueStack:
    private val stack: mutable.ArrayBuffer[Value] = new mutable.ArrayBuffer

    def pop(): Value =
      if stack.nonEmpty then stack.remove(stack.size - 1)
      else err("Stack is empty")

    def push(v: Value): Unit = stack.append(v)

    def take: List[Value] =
      val res = stack.toList
      stack.clear()
      res

    def size: Int = stack.size

    def show: String = stack.toString

  enum Denotation:
    case IntVal(value: Int)
    case BoolVal(value: Boolean)
    case RecordVal(fields: Map[String, Value])
    case VariantVal(tag: String, values: List[Value])
    case ClosureVal(lambda: Lambda, sc: Scope)
    case FunCall(fun: FunDef, scope: Scope)
    case PrimAction(op: ValueStack => Unit)

  type Value = IntVal | BoolVal | RecordVal | VariantVal | ClosureVal

  //----------------------------------------------------------------------------

  enum Scope:
    case RootScope()
    case NestedScope(outer: Scope)

    private val map: mutable.Map[String, Denotation] = mutable.Map.empty

    def fresh(): Scope = new NestedScope(this)

    def resolve(name: String): Denotation =
      map.get(name) match
        case None =>
          this match
            case NestedScope(outer) => outer.resolve(name)
            case _ => throw new Exception("Not found " + name)

        case Some(res)  => res

    def update(name: String, denot: Denotation): Unit =
      if map.contains(name) then
        map(name) = denot
      else
        this match
          case NestedScope(outer) => outer.update(name, denot)
          case _ => err("Unknown name to update: " + name)

    def bind(name: String, denot: Denotation): Unit =
      assert(!map.contains(name), "Double binding")
      map(name) = denot

  //----------------------------------------------------------------------------

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

  val primitiveOperators: Map[String, ValueStack => Unit] = Map(
      predef.add.name    ->    add,
      predef.sub.name    ->    sub,
      predef.mul.name    ->    mul,
      predef.div.name    ->    div,
      predef.mod.name    ->    mod,
      predef.gt.name     ->    gt,
      predef.lt.name     ->    lt,
      predef.ge.name     ->    ge,
      predef.le.name     ->    le,
      predef.srl.name    ->    srl,
      predef.sll.name    ->    sll,
      predef.land.name   ->    land,
      predef.lor.name    ->    lor,
      predef.lxor.name   ->    lxor,
      predef.band.name   ->    band,
      predef.bor.name    ->    bor,
      predef.bnot.name   ->    bnot,
      predef.eql.name    ->    eql,
      predef.p.name      ->    print,
      runtime.abort.name ->    abort
  )

  //----------------------------------------------------------------------------

  def exec(prog: Prog): Unit =
    val rootScope = new Scope.RootScope()

    for (sym, op) <- primitiveOperators do
      rootScope.bind(sym, PrimAction(op))

    val sc = rootScope.fresh()
    for case fdef: FunDef <- prog.defs do
      sc.bind(fdef.name, FunCall(fdef, sc))

    val vs = new ValueStack
    for case vdef: ValDef <- prog.defs do
      exec(vdef.rhs)(using vs, sc)
      sc.bind(vdef.name, vs.pop())

    exec(prog.main)(using vs, sc)

  def exec(phrase: Phrase)(using vs: ValueStack, sc: Scope): Unit =
    val vs2 = new ValueStack

    def processCall(words: List[Word]): Unit =
      if vs2.size == 1 then
        vs2.pop() match
          case ClosureVal(lam, sc2) =>
            // always call the first function in phrase
            val paramCount = lam.params.size
            assert(words.size >= paramCount, "words = " + words + ", paramCount = " + paramCount)
            for arg <- words.take(paramCount) do exec(arg)(using vs2, sc2)
            call(lam)(using vs2, sc2)
            processCall(words.drop(paramCount))

          case value =>
            vs2.push(value)
            process(words)
      else
        process(words)

    def process(words: List[Word]): Unit =
      words match
        case word :: rest =>
          exec(word)
          processCall(rest)
        case Nil =>
    end process

    process(phrase.words)

    for value <- vs2.take do vs.push(value)

  def call(fdef: FunDef)(using vs: ValueStack, sc: Scope): Unit =
    val funScope = sc.fresh()
    for param <- fdef.params.reverse do
      funScope.bind(param.name, vs.pop())
    exec(fdef.body)(using vs, funScope)

  def call(lam: Lambda)(using vs: ValueStack, sc: Scope): Unit =
    val lamScope = sc.fresh()
    for param <- lam.params.reverse do
      lamScope.bind(param.name, vs.pop())
    exec(lam.body)(using vs, lamScope)

  def exec(value: Value, cases: List[Case])(using vs: ValueStack, sc: Scope): Unit =
    val VariantVal(tag, values) = value: @unchecked
    def matches(caseDef: Case): Boolean =
      caseDef.pat match
        case Wildcard()  => true
        case TagPat(tagId, _) => tagId.name == tag

    val Some(Case(pat, body)) = cases.find(matches): @unchecked

    val caseScope = sc.fresh()
    pat match
      case TagPat(_, bindings) =>
        assert(bindings.size == values.size)
        for (id, value) <- bindings.zip(values) do
          caseScope.bind(id.name, value)

      case Wildcard()  =>

    exec(body)(using vs, caseScope)

  def eval(word: Word)(using vs: ValueStack, sc: Scope): Value =
    exec(word)
    vs.pop()

  def exec(word: Word)(using vs: ValueStack, sc: Scope): Unit =
    word match
      case IntLit(v)  => vs.push(IntVal(v))

      case BoolLit(v) => vs.push(BoolVal(v))

      case RecordLit(args) =>
        val fieldValues = mutable.Map.empty[String, Value]
        for NamedArg(id, arg) <- args do
          fieldValues(id.name) = eval(arg)
        vs.push(RecordVal(fieldValues.toMap))

      case Select(qual, name) =>
        val RecordVal(fieldVals) = eval(qual): @unchecked
        vs.push(fieldVals(name))

      case Variant(tag, words, _) =>
        val values = mutable.ArrayBuffer.empty[Value]
        for word <- words do values += eval(word)
        vs.push(VariantVal(tag.name, values.toList))

      case Ast.Match(scrut, cases) =>
        exec(eval(scrut), cases)

      case Assign(id, rhs) =>
        sc.update(id.name, eval(rhs))

      case If(cond, thenp, elsep) =>
        val BoolVal(b) = eval(cond): @unchecked
        if b then exec(thenp) else exec(elsep)

      case While(cond, body) =>
        val BoolVal(b) = eval(cond): @unchecked
        if b then
          exec(body)
          exec(word)

      case phrase: Phrase =>
        exec(phrase)

      case Ident(name) =>
        sc.resolve(name) match
          case PrimAction(op) => op(vs)
          case FunCall(fdef, sc) => call(fdef)(using vs, sc)
          case value: Value => vs.push(value)

      case vdef: ValDef =>
        sc.bind(vdef.name, eval(vdef.rhs))

      case TypeApply(fun, _) =>
        exec(fun)

      case lam @ Lambda(params, body) =>
        vs.push(ClosureVal(lam, sc))

/***********************************************************************
 *
 * Main entry point
 *
 ***********************************************************************/
@main
def eval(file: String) = Reporter.monitor(file):
  IO.fileContent(file)        |>
  Parsing.parse               |>
  Interpreter.exec

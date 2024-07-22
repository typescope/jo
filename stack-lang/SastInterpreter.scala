import scala.collection.mutable

import Sast.*
import Symbols.*

/** An interpreter for S-AST */
object SastInterpreter:
  import Denotation.*

  def err(msg: String) = throw new Exception(msg)

  enum Denotation:
    case IntVal(value: Int)
    case BoolVal(value: Boolean)
    case RecordVal(fields: Map[String, Value])
    case FunVal(fun: Sast.FunDef, scope: Scope)
    case PrimAction(op: List[Value] => List[Value])
    case Uninit

  type Value = IntVal | BoolVal | RecordVal | FunVal

  enum Scope:
    case RootScope()
    case NestedScope(outer: Scope)

    private val map: mutable.Map[Symbol, Denotation] = mutable.Map.empty

    def fresh(): Scope = new Scope.NestedScope(this)

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
      assert(!map.contains(sym), "Double binding")
      map(sym) = denot
  end Scope

  def int2(op: (Int, Int) => Int)(args: List[Value]): List[Value] =
    val IntVal(a) :: IntVal(b) :: Nil = args: @unchecked
    IntVal(op(a, b)) :: Nil

  def int2bool(op: (Int, Int) => Boolean)(args: List[Value]): List[Value] =
    val IntVal(a) :: IntVal(b) :: Nil = args: @unchecked
    BoolVal(op(a, b)) :: Nil

  def bool2(op: (Boolean, Boolean) => Boolean)(args: List[Value]): List[Value] =
    val BoolVal(a) :: BoolVal(b) :: Nil = args: @unchecked
    BoolVal(op(a, b)) :: Nil

  def bool1(op: Boolean => Boolean)(args: List[Value]): List[Value] =
    val BoolVal(a) :: Nil = args: @unchecked
    BoolVal(op(a)) :: Nil

  def add(args: List[Value]) = int2(_ + _)(args)
  def sub(args: List[Value]) = int2(_ - _)(args)
  def mul(args: List[Value]) = int2(_ * _)(args)
  def div(args: List[Value]) = int2(_ / _)(args)
  def mod(args: List[Value]) = int2(_ % _)(args)

  def lt(args: List[Value]) = int2bool(_ <  _)(args)
  def gt(args: List[Value]) = int2bool(_ >  _)(args)
  def le(args: List[Value]) = int2bool(_ <= _)(args)
  def ge(args: List[Value]) = int2bool(_ >= _)(args)

  def sll (args: List[Value]) = int2(_ << _)(args)
  def srl (args: List[Value]) = int2(_ >> _)(args)
  def land(args: List[Value]) = int2(_ &  _)(args)
  def lor (args: List[Value]) = int2(_ |  _)(args)
  def lxor(args: List[Value]) = int2(_ ^  _)(args)

  def band(args: List[Value]) = bool2(_ && _)(args)
  def bor (args: List[Value]) = bool2(_ || _)(args)
  def bnot(args: List[Value]) = bool1(! _   )(args)

  def eql(args: List[Value]): List[Value] =
    val a :: b :: Nil = args: @unchecked
    BoolVal(a == b) :: Nil

  def print(args: List[Value]): List[Value] =
    val IntVal(v) :: Nil = args: @unchecked
    println(v)
    Nil

  def abort(args: List[Value]): List[Value] =
    val IntVal(v) :: Nil = args: @unchecked
    throw new Exception(v.toString)

  val primitiveOperators: Map[Symbol, List[Value] => List[Value]] = Map(
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

  def exec(prog: Prog): Unit =
    val rootScope = new Scope.RootScope()

    for (sym, op) <- primitiveOperators do
      rootScope.bind(sym, PrimAction(op))

    val sc = rootScope.fresh()
    for fun <- prog.funs do
      sc.bind(fun.symbol, FunVal(fun, sc))

    for ValDef(sym, rhs) <- prog.vals do
      sc.bind(sym, eval(rhs)(using sc))

    exec(prog.main)(using sc)

  def exec(phrase: Phrase)(using Scope): List[Denotation] =
    val results = for word <- phrase.words yield exec(word)

    if results.isEmpty then Nil
    else results.last

  def call(fdef: FunDef, args: List[Value])(using sc: Scope): List[Denotation] =
    val funScope = sc.fresh()
    for (param, arg) <- fdef.params.zip(args) do
      funScope.bind(param, arg)
    for param <- fdef.locals do
      funScope.bind(param, Uninit)
    exec(fdef.body)(using funScope)

  def eval(word: Word)(using sc: Scope): Value =
    val (value: Value) :: Nil = exec(word): @unchecked
    value

  def exec(word: Word)(using sc: Scope): List[Denotation] = Debug.trace(Printing.show(word), enable = false):
    word match
      case IntLit(v)  => IntVal(v) :: Nil

      case BoolLit(v) => BoolVal(v) :: Nil

      case Encoded(repr) => exec(repr)

      case RecordLit(args) =>
        val fieldValues = mutable.Map.empty[String, Value]
        for (name, arg) <- args do fieldValues(name) = eval(arg)
        RecordVal(fieldValues.toMap) :: Nil

      case Select(qual, name) =>
        val RecordVal(fieldVals) = eval(qual): @unchecked
        fieldVals(name) :: Nil

      case Assign(sym, rhs) =>
        sc.update(sym, eval(rhs))
        Nil

      case If(cond, thenp, elsep) =>
        val BoolVal(b) = eval(cond): @unchecked
        if b then exec(thenp) else exec(elsep)

      case While(cond, body) =>
        // avoid stackoverflow
        def loop(): Unit =
          val BoolVal(b) = eval(cond): @unchecked
          if b then
            given Scope = sc.fresh()
            exec(body)
            loop()
        loop()
        Nil

      case phrase: Phrase =>
        exec(phrase)

      case Ident(sym) =>
        sc.resolve(sym) match
          case Uninit =>
            err("Accessing uninitialized variable " + sym)

          case denot =>
            denot :: Nil

      case ValDef(sym, rhs) =>
        sc.bind(sym, eval(rhs))
        Nil

      case Apply(fun, args) =>
        val funDenot :: Nil = exec(fun): @unchecked
        val argVals = args.map(eval)

        (funDenot: @unchecked) match
          case FunVal(fdef, sc) => call(fdef, argVals)(using sc)
          case PrimAction(op) => op(argVals)

      case TypeApply(fun, _) =>
        exec(fun)

      case fdef: FunDef =>
        sc.bind(fdef.symbol, FunVal(fdef, sc))
        Nil

@main
def sastEval(file: String) = Reporter.monitor(file):
  IO.fileContent(file)        |>
  Parsing.parse               |>
  Namer.transform             |+
  Debug.peek(enable = false)  |>
  SastInterpreter.exec

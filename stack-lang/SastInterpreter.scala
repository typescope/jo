import scala.collection.mutable

import sast.*
import sast.Sast.*
import sast.Symbols.*

import common.Debug
import parsing.Parser
import reporting.Reporter
import typing.Namer

/** An interpreter for S-AST */
object SastInterpreter:
  import Denotation.*

  def err(msg: String) = throw new Exception(msg)

  enum Denotation:
    case IntVal(value: Int)
    case BoolVal(value: Boolean)
    case StringVal(value: String)
    case RecordVal(fields: Map[String, Value])
    case FunVal(fun: FunDef, env: Env)
    case PrimAction(op: List[Value] => List[Value])
    case Uninit

  type Value = IntVal | BoolVal | StringVal | RecordVal | FunVal

  enum Env:
    case RootEnv()
    case NestedEnv(outer: Env)

    private val map: mutable.Map[Symbol, Denotation] = mutable.Map.empty

    def fresh(): Env = new Env.NestedEnv(this)

    def resolve(sym: Symbol): Denotation =
      map.get(sym) match
        case None =>
          this match
            case NestedEnv(outer) => outer.resolve(sym)
            case _ => throw new Exception("Not found " + sym)

        case Some(res)  => res

    def update(sym: Symbol, denot: Denotation): Unit =
      if map.contains(sym) then
        map(sym) = denot
      else
        this match
          case NestedEnv(outer) => outer.update(sym, denot)
          case _ => err("Unknown name to update: " + sym.name)

    def bind(sym: Symbol, denot: Denotation): Unit =
      assert(!map.contains(sym), "Double binding " + sym)
      map(sym) = denot

    def contains(sym: Symbol): Boolean = map.contains(sym)
  end Env

  type Params = Map[Symbol, Value]

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

  def p(args: List[Value]): List[Value] =
    val IntVal(v) :: Nil = args: @unchecked
    println(v)
    Nil

  def print(args: List[Value]): List[Value] =
    val StringVal(v) :: Nil = args: @unchecked
    System.out.print(v)
    Nil

  def abort(args: List[Value]): List[Value] =
    val StringVal(v) :: Nil = args: @unchecked
    throw new Exception(v)

  def createRootEnv(): Env =
    val rootEnv = new Env.RootEnv()

    val predef = Predef.instance

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
      predef.p      ->    p,
      predef.print  ->    print,
      predef.abort  ->    abort
    )

    for (sym, op) <- primitiveOperators do
      rootEnv.bind(sym, PrimAction(op))

    rootEnv

  def exec(nss: List[Namespace], main: Symbol): Unit =
    val rootEnv = createRootEnv()

    for
      ns <- nss
      case fun: FunDef <- ns.defs
    do
      // Predef symbols without an implementation should be ignored
      if !rootEnv.contains(fun.symbol) then
        rootEnv.bind(fun.symbol, FunVal(fun, rootEnv))

    val FunVal(fdef, env2) = rootEnv.resolve(main): @unchecked
    val params = Map.empty[Symbol, Value]
    call(fdef, args = Nil)(using env2, params)

  def exec(phrase: Phrase)(using Env, Params): List[Denotation] =
    val results = for word <- phrase.words yield exec(word)

    if results.isEmpty then Nil
    else results.last

  def call(fdef: FunDef, args: List[Value])(using env: Env, params: Params): List[Denotation] =
    val funEnv = env.fresh()
    for (param, arg) <- fdef.params.zip(args) do
      funEnv.bind(param, arg)
    for param <- fdef.locals do
      funEnv.bind(param, Uninit)
    exec(fdef.body)(using funEnv)

  def eval(word: Word)(using env: Env, params: Params): Value =
    val (value: Value) :: Nil = exec(word): @unchecked
    value

  def exec(word: Word)(using env: Env, params: Params): List[Denotation] = Debug.trace(word.show, enable = false):
    word match
      case IntLit(v)  => IntVal(v) :: Nil

      case BoolLit(v) => BoolVal(v) :: Nil

      case StringLit(v) => StringVal(v) :: Nil

      case Encoded(repr) => exec(repr)

      case RecordLit(args) =>
        val fieldValues = mutable.Map.empty[String, Value]
        for (name, arg) <- args do fieldValues(name) = eval(arg)
        RecordVal(fieldValues.toMap) :: Nil

      case Select(qual, name) =>
        val RecordVal(fieldVals) = eval(qual): @unchecked
        fieldVals(name) :: Nil

      case Assign(sym, rhs) =>
        env.update(sym, eval(rhs))
        Nil

      case If(cond, thenp, elsep) =>
        val BoolVal(b) = eval(cond): @unchecked
        if b then exec(thenp) else exec(elsep)

      case With(expr, args) =>
        val params2 = args.foldLeft(params): (params, arg) =>
          params.updated(arg.paramRef.symbol, eval(arg.rhs))
        exec(expr)(using env, params2)

      case While(cond, body) =>
        // avoid stackoverflow
        def loop(): Unit =
          val BoolVal(b) = eval(cond): @unchecked
          if b then
            given Env = env.fresh()
            exec(body)
            loop()
        loop()
        Nil

      case phrase: Phrase =>
        exec(phrase)

      case Ident(sym) =>
        if sym.isAllOf(Flags.Param | Flags.Context) then
          params.get(sym) match
            case Some(v) => v :: Nil
            case None => throw new Exception("Unbound context parameter " + sym)
        else
          env.resolve(sym) match
            case Uninit =>
              err("Accessing uninitialized variable " + sym)

            case denot =>
              denot :: Nil

      case Apply(fun, args) =>
        val funDenot :: Nil = exec(fun): @unchecked
        val argVals = args.map(eval)

        (funDenot: @unchecked) match
          case FunVal(fdef, env) => call(fdef, argVals)(using env)
          case PrimAction(op) => op(argVals)

      case TypeApply(fun, _) =>
        exec(fun)

      case ValDef(sym, rhs) =>
        env.bind(sym, eval(rhs))
        Nil

      case fdef: FunDef =>
        env.bind(fdef.symbol, FunVal(fdef, env))
        Nil

      case tdef: TypeDef =>
        Nil

@main
def sastEval(args: String*) = Reporter.monitor:
    val sourceFiles = args.toList
    val namespacesSAST =
      Parser.parse(sourceFiles)     |>
      Namer.transform               |>
      Printing.peek(enable = false)

    val mains = namespacesSAST.collect:
      case ns if ns.mainSymbol.nonEmpty => ns.mainSymbol.get

    mains match
      case main :: _ =>
        SastInterpreter.exec(namespacesSAST, main)

      case Nil =>
        Reporter.abortInternal("No main function found")

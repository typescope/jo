package sast

import scala.collection.mutable

import ast.Ast

import Sast.*
import Symbols.*

import common.Debug
import parsing.Parser
import reporting.Reporter
import typing.Namer

/** An interpreter for S-AST */
object Interpreter:
  import Denotation.*

  def err(msg: String) = throw new Exception(msg)

  enum Denotation:
    case IntVal(value: Int)
    case BoolVal(value: Boolean)
    case StringVal(value: String)
    case RecordVal(fields: Map[String, Value])
    case FunVal(fun: FunDef, env: Env)

    case ObjectVal(
      values: mutable.Map[String, Value],
      self: Symbol,
      vals: Map[String, Symbol],
      defs: Map[String, FunDef],
      env: Env)

    case ArrayVal(content: Array[Value])

    case PlatformCall(op: List[Value] => List[Value])

    case PlatformObj(call: (String, List[Value]) => List[Value])

    def show(level: Int = 2): String =
      if level == 0 then
        "..."
      else
        this match
        case IntVal(value) => value.toString
        case BoolVal(value) => value.toString
        case StringVal(value) => "\"" + value + "\""
        case RecordVal(fields) => fields.map(_ + " = " + _.show(level - 1)).mkString("{", ", ", "}")
        case ObjectVal(values, self, vals, defs,  env) => values.map(_ + " = " + _.show(level - 1)).mkString("object {", ", ", "}")
        case FunVal(fun, env) => "closue(env = " + env.show(recursive = false) + ")"
        case ArrayVal(content) => "[...]"
        case PlatformCall(op) => "platformCall"
        case PlatformObj(_) => "platformObject"

  type Value = IntVal | BoolVal | StringVal | RecordVal | ObjectVal | FunVal | ArrayVal | PlatformObj

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

    def show(recursive: Boolean): String =
      var bindings = map.map(_.name + " -> " + _.show).toList

      if recursive then
        this match
          case NestedEnv(outer) =>
            bindings = ("outer -> " + outer.show) :: bindings
          case _ =>

      bindings.mkString("{", ", ", "}")
  end Env

  type Params = Map[Symbol, Value]

  def int1(op: Int => Int)(args: List[Value]): List[Value] =
    val IntVal(a) :: Nil = args: @unchecked
    IntVal(op(a)) :: Nil

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

  def byteToChar(args: List[Value]) = int1(n => n)(args)
  def byteToInt(args: List[Value]) = int1(n => n)(args)
  def charToByte(args: List[Value]) = int1(_ & 255)(args)
  def charToInt(args: List[Value]) = int1(n => n)(args)
  def intToByte(args: List[Value]) = int1(_ & 255)(args)
  def intToChar(args: List[Value]) = int1(_ & 65535)(args)

  def charToStr(args: List[Value]): List[Value] =
    val IntVal(v) :: Nil = args: @unchecked
    StringVal(v.toChar.toString()) :: Nil

  def intToStr(args: List[Value]): List[Value] =
    val IntVal(v) :: Nil = args: @unchecked
    StringVal(v.toString()) :: Nil

  def eql(args: List[Value]): List[Value] =
    val a :: b :: Nil = args: @unchecked
    BoolVal(a == b) :: Nil

  def newArray(args: List[Value]): List[Value] =
    val IntVal(size) :: Nil = args: @unchecked
    ArrayVal(new Array[Value](size)) :: Nil

  def abort(args: List[Value]): List[Value] =
    val StringVal(v) :: Nil = args: @unchecked
    throw new Exception(v)

  def createRootEnv(): Env =
    val rootEnv = new Env.RootEnv()

    val defn = Definitions.instance

    val platformCalls: Map[Symbol, List[Value] => List[Value]] = Map(
      defn.Predef_add        ->       add,
      defn.Predef_sub        ->       sub,
      defn.Predef_mul        ->       mul,
      defn.Predef_div        ->       div,
      defn.Predef_mod        ->       mod,
      defn.Predef_gt         ->       gt,
      defn.Predef_lt         ->       lt,
      defn.Predef_ge         ->       ge,
      defn.Predef_le         ->       le,
      defn.Predef_srl        ->       srl,
      defn.Predef_sll        ->       sll,
      defn.Predef_land       ->       land,
      defn.Predef_lor        ->       lor,
      defn.Predef_lxor       ->       lxor,
      defn.Predef_band       ->       band,
      defn.Predef_bor        ->       bor,
      defn.Predef_bnot       ->       bnot,
      defn.Predef_eql        ->       eql,
      defn.Predef_abort      ->       abort,
      defn.Predef_array      ->       newArray,
      defn.Predef_byteToChar ->       byteToChar,
      defn.Predef_byteToInt  ->       byteToInt,
      defn.Predef_charToByte ->       charToByte,
      defn.Predef_charToInt  ->       charToInt,
      defn.Predef_charToStr  ->       charToStr,
      defn.Predef_intToByte  ->       intToByte,
      defn.Predef_intToChar  ->       intToChar,
      defn.Predef_intToStr   ->       intToStr
    )

    for (sym, op) <- platformCalls do
      rootEnv.bind(sym, PlatformCall(op))

    rootEnv

  def createRuntimeContextParams(): Map[Symbol, Value] =
    val defn = Definitions.instance
    Map(
      defn.Predef_open   ->  open(),
      defn.Predef_stdin  ->  stdin(),
      defn.Predef_stdout ->  stdout(),
      defn.Predef_stderr ->  stderr(),
    )

  def stdin() = new PlatformObj((name: String, args: List[Value]) =>
    assert(name == "readLine", name)
    val reader = new java.io.BufferedReader(new java.io.InputStreamReader(System.in))
    val res = reader.readLine()
    reader.close()
    StringVal(res) :: Nil
  )

  def stdout() = new PlatformObj((name: String, args: List[Value]) =>
    assert(name == "write", name)
    val StringVal(content) :: Nil = args: @unchecked
    System.out.print(content)
    Nil
  )

  def stderr() = new PlatformObj((name: String, args: List[Value]) =>
    assert(name == "write", name)
    val StringVal(content) :: Nil = args: @unchecked
    System.err.print(content)
    Nil
  )

  def open() = new PlatformObj((name: String, args: List[Value]) =>
    assert(name == "apply", name)
    val StringVal(file) :: Nil = args: @unchecked
    val jfile = new java.io.RandomAccessFile(file, "rw")
    PlatformObj { (name: String, args: List[Value]) =>
      name match
      case "close" =>
        jfile.close()
        Nil

      case "seek" =>
        val IntVal(offset) :: Nil = args: @unchecked
        jfile.seek(offset)
        Nil

      case "hasMore" =>
        val res = jfile.getFilePointer() < jfile.length()
        BoolVal(res) :: Nil

      case "readLine" =>
        val res = jfile.readLine()
        StringVal(res) :: Nil

      case "write" =>
        val StringVal(content) :: Nil = args: @unchecked
        jfile.write(content.getBytes("utf-8"))
        Nil
    } :: Nil
  )

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

    val params = createRuntimeContextParams()

    call(fdef, args = Nil)(using env2, params)

  def exec(block: Block)(using Env, Params): List[Denotation] =
    val results = for word <- block.words yield exec(word)

    if results.isEmpty then Nil
    else results.last

  def call(fdef: FunDef, args: List[Value])(using env: Env, params: Params): List[Denotation] =
    val funEnv = env.fresh()

    for (param, arg) <- fdef.params.zip(args) do
      funEnv.bind(param, arg)

    exec(fdef.body)(using funEnv)

  def eval(word: Word)(using env: Env, params: Params): Value =
    Debug.trace(word.show + ", env = " + env.show(recursive = false), (_: Value).show(), enable = false):
      val (value: Value) :: Nil = exec(word): @unchecked
      value

  def exec(word: Word)(using env: Env, params: Params): List[Denotation] =
    word match
      case Literal(c)  =>
        c match
          case Constant.Int(n) =>
            IntVal(n) :: Nil

          case Constant.Bool(b) =>
            BoolVal(b) :: Nil

          case Constant.String(s) =>
            StringVal(s) :: Nil

      case Encoded(repr) => exec(repr)

      case RecordLit(args) =>
        val fieldValues = mutable.Map.empty[String, Value]
        for (name, arg) <- args do fieldValues(name) = eval(arg)
        RecordVal(fieldValues.toMap) :: Nil

      case Select(qual, name) =>
        eval(qual): @unchecked match
          case RecordVal(fieldVals) =>
            fieldVals(name) :: Nil

          case objVal: ObjectVal =>
            objVal.values(name) :: Nil

      case Assign(ident, rhs) =>
        env.update(ident.symbol, eval(rhs))
        Nil

      case FieldAssign(qual, name, rhs) =>
        val (objVal: ObjectVal) = eval(qual): @unchecked
        val rhsValue = eval(rhs)
        objVal.values(name) = rhsValue
        Nil

      case If(cond, thenp, elsep) =>
        val BoolVal(b) = eval(cond): @unchecked
        if b then exec(thenp) else exec(elsep)

      case With(expr, args) =>
        val params2 = args.foldLeft(params): (params, arg) =>
          params.updated(arg.paramRef.symbol, eval(arg.rhs))
        exec(expr)(using env, params2)

      case Allow(expr, _) =>
        exec(expr)

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

      case block: Block =>
        exec(block)

      case Ident(sym) =>
        if sym.isAllOf(Flags.Param | Flags.Context) then
          params.get(sym) match
            case Some(v) => v :: Nil
            case None =>
               if sym.is(Flags.Default) then
                 val defaultFun = Ident(sym.defaultFunction)(word.span)
                 val defaultCall = Apply(defaultFun, args = Nil)(word.tpe, word.span)
                 exec(defaultCall)
               else
                 throw new Exception("Unbound context parameter " + sym)

        else
          env.resolve(sym) :: Nil

      case Apply(fun, args) =>
        fun match
          case Select(qual, name) =>
            // invariant: selection must be a method call

            eval(qual): @unchecked match
              case objNative: PlatformObj =>
                objNative.call(name, args.map(eval))

              case objVal: ObjectVal =>
                val argVals = args.map(eval)
                val fdef = objVal.defs(name)
                val env2 = objVal.env.fresh()
                env2.bind(objVal.self, objVal)
                call(fdef, argVals)(using env2)

              case arrayVal: ArrayVal =>
                val argVals = args.map(eval)

                if name == "apply" then
                  val IntVal(index) :: Nil = argVals: @unchecked
                  arrayVal.content(index) :: Nil

                else if name == "set" then
                  val IntVal(index) :: v :: Nil = argVals: @unchecked
                  arrayVal.content(index) = v
                   Nil

                else if name == "size" then
                  assert(argVals.isEmpty)
                  IntVal(arrayVal.content.length) :: Nil

                else
                   throw new Exception(s"Unexpect method $name on array")

              case strVal: StringVal =>
                val argVals = args.map(eval)

                if name == "apply" then
                  val IntVal(index) :: Nil = argVals: @unchecked
                  IntVal(strVal.value(index)) :: Nil

                else if name == "+" then
                  val (other: StringVal) :: Nil = argVals: @unchecked
                  StringVal(strVal.value + other.value) :: Nil

                else if name == "==" then
                  val (other: StringVal) :: Nil = argVals: @unchecked
                  BoolVal(strVal.value == other.value) :: Nil

                else if name == "size" then
                  assert(argVals.isEmpty)
                  IntVal(strVal.value.length) :: Nil

                else if name == "substring" then
                  val IntVal(from) :: IntVal(len) :: Nil = argVals: @unchecked
                  StringVal(strVal.value.substring(from, from + len)) :: Nil

                else
                   throw new Exception(s"Unexpect method $name on array")

          case TypeApply(Select(qual, name), _) =>
            // invariant: selection must be a method call

            eval(qual): @unchecked match
              case objNative: PlatformObj =>
                objNative.call(name, args.map(eval))

              case objVal: ObjectVal =>
                val argVals = args.map(eval)
                val fdef = objVal.defs(name)
                val env2 = objVal.env.fresh()
                env2.bind(objVal.self, objVal)
                call(fdef, argVals)(using env2)

          case _ =>
            val funDenot :: Nil = exec(fun): @unchecked
            val argVals = args.map(eval)

            (funDenot: @unchecked) match
              case FunVal(fdef, env) => call(fdef, argVals)(using env)
              case PlatformCall(op) => op(argVals)

      case TypeApply(fun, _) =>
        exec(fun)

      case ValDef(sym, rhs) =>
        env.bind(sym, eval(rhs))
        Nil

      case fdef: FunDef =>
        env.bind(fdef.symbol, FunVal(fdef, env))
        Nil

      case Object(self, vals, defs) =>
        val fieldInits = vals.map(vdef => vdef.name -> eval(vdef.rhs))
        val fieldVals = mutable.Map.from(fieldInits)
        val valSyms = vals.map(vdef => vdef.name -> vdef.symbol).toMap
        val defTrees = defs.map(mdef => mdef.name -> mdef).toMap
        val objVal = ObjectVal(fieldVals, self, valSyms, defTrees, env)
        objVal :: Nil

      case tdef: TypeDef =>
        Nil

  def main(args: Array[String]): Unit = Reporter.monitor:
    val sourceFiles = args.toList
    val stdlib = "lib/Predef.stk" :: Nil
    val runtime = Nil
    val typeCheck = (nss: List[Ast.Namespace]) => Namer.transform(nss, stdlib, runtime)

    val noramlizer = new phases.NormalizeParams

    val namespacesSAST =
      Parser.parse(sourceFiles)     |>
      typeCheck                     |+
      Printing.peek(enable = false) |>
      TreeChecker.check             |>
      Printing.peek(enable = false) |>
      noramlizer.transform          |>
      TreeChecker.check             |>
      Printing.peek(enable = false)

    val mains = namespacesSAST.collect:
      case ns if ns.mainSymbol.nonEmpty => ns.mainSymbol.get

    mains match
      case main :: _ =>
        exec(namespacesSAST, main)

      case Nil =>
        Reporter.abortInternal("No main function found")

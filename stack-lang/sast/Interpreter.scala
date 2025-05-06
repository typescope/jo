package sast

import scala.collection.mutable

import Sast.*
import Symbols.*

import common.Debug

import phases.FrontEnd
import reporting.Reporter

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
        case FunVal(fun, env) => "closue(env = " + env.show(recursive = false) + ")"
        case ArrayVal(content) => "[...]"
        case PlatformCall(op) => "platformCall"
        case PlatformObj(_) => "platformObject"
        case ObjectVal(values, self, vals, defs,  env) =>
          val fields = values.map(_ + " = " + _.show(level - 1)).mkString(", ")
          val methods = defs.map(_ + ": " + _.symbol.info.show).mkString(", ")
          "object {" + fields + ", " + methods + "}"

  type Value = IntVal | BoolVal | StringVal | RecordVal | ObjectVal | FunVal | ArrayVal | PlatformObj

  enum Env:
    case RootEnv()
    case NestedEnv(outer: Env, owner: Symbol)

    private val map: mutable.Map[Symbol, Denotation] = mutable.Map.empty

    def fresh(owner: Symbol): Env = new Env.NestedEnv(this, owner)

    def findEnv(sym: Symbol): Env =
      this match
        case NestedEnv(outer, owner) =>
          if sym.owner == owner then this
          else outer.findEnv(sym)

        case _: RootEnv =>
          if !sym.isLocal then this
          else throw new Exception("Env not found for " + sym + ", owner = " + sym.owner)

    def resolve(sym: Symbol): Denotation =
      val env = findEnv(sym)
      env.map.get(sym) match
        case None => throw new Exception("Not found " + sym)
        case Some(res)  => res

    def update(sym: Symbol, denot: Denotation): Unit =
      val env = findEnv(sym)
      env.map(sym) = denot

    def bind(sym: Symbol, denot: Denotation): Unit =
      val env = findEnv(sym)
      // Pattern symbol could be bound twice as an optimization in translation
      assert(!env.map.contains(sym) || sym.isPattern, "Double binding " + sym)
      env.map(sym) = denot

    def contains(sym: Symbol): Boolean = map.contains(sym)

    def show(recursive: Boolean): String =
      var bindings = map.map(_.name + " -> " + _.show).toList

      if recursive then
        this match
          case NestedEnv(outer, _) =>
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

  def both(args: List[Value]) = bool2(_ && _)(args)
  def either(args: List[Value]) = bool2(_ || _)(args)
  def not(args: List[Value]) = bool1(! _   )(args)

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

  def createRootEnv()(using defn: Definitions): Env =
    val rootEnv = new Env.RootEnv()

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
      defn.Predef_both       ->       both,
      defn.Predef_either     ->       either,
      defn.Predef_not        ->       not,
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

  def createRuntimeContextParams()(using defn: Definitions): Map[Symbol, Value] =
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

  def index(defs: List[Def])(using env: Env, defn: Definitions): Unit =
    defs.foreach:
      case fun: FunDef =>
        // Predef symbols without an implementation should be ignored
        if !env.contains(fun.symbol) then
          env.bind(fun.symbol, FunVal(fun, env))

      case Section(_, defs) =>
        index(defs)

      case _ =>

  def exec(nss: List[Namespace], main: Symbol)(using Definitions): Unit =
    val rootEnv = createRootEnv()
    for ns <- nss do index(ns.defs)(using rootEnv)

    val FunVal(fdef, env2) = rootEnv.resolve(main): @unchecked

    val params = createRuntimeContextParams()

    call(fdef, args = Nil)(using env2, params)

  def exec(block: Block)(using Env, Params, Definitions): List[Denotation] =
    val results = for word <- block.words yield exec(word)

    if results.isEmpty then Nil
    else results.last

  def call(fdef: FunDef, args: List[Value])(using env: Env, params: Params, defn: Definitions): List[Denotation] =
    val funEnv = env.fresh(fdef.symbol)

    for (param, arg) <- fdef.params.zip(args) do
      funEnv.bind(param, arg)

    Debug.trace("calling " + fdef.symbol + ", env = " + funEnv.show(recursive = false), (ds: List[Denotation]) => ds.map(_.show()).mkString(", "),  enable = false):
      exec(fdef.body)(using funEnv)

  def eval(word: Word)(using env: Env, params: Params, defn: Definitions): Value =
    Debug.trace(word.show + ", env = " + env.show(recursive = false), (_: Value).show(), enable = false):
      val (value: Value) :: Nil = exec(word): @unchecked
      value

  def exec(word: Word)(using env: Env, params: Params, defn: Definitions): List[Denotation] =
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
        if ident.symbol.isMutable then
          env.update(ident.symbol, eval(rhs))
        else
          env.bind(ident.symbol, eval(rhs))

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
                val env2 = objVal.env.fresh(fdef.symbol)
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
                val env2 = objVal.env.fresh(fdef.symbol)
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

      case _: TypeDef | _: PatDef =>
        Nil

      case _: Match | _: TaggedLit =>
        throw new Exception("Unexpected tree: " + word.show)

  def main(args: Array[String]): Unit = Reporter.monitor:
    val sourceFiles = args.toList
    val stdlib = "lib/Predef.stk" :: Nil
    val runtime = Nil

    val rootNameTable = new NameTable
    val runtimeNameTable = new NameTable

    given Reporter.Config = Reporter.Config(fatalWarnings = true)
    given lazyDefn: Definitions.Lazy = new Definitions.Lazy(rootNameTable)
    val namespacesSAST = FrontEnd.run(stdlib, runtime, sourceFiles, runtimeNameTable)

    val mains = namespacesSAST.collect:
      case ns if ns.mainSymbol.nonEmpty => ns.mainSymbol.get

    mains match
      case main :: _ =>
        given Definitions = lazyDefn.value
        exec(namespacesSAST, main)

      case Nil =>
        Reporter.abortInternal("No main function found")

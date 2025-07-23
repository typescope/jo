package sast

import scala.collection.mutable

import Sast.*
import Symbols.*

import common.Debug
import common.IO

import phases.FrontEnd
import reporting.Reporter
import reporting.Config

/** An interpreter for S-AST */
object Interpreter:
  import Denotation.*

  def err(msg: String) = throw new Exception(msg)

  enum Denotation:
    case IntVal(value: Int)
    case BoolVal(value: Boolean)
    case StringVal(value: String)
    case RecordVal(fields: Map[String, Value])
    case FunVal(fun: Symbol, env: Env)

    case ObjectVal(
      values: mutable.Map[String, Value],
      self: Symbol,
      funs: Map[String, Symbol],
      env: Env)

    case ArrayVal(content: Array[Value])

    case PlatformCall(op: List[Value] => List[Value])

    case PlatformObj(call: (String, List[Value]) => List[Value])

    def show(level: Int = 2)(using Definitions): String =
      if level == 0 then
        "..."
      else this match
        case IntVal(value) => value.toString

        case BoolVal(value) => value.toString

        case StringVal(value) => "\"" + value + "\""

        case RecordVal(fields) => fields.map(_ + " = " + _.show(level - 1)).mkString("{", ", ", "}")

        case FunVal(fun, env) => "closue(env = " + env.show(recursive = false) + ")"

        case ArrayVal(content) => "[...]"

        case PlatformCall(op) => "platformCall"

        case PlatformObj(_) => "platformObject"

        case ObjectVal(values, self, defs,  env) =>
          val fields = values.take(1).map(_ + " = " + _.show(level - 1)).mkString(", ")
          val methods = defs.take(5).keys.mkString(", ")
          "object {" + fields + ", " + methods + "}"

  type Value = IntVal | BoolVal | StringVal | RecordVal | ObjectVal | ArrayVal | PlatformObj

  enum Env:
    case RootEnv()
    case NestedEnv(outer: Env)

    private val map: mutable.Map[Symbol, Denotation] = mutable.Map.empty

    def fresh(): Env = new Env.NestedEnv(this)

    def resolve(sym: Symbol)(using Definitions): Denotation =
      resolveRecursive(sym.dealias)

    def root: Env =
      this match
        case _: RootEnv => this
        case NestedEnv(outer) => outer.root

    private def resolveRecursive(sym: Symbol)(using Definitions): Denotation =
      map.get(sym) match
        case Some(res)  => res

        case None =>
          this match
            case NestedEnv(outer) =>
              outer.resolveRecursive(sym)

            case _ =>
              throw new Exception("Not found " + sym + ", sym.info = " + sym.info.show + ", sym.owner = " + sym.owner + ", sym.isAlias = " + sym.isAlias)

    def update(sym: Symbol, denot: Denotation)(using Definitions): Unit =
      // Is only possible to update sym of the current scope
      map(sym) = denot

    def bind(sym: Symbol, denot: Denotation)(using Definitions): Unit =
      // Pattern symbol could be bound twice as an optimization in translation
      assert(!map.contains(sym) || sym.isPattern, "Double binding " + sym)
      map(sym) = denot

    def contains(sym: Symbol): Boolean = map.contains(sym)

    def show(recursive: Boolean)(using Definitions): String =
      var bindings = map.map(_.name + " -> " + _.show()).toList

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

  def createArray(args: List[Value]): List[Value] =
    val IntVal(size) :: Nil = args: @unchecked
    ArrayVal(new Array[Value](size)) :: Nil

  def getArray(args: List[Value]): List[Value] =
    val (arrayVal: ArrayVal) :: IntVal(index) :: Nil = args: @unchecked
    arrayVal.content(index) :: Nil

  def setArray(args: List[Value]): List[Value] =
    val (arrayVal: ArrayVal) :: IntVal(index) :: v :: Nil = args: @unchecked
    arrayVal.content(index) = v
    Nil

  def sizeArray(args: List[Value]): List[Value] =
    val (arrayVal: ArrayVal) :: Nil = args: @unchecked
    IntVal(arrayVal.content.length) :: Nil

  def abort(args: List[Value]): List[Value] =
    val StringVal(v) :: Nil = args: @unchecked
    throw new Exception(v)

  def createRootEnv()(using defn: Definitions): Env =
    val rootEnv = new Env.RootEnv()

    val platformCalls: Map[Symbol, List[Value] => List[Value]] = Map(
      defn.Int_add        ->       add,
      defn.Int_sub        ->       sub,
      defn.Int_mul        ->       mul,
      defn.Int_div        ->       div,
      defn.Int_mod        ->       mod,
      defn.Int_gt         ->       gt,
      defn.Int_lt         ->       lt,
      defn.Int_ge         ->       ge,
      defn.Int_le         ->       le,
      defn.Int_srl        ->       srl,
      defn.Int_sll        ->       sll,
      defn.Int_land       ->       land,
      defn.Int_lor        ->       lor,
      defn.Int_lxor       ->       lxor,
      defn.Int_eql        ->       eql,

      defn.Bool_both       ->       both,
      defn.Bool_either     ->       either,
      defn.Bool_not        ->       not,

      defn.Array_create    ->       createArray,
      defn.Array_get       ->       getArray,
      defn.Array_set       ->       setArray,
      defn.Array_size      ->       sizeArray,

      defn.Predef_abort      ->       abort,
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
      defn.IO_open   ->  open(),
      defn.IO_stdin  ->  stdin(),
      defn.IO_stdout ->  stdout(),
      defn.IO_stderr ->  stderr(),
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

  def index(defs: List[Def])(using defn: Definitions, env: Env): Unit =
    defs.foreach:
      case fun: FunDef =>
        // Predef symbols without an implementation should be ignored
        if !env.contains(fun.symbol) then
          env.bind(fun.symbol, FunVal(fun.symbol, env))

      case Section(_, defs) =>
        index(defs)

      case _ =>

  def exec(nss: List[Namespace], main: Symbol)(using defn: Definitions): Unit =
    given Env = createRootEnv()
    given Params = createRuntimeContextParams()

    for ns <- nss do index(ns.defs)

    val fdef: FunDef = defn.getCode(main)
    call(fdef, args = Nil)

  def exec(block: Block)(using Env, Params, Definitions): List[Denotation] =
    val results = for word <- block.words yield exec(word)

    if results.isEmpty then Nil
    else results.last

  def call(fdef: FunDef, args: List[Value])(using env: Env, params: Params, defn: Definitions): List[Denotation] =
    val funEnv = env.fresh()

    for (param, arg) <- fdef.allParams.zip(args) do
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

      case ValDef(sym, rhs) =>
        // Immutable initialization in a while loop will update old value.
        env.update(sym, eval(rhs))
        Nil

      case Assign(ident, rhs) =>
        env.update(ident.symbol, eval(rhs))
        Nil

      case FieldAssign(Select(qual, name), rhs) =>
        eval(qual): @unchecked match
          case objVal: ObjectVal =>
            val rhsValue = eval(rhs)
            objVal.values(name) = rhsValue
            Nil

      case If(cond, thenp, elsep) =>
        val BoolVal(b) = eval(cond): @unchecked
        if b then exec(thenp) else exec(elsep)

      case With(expr, args) =>
        val params2 = args.foldLeft(params): (params, arg) =>
          params.updated(arg.symbol.dealias, eval(arg.rhs))
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
          params.get(sym.dealias) match
            case Some(v) => v :: Nil
            case None =>
               if sym.is(Flags.Default) then
                 val defaultFun = Ident(sym.defaultFunction)(word.span)
                 val defaultCall = Apply(defaultFun, args = Nil)(word.tpe)
                 exec(defaultCall)
               else
                 throw new Exception("Unbound context parameter " + sym)

        else
          env.resolve(sym) :: Nil

      case Apply(fun, args, autos) =>
        fun match
          case Select(qual, name) =>
            // invariant: selection must be a method call

            eval(qual): @unchecked match
              case objNative: PlatformObj =>
                assert(autos.isEmpty, "autos non empty")
                objNative.call(name, args.map(eval))

              case objVal: ObjectVal =>
                val argVals = args.map(eval) ++ autos.map(eval)
                val sym = objVal.funs(name)
                val env2 = objVal.env.fresh()
                val fdef = defn.getCode(sym).asInstanceOf[FunDef]

                env2.bind(objVal.self, objVal)
                call(fdef, argVals)(using env2)

              case strVal: StringVal =>
                assert(autos.isEmpty, "autos non empty")
                val argVals = args.map(eval)

                if name == "get" then
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
                assert(autos.isEmpty, "autos non empty")
                objNative.call(name, args.map(eval))

              case objVal: ObjectVal =>
                val argVals = args.map(eval) ++ autos.map(eval)
                val sym = objVal.funs(name)
                val env2 = objVal.env.fresh()
                val fdef = defn.getCode(sym)

                env2.bind(objVal.self, objVal)
                call(fdef, argVals)(using env2)


          case _ =>
            val funDenot :: Nil = exec(fun): @unchecked
            val argVals = args.map(eval) ++ autos.map(eval)

            (funDenot: @unchecked) match
              case FunVal(sym, env) =>
                val fdef = defn.getCode(sym)
                call(fdef, argVals)(using env)

              case PlatformCall(op) =>
                op(argVals)

      case TypeApply(fun, _) =>
        exec(fun)

      case fdef: FunDef =>
        val sym = fdef.symbol
        env.bind(sym, FunVal(sym, env))
        Nil

      case Object(self, vals, defs) =>
        val fieldInits = vals.map(vdef => vdef.name -> eval(vdef.rhs))
        val fieldVals = mutable.Map.from(fieldInits)
        val defSymbols = defs.map(mdef => mdef.name -> mdef.symbol).toMap

        val objVal = ObjectVal(fieldVals, self, defSymbols, env)
        objVal :: Nil

      case New(classRef, _) =>
        val classSym = classRef.symbol
        val classInfo = classSym.classInfo

        val fields = mutable.Map.empty[String, Value]
        val methods = classInfo.allMethods.map(sym => sym.name -> sym).toMap
        val objVal = ObjectVal(fields, classInfo.self, methods, env.root)
        objVal :: Nil

      case _: TypeDef | _: PatDef =>
        Nil

      case _: Match | _: TaggedLit =>
        throw new Exception("Unexpected tree: " + word.show)

  def main(args: Array[String]): Unit =
    val (options, sources) = IO.parseOptions(args, Config.commonOptionsSpec)
    given Config = Config(options)

    val runtime = Nil
    val rootNameTable = new NameTable

    Reporter.monitor:
      given lazyDefn: Definitions.Lazy = new Definitions.Lazy(rootNameTable)
      val namespacesSAST = FrontEnd.run(runtime, sources) <| "frontend"

      val mains = namespacesSAST.collect:
        case ns if ns.mainSymbol.nonEmpty => ns.mainSymbol.get

      mains match
        case main :: _ =>
          given Definitions = lazyDefn.value
          exec(namespacesSAST, main) <| "interpreter"

        case Nil =>
          Reporter.abortInternal("No main function found")

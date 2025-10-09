package sast

import scala.collection.mutable

import Trees.*
import Symbols.*

import common.Debug

import phases.FrontEnd
import reporting.Reporter
import reporting.Config

/** An interpreter for S-AST */
object Interpreter:

  //----------------------------------------------------------------------------
  // Default link mappings for Interpreter runtime
  val defaultLinkMappings = Map(
    "stk.Predef.abort"      -> "stk.runtime.Interpreter.abort",
    "stk.Predef.byteToChar" -> "stk.runtime.Interpreter.byteToChar",
    "stk.Predef.byteToInt"  -> "stk.runtime.Interpreter.byteToInt",
    "stk.Predef.charToByte" -> "stk.runtime.Interpreter.charToByte",
    "stk.Predef.charToInt"  -> "stk.runtime.Interpreter.charToInt",
    "stk.Predef.charToStr"  -> "stk.runtime.Interpreter.charToStr",
    "stk.Predef.intToByte"  -> "stk.runtime.Interpreter.intToByte",
    "stk.Predef.intToChar"  -> "stk.runtime.Interpreter.intToChar",
    "stk.Predef.intToStr"   -> "stk.runtime.Interpreter.intToStr",
    "stk.Array.create"      -> "stk.runtime.Interpreter.Array.create",
    "stk.Array.get"         -> "stk.runtime.Interpreter.Array.get",
    "stk.Array.set"         -> "stk.runtime.Interpreter.Array.set",
    "stk.Array.size"        -> "stk.runtime.Interpreter.Array.size",

    "stk.Int.+"        -> "stk.runtime.Interpreter.Int.add",
    "stk.Int.-"        -> "stk.runtime.Interpreter.Int.sub",
    "stk.Int.*"        -> "stk.runtime.Interpreter.Int.mul",
    "stk.Int./"        -> "stk.runtime.Interpreter.Int.div",
    "stk.Int.%"        -> "stk.runtime.Interpreter.Int.mod",
    "stk.Int.>"        -> "stk.runtime.Interpreter.Int.gt",
    "stk.Int.<"        -> "stk.runtime.Interpreter.Int.lt",
    "stk.Int.>="       -> "stk.runtime.Interpreter.Int.ge",
    "stk.Int.<="       -> "stk.runtime.Interpreter.Int.le",
    "stk.Int.=="       -> "stk.runtime.Interpreter.Int.eql",
    "stk.Int.>>"       -> "stk.runtime.Interpreter.Int.srl",
    "stk.Int.<<"       -> "stk.runtime.Interpreter.Int.sll",
    "stk.Int.&"        -> "stk.runtime.Interpreter.Int.land",
    "stk.Int.|"        -> "stk.runtime.Interpreter.Int.lor",
    "stk.Int.^"        -> "stk.runtime.Interpreter.Int.lxor",

    "stk.Bool.both"    -> "stk.runtime.Interpreter.Bool.both",
    "stk.Bool.either"  -> "stk.runtime.Interpreter.Bool.either",
    "stk.Bool.!"       -> "stk.runtime.Interpreter.Bool.not",
  )

  //----------------------------------------------------------------------------

  /** Runtime intrinsic functions */
  class Runtime(defn: Definitions):
    val platformCall = defn.resolveTermByPath("stk.runtime.Interpreter.platformCall")
    val platformCall2 = defn.resolveTermByPath("stk.runtime.Interpreter.platformCall2")
    val platformCall3 = defn.resolveTermByPath("stk.runtime.Interpreter.platformCall3")

  //----------------------------------------------------------------------------

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
      resolveRecursive(sym)

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

  //----------------------------------------------------------------------------

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

  val platformCalls: Map[String, List[Value] => List[Value]] = Map(
      "add" -> { (args: List[Value]) => int2(_ + _)(args) },
      "sub" -> { (args: List[Value]) => int2(_ - _)(args) },
      "mul" -> { (args: List[Value]) => int2(_ * _)(args) },
      "div" -> { (args: List[Value]) => int2(_ / _)(args) },
      "mod" -> { (args: List[Value]) => int2(_ % _)(args) },

      "lt"  -> { (args: List[Value]) => int2bool(_ <  _)(args) },
      "gt"  -> { (args: List[Value]) => int2bool(_ >  _)(args) },
      "le"  -> { (args: List[Value]) => int2bool(_ <= _)(args) },
      "ge"  -> { (args: List[Value]) => int2bool(_ >= _)(args) },

      "sll"  -> {  (args: List[Value]) => int2(_ << _)(args) },
      "srl"  -> {  (args: List[Value]) => int2(_ >> _)(args) },
      "land" -> { (args: List[Value]) => int2(_ &  _)(args) },
      "lor"  -> {  (args: List[Value]) => int2(_ |  _)(args) },
      "lxor" -> { (args: List[Value]) => int2(_ ^  _)(args) },

      "both"   -> { (args: List[Value]) => bool2(_ && _)(args) },
      "either" -> { (args: List[Value]) => bool2(_ || _)(args) },
      "not"    -> { (args: List[Value]) => bool1(! _   )(args) },

      "byteToChar" -> { (args: List[Value]) => int1(n => n)(args) },
      "byteToInt"  -> { (args: List[Value]) => int1(n => n)(args) },
      "charToByte" -> { (args: List[Value]) => int1(_ & 255)(args) },
      "charToInt"  -> { (args: List[Value]) => int1(n => n)(args) },
      "intToByte"  -> { (args: List[Value]) => int1(_ & 255)(args) },
      "intToChar"  -> { (args: List[Value]) => int1(_ & 65535)(args) },

      "charToStr" -> { (args: List[Value]) =>
        val IntVal(v) :: Nil = args: @unchecked
        StringVal(v.toChar.toString()) :: Nil
      },

      "intToStr" -> { (args: List[Value]) =>
        val IntVal(v) :: Nil = args: @unchecked
        StringVal(v.toString()) :: Nil
      },

      "eql" -> { (args: List[Value]) =>
        val a :: b :: Nil = args: @unchecked
        BoolVal(a == b) :: Nil
      },

      "createArray" -> { (args: List[Value]) =>
        val IntVal(size) :: Nil = args: @unchecked
        ArrayVal(new Array[Value](size)) :: Nil
      },

      "getArray" -> { (args: List[Value]) =>
        val (arrayVal: ArrayVal) :: IntVal(index) :: Nil = args: @unchecked
        arrayVal.content(index) :: Nil
      },

      "setArray" -> { (args: List[Value]) =>
        val (arrayVal: ArrayVal) :: IntVal(index) :: v :: Nil = args: @unchecked
        arrayVal.content(index) = v
        Nil
      },

      "sizeArray" -> { (args: List[Value]) =>
        val (arrayVal: ArrayVal) :: Nil = args: @unchecked
        IntVal(arrayVal.content.length) :: Nil
      },

      "abort" -> { (args: List[Value]) =>
        val StringVal(v) :: Nil = args: @unchecked
        throw new Exception(v)
      })


  def platformCall(args: List[Value]): List[Value] =
    val StringVal(name) :: argActual = args: @unchecked
    platformCalls.get(name) match
      case Some(fn) => fn(argActual)
      case None => throw new Exception("Unknown platform call " + name)

  //----------------------------------------------------------------------------
  // default params

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

  //----------------------------------------------------------------------------

  def index(defs: List[Def])(using defn: Definitions, env: Env): Unit =
    defs.foreach:
      case fun: FunDef =>
        env.bind(fun.symbol, FunVal(fun.symbol, env))

      case Section(_, defs) =>
        index(defs)

      case _ =>

  def exec(nss: List[Namespace], main: Symbol)(using defn: Definitions, runtime: Runtime): Unit =
    given Env = new Env.RootEnv()
    given Params = createRuntimeContextParams()

    for ns <- nss do index(ns.defs)

    val fdef: FunDef = defn.getCode(main)
    call(fdef, args = Nil)

  def exec(block: Block)(using Env, Params, Definitions, Runtime): List[Denotation] =
    val results = for word <- block.words yield exec(word)

    if results.isEmpty then Nil
    else results.last

  def call(fdef: FunDef, args: List[Value])(using env: Env, params: Params, defn: Definitions, runtime: Runtime): List[Denotation] =
    val funEnv = env.fresh()

    for (param, arg) <- fdef.allParams.zip(args) do
      funEnv.bind(param, arg)

    Debug.trace("calling " + fdef.symbol + ", env = " + funEnv.show(recursive = false), (ds: List[Denotation]) => ds.map(_.show()).mkString(", "),  enable = false):
      exec(fdef.body)(using funEnv)

  def eval(word: Word)(using env: Env, params: Params, defn: Definitions, runtime: Runtime): Value =
    Debug.trace(word.show + ", env = " + env.show(recursive = false), (_: Value).show(), enable = false):
      val (value: Value) :: Nil = exec(word): @unchecked
      value

  def exec(word: Word)(using env: Env, params: Params, defn: Definitions, runtime: Runtime): List[Denotation] =
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
          params.updated(arg.symbol, eval(arg.rhs))
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
        if sym.is(Flags.Context) then
          params.get(sym) match
            case Some(v) => v :: Nil
            case None =>
               if sym.is(Flags.Default) then
                 val defaultFun = Ident(sym.defaultFunction)(word.span)
                 val defaultCall = defaultFun.appliedTo()
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


          case Ident(runtime.platformCall | runtime.platformCall2 | runtime.platformCall3) =>
            assert(autos.isEmpty, "Unexpected autos for platform calls")
            val argVals = args.map(eval)
            platformCall(argVals)

          case _ =>
            val funDenot :: Nil = exec(fun): @unchecked
            val argVals = args.map(eval) ++ autos.map(eval)

            (funDenot: @unchecked) match
              case FunVal(sym, env) =>
                val fdef = defn.getCode(sym)
                call(fdef, argVals)(using env)

      case TypeApply(fun, _) =>
        exec(fun)

      case fdef: FunDef =>
        val sym = fdef.symbol
        env.bind(sym, FunVal(sym, env))
        Nil

      case Object(self, members) =>
        val defSymbols = mutable.Map.empty[String, Symbol]
        val fieldVals = mutable.Map.empty[String, Value]

        members.map:
          case vdef: ValDef =>
            fieldVals(vdef.name) = eval(vdef.rhs)

          case fdef: FunDef =>
            defSymbols(fdef.name) = fdef.symbol

        val objVal = ObjectVal(fieldVals, self, defSymbols.toMap, env)
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

  //----------------------------------------------------------------------------

  def main(args: Array[String]): Unit =
    given Reporter = Reporter.createReporter()

    val (config, sources) = cli.OptionParser.parseConfig(args, Config.appOptions)

    given Config = config

    Reporter.monitor():

      val runtimePaths = Config.InterpreterRuntimePath :: Nil
      val rootNameTable = new NameTable

      given lazyDefn: Definitions.Lazy = Definitions.Lazy(rootNameTable)

      val nss = FrontEnd.run(runtimePaths, sources, defaultLinkMappings) <| "FrontEnd"
      locally:
        given defn: Definitions = lazyDefn.value
        given Runtime = new Runtime(defn)

        // Final rewire for phases after the first run of LinkRewriter
        val rewriter = new phases.LinkRewriter(FrontEnd.rewireMap.value)


        val entry = defn.resolveTermByPath("stk.runtime.Interpreter.start")

        val nssRewired = rewriter.transform(nss)
        exec(nssRewired, entry) <| "interpreter"

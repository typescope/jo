package sast

import scala.collection.mutable

import Trees.*
import Symbols.*
import Types.*

import common.Debug

import phases.FrontEnd
import reporting.Reporter
import reporting.Config

/** An interpreter for S-AST */
object Interpreter:

  //----------------------------------------------------------------------------
  // Default link mappings for Interpreter runtime
  val defaultLinkMappings = Map(
    "jo.Predef.abort"      -> "jo.runtime.Interpreter.abort",
    "jo.Predef.byteToChar" -> "jo.runtime.Interpreter.byteToChar",
    "jo.Predef.byteToInt"  -> "jo.runtime.Interpreter.byteToInt",
    "jo.Predef.charToByte" -> "jo.runtime.Interpreter.charToByte",
    "jo.Predef.charToInt"  -> "jo.runtime.Interpreter.charToInt",
    "jo.Predef.charToStr"  -> "jo.runtime.Interpreter.charToStr",
    "jo.Predef.intToByte"  -> "jo.runtime.Interpreter.intToByte",
    "jo.Predef.intToChar"  -> "jo.runtime.Interpreter.intToChar",
    "jo.Predef.intToStr"   -> "jo.runtime.Interpreter.intToStr",
    "jo.Array.create"      -> "jo.runtime.Interpreter.Array.create",
    "jo.Array.get"         -> "jo.runtime.Interpreter.Array.get",
    "jo.Array.set"         -> "jo.runtime.Interpreter.Array.set",
    "jo.Array.size"        -> "jo.runtime.Interpreter.Array.size",

    "jo.Int.+"        -> "jo.runtime.Interpreter.Int.add",
    "jo.Int.-"        -> "jo.runtime.Interpreter.Int.sub",
    "jo.Int.*"        -> "jo.runtime.Interpreter.Int.mul",
    "jo.Int./"        -> "jo.runtime.Interpreter.Int.div",
    "jo.Int.%"        -> "jo.runtime.Interpreter.Int.mod",
    "jo.Int.>"        -> "jo.runtime.Interpreter.Int.gt",
    "jo.Int.<"        -> "jo.runtime.Interpreter.Int.lt",
    "jo.Int.>="       -> "jo.runtime.Interpreter.Int.ge",
    "jo.Int.<="       -> "jo.runtime.Interpreter.Int.le",
    "jo.Int.=="       -> "jo.runtime.Interpreter.Int.eql",
    "jo.Int.>>"       -> "jo.runtime.Interpreter.Int.srl",
    "jo.Int.<<"       -> "jo.runtime.Interpreter.Int.sll",
    "jo.Int.&"        -> "jo.runtime.Interpreter.Int.land",
    "jo.Int.|"        -> "jo.runtime.Interpreter.Int.lor",
    "jo.Int.^"        -> "jo.runtime.Interpreter.Int.lxor",

    "jo.Bool.both"    -> "jo.runtime.Interpreter.Bool.both",
    "jo.Bool.either"  -> "jo.runtime.Interpreter.Bool.either",
    "jo.Bool.!"       -> "jo.runtime.Interpreter.Bool.not",
  )

  //----------------------------------------------------------------------------

  /** Runtime intrinsic functions */
  class Runtime(defn: Definitions):
    val platformCall0 = defn.resolveTerm("jo.runtime.Interpreter.platformCall0")
    val platformCall1 = defn.resolveTerm("jo.runtime.Interpreter.platformCall1")
    val platformCall2 = defn.resolveTerm("jo.runtime.Interpreter.platformCall2")
    val platformCall3 = defn.resolveTerm("jo.runtime.Interpreter.platformCall3")

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

    case ClosureVal(lambda: Lambda, env: Env)

    case PlatformVal(v: Any)

    def show(level: Int = 2)(using Definitions): String =
      if level == 0 then
        "..."
      else this match
        case IntVal(value) => value.toString

        case BoolVal(value) => value.toString

        case StringVal(value) => "\"" + value + "\""

        case RecordVal(fields) => fields.map(_ + " = " + _.show(level - 1)).mkString("{", ", ", "}")

        case FunVal(fun, env) => "closue(env = " + env.show(recursive = false) + ")"

        case ClosureVal(lambda, env) => "closure(env = " + env.show(recursive = false) + ")"

        case ArrayVal(content) => "[...]"

        case PlatformVal(v) => v.toString

        case ObjectVal(values, self, defs,  env) =>
          val fields = values.take(1).map(_ + " = " + _.show(level - 1)).mkString(", ")
          val methods = defs.take(5).keys.mkString(", ")
          "{" + fields + ", " + methods + "}"

  type Value = IntVal | BoolVal | StringVal | RecordVal | ClosureVal | ObjectVal | ArrayVal | PlatformVal

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
        StringVal(Character.toString(v)) :: Nil
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
      },

      "readLineStdIn" -> { (args: List[Value]) =>
        assert(args.isEmpty, "Expect empty, found = " + args.size)
        val reader = new java.io.BufferedReader(new java.io.InputStreamReader(System.in))
        val res = reader.readLine()
        reader.close()
        StringVal(res) :: Nil
      },

      "writeStdOut" -> { (args: List[Value]) =>
        val StringVal(content) :: Nil = args: @unchecked
        System.out.print(content)
        Nil
      },

      "writeStdErr" -> { (args: List[Value]) =>
        val StringVal(content) :: Nil = args: @unchecked
        System.err.print(content)
        Nil
      },

      "openFile" -> { (args: List[Value]) =>
        val StringVal(file) :: Nil = args: @unchecked
        val jfile = new java.io.RandomAccessFile(file, "rw")
        PlatformVal(jfile) :: Nil
      },

      "closeFile" -> { (args: List[Value]) =>
        val PlatformVal(jfile: java.io.RandomAccessFile) :: Nil = args: @unchecked
        jfile.close()
        Nil
      },

      "seekFile" -> { (args: List[Value]) =>
        val PlatformVal(jfile: java.io.RandomAccessFile) :: IntVal(offset) :: Nil = args: @unchecked
        jfile.seek(offset)
        Nil
      },

      "hasMoreFile" -> { (args: List[Value]) =>
        val PlatformVal(jfile: java.io.RandomAccessFile) :: Nil = args: @unchecked
        val res = jfile.getFilePointer() < jfile.length()
        BoolVal(res) :: Nil
      },

      "readLineFile" -> { (args: List[Value]) =>
        val PlatformVal(jfile: java.io.RandomAccessFile) :: Nil = args: @unchecked
        val res = jfile.readLine()
        StringVal(res) :: Nil
      },

      "writeFile" -> { (args: List[Value]) =>
        val PlatformVal(jfile: java.io.RandomAccessFile) :: StringVal(content) :: Nil = args: @unchecked
        jfile.write(content.getBytes("utf-8"))
        Nil
      },

  )


  def platformCall(args: List[Value]): List[Value] =
    val StringVal(name) :: argActual = args: @unchecked
    platformCalls.get(name) match
      case Some(fn) => fn(argActual)
      case None => throw new Exception("Unknown platform call " + name)

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
    given Params = Map.empty

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

      case FieldAssign(lhs @ Select(qual, name), rhs) =>
        eval(qual): @unchecked match
          case objVal: ObjectVal =>
            lhs.tpe match
              case MemberRef(_, sym) if sym.isAllOf(Flags.View | Flags.Defer) =>
                // synthesize interface view object
                val viewInfo = sym.info.asClassInfo

                // concrete methods are dispatched directly
                val funsDeferred = viewInfo.methods.foldLeft(Map.empty[String, Symbol]): (acc, meth) =>
                  if meth.is(Flags.Defer) then
                    val target = sym.owner.termMember(meth.name)
                    acc.updated(meth.name, target)
                  else
                    acc

                val viewObj = objVal.copy(funs = funsDeferred).asInstanceOf[ObjectVal]
                objVal.values(name) = viewObj
                Nil

              case _ =>
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
            case None => throw new Exception("Unbound context parameter " + sym)

        else
          env.resolve(sym) :: Nil

      case Apply(fun, args, autos) =>
        fun match
          case Select(qual, name) =>
            // invariant: selection must be a method call

            eval(qual): @unchecked match
              case objVal: ObjectVal =>
                val argVals = args.map(eval) ++ autos.map(eval)
                val env2 = objVal.env.fresh()
                val fdef =
                  fun.tpe match
                    case MemberRef(_, sym) if !sym.is(Flags.Defer) && sym.owner.isOneOf(Flags.Class | Flags.Interface) =>
                      val ownerClassInfo = sym.owner.classInfo
                      env2.bind(ownerClassInfo.self, objVal)
                      defn.getCode(sym).asInstanceOf[FunDef]

                    case _ =>
                      env2.bind(objVal.self, objVal)
                      val sym = objVal.funs(name)
                      defn.getCode(sym).asInstanceOf[FunDef]

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

              case ClosureVal(lambda, env) =>
                assert(autos.isEmpty, "Unexpected autos for interface closure")
                assert(args.size == lambda.params.size, "Size mismatch for interface closure")

                val argVals = args.map(eval)

                // Come from interface instantiation via lambdas
                val lambdaEnv = env.fresh()

                for (param, arg) <- lambda.params.zip(argVals) do
                  lambdaEnv.bind(param, arg)

                exec(lambda.body)(using lambdaEnv)

          case TypeApply(ref @ Select(qual, name), _) =>
            // invariant: selection must be a method call

            eval(qual): @unchecked match
              case objVal: ObjectVal =>
                val argVals = args.map(eval) ++ autos.map(eval)
                val env2 = objVal.env.fresh()

                val fdef =
                  ref.tpe match
                    case MemberRef(_, sym) if !sym.is(Flags.Defer) && sym.owner.isOneOf(Flags.Class | Flags.Interface) =>
                      val ownerClassInfo = sym.owner.classInfo
                      env2.bind(ownerClassInfo.self, objVal)
                      defn.getCode(sym).asInstanceOf[FunDef]

                    case _ =>
                      env2.bind(objVal.self, objVal)
                      val sym = objVal.funs(name)
                      defn.getCode(sym).asInstanceOf[FunDef]

                call(fdef, argVals)(using env2)


          case Ident(runtime.platformCall0 | runtime.platformCall1 | runtime.platformCall2 | runtime.platformCall3) =>
            assert(autos.isEmpty, "Unexpected autos for platform calls")
            val argVals = args.map(eval)
            platformCall(argVals)


          case TypeApply(Ident(sym), tpt :: Nil) if sym == defn.Internal_typeTest =>
            val classInfo = tpt.tpe.asClassInfo
            assert(args.size == 1, "Unexpect args = " + args.size)
            val value = eval(args.head)

            value match
              case _: StringVal => BoolVal(classInfo.classSymbol == defn.Predef_String) :: Nil

              case objVal: ObjectVal => BoolVal(classInfo.classSymbol == objVal.self.owner) :: Nil

              case _ => throw new Exception("Unxpected value in type test: " + value.show)

          case _ =>
            val funDenot :: Nil = exec(fun): @unchecked
            val argVals = args.map(eval) ++ autos.map(eval)

            (funDenot: @unchecked) match
              case FunVal(sym, env) =>
                val fdef = defn.getCode(sym)
                call(fdef, argVals)(using env)

              case ClosureVal(lambda, env) =>
                val lambdaEnv = env.fresh()

                for (param, arg) <- lambda.params.zip(argVals) do
                  lambdaEnv.bind(param, arg)

                exec(lambda.body)(using lambdaEnv)

      case TypeApply(fun, _) =>
        exec(fun)

      case fdef: FunDef =>
        val sym = fdef.symbol
        env.bind(sym, FunVal(sym, env))
        Nil

      case lam: Lambda =>
        ClosureVal(lam, env) :: Nil

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

      case New(tpt) =>
        val classInfo = tpt.tpe.asClassInfo

        // All class methods are direct dispatch
        val fields = mutable.Map.empty[String, Value]
        val objVal = ObjectVal(fields, classInfo.self, funs = Map.empty, env = env.root)
        objVal :: Nil

      case _: TypeDef | _: PatDef =>
        Nil

      case _: Match | _: IsExpr | _: CaseDef =>
        throw new Exception("Unexpected tree: " + word.show)

  //----------------------------------------------------------------------------

  def main(args: Array[String]): Unit =
    given Reporter = Reporter.createReporter()

    val (config, sources) = cli.OptionParser.parseConfig(args, Config.appOptions)

    given Config = config

    Reporter.monitor():

      val runtimePaths = Config.InterpreterRuntimePath :: Config.runtimePaths.value
      val rootNameTable = new NameTable

      given lazyDefn: Definitions.Lazy = Definitions.Lazy(rootNameTable)

      val nss = FrontEnd.run(runtimePaths, sources, defaultLinkMappings) <| "FrontEnd"
      locally:
        given defn: Definitions = lazyDefn.value
        given Runtime = new Runtime(defn)

        // Final rewire for phases after the first run of LinkRewriter
        val rewriter = new phases.LinkRewriter(FrontEnd.rewireMap.value)


        val entry = defn.resolveTerm("jo.runtime.Interpreter.start")

        val nssRewired = rewriter.transform(nss)
        exec(nssRewired, entry) <| "interpreter"

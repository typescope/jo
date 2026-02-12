package sast

import scala.collection.mutable

import Trees.*
import Symbols.*
import Types.*

import common.Debug

import phases.FrontEnd
import reporting.Reporter
import reporting.Config

import java.nio.charset.StandardCharsets

/** An interpreter for S-AST */
object Interpreter:

  //----------------------------------------------------------------------------
  // Default link mappings for Interpreter runtime
  val defaultLinkMappings = Map(
    "jo.abort"      -> "run.abort",

    // Typed array operations (all use the same implementation in interpreter)
    "jo.Array.IntArray.create" -> "run.IntArray.create",
    "jo.Array.IntArray.get"    -> "run.IntArray.get",
    "jo.Array.IntArray.set"    -> "run.IntArray.set",
    "jo.Array.IntArray.size"   -> "run.IntArray.size",

    "jo.Array.FloatArray.create" -> "run.FloatArray.create",
    "jo.Array.FloatArray.get"    -> "run.FloatArray.get",
    "jo.Array.FloatArray.set"    -> "run.FloatArray.set",
    "jo.Array.FloatArray.size"   -> "run.FloatArray.size",

    "jo.Array.ByteArray.create" -> "run.ByteArray.create",
    "jo.Array.ByteArray.get"    -> "run.ByteArray.get",
    "jo.Array.ByteArray.set"    -> "run.ByteArray.set",
    "jo.Array.ByteArray.size"   -> "run.ByteArray.size",

    "jo.Array.ObjectArray.create" -> "run.ObjectArray.create",
    "jo.Array.ObjectArray.get"    -> "run.ObjectArray.get",
    "jo.Array.ObjectArray.set"    -> "run.ObjectArray.set",
    "jo.Array.ObjectArray.size"   -> "run.ObjectArray.size",
  )

  //----------------------------------------------------------------------------

  /** Runtime intrinsic functions */
  class Runtime(defn: Definitions):
    val platformCall0 = defn.resolveTerm("run.platformCall0")
    val platformCall1 = defn.resolveTerm("run.platformCall1")
    val platformCall2 = defn.resolveTerm("run.platformCall2")
    val platformCall3 = defn.resolveTerm("run.platformCall3")

  //----------------------------------------------------------------------------

  import Denotation.*

  def err(msg: String) = throw new Exception(msg)

  enum Denotation:
    case IntVal(value: Int)
    case FloatVal(value: Double)
    case BoolVal(value: Boolean)
    case StringVal(value: String)
    case RecordVal(fields: Map[String, Value])
    case FunVal(fun: Symbol, env: Env)

    case ObjectVal(
      values: mutable.Map[String, Value],
      self: Symbol,
      funs: Map[String, Symbol],
      env: Env)

    case ArrayVal(content: Array[?])

    case ClosureVal(lambda: Lambda, env: Env)

    case PlatformVal(v: Any)

    def show(level: Int = 2)(using Definitions): String =
      if level == 0 then
        "..."
      else this match
        case IntVal(value) => value.toString

        case FloatVal(value) => value.toString

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

  type Value = IntVal | FloatVal | BoolVal | StringVal | RecordVal | ClosureVal | ObjectVal | ArrayVal | PlatformVal

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

    def update(sym: Symbol, denot: Denotation): Unit =
      // Is only possible to update sym of the current scope
      map(sym) = denot

    def bind(sym: Symbol, denot: Denotation): Unit =
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

  val platformCalls: Map[String, List[Value] => List[Value]] = Map(
      "createIntArray" -> { (args: List[Value]) =>
        val IntVal(size) :: Nil = args: @unchecked
        ArrayVal(new Array[Int](size)) :: Nil
      },

      "getIntArray" -> { (args: List[Value]) =>
        val (arrayVal: ArrayVal) :: IntVal(index) :: Nil = args: @unchecked
        IntVal(arrayVal.content(index).asInstanceOf[Int]) :: Nil
      },

      "setIntArray" -> { (args: List[Value]) =>
        val (arrayVal: ArrayVal) :: IntVal(index) :: IntVal(v) :: Nil = args: @unchecked
        arrayVal.content.asInstanceOf[Array[Int]](index) = v
        Nil
      },

      "createFloatArray" -> { (args: List[Value]) =>
        val IntVal(size) :: Nil = args: @unchecked
        ArrayVal(new Array[Double](size)) :: Nil
      },

      "getFloatArray" -> { (args: List[Value]) =>
        val (arrayVal: ArrayVal) :: IntVal(index) :: Nil = args: @unchecked
        FloatVal(arrayVal.content(index).asInstanceOf[Double]) :: Nil
      },

      "setFloatArray" -> { (args: List[Value]) =>
        val (arrayVal: ArrayVal) :: IntVal(index) :: FloatVal(v) :: Nil = args: @unchecked
        arrayVal.content.asInstanceOf[Array[Double]](index) = v
        Nil
      },

      "createObjectArray" -> { (args: List[Value]) =>
        val IntVal(size) :: Nil = args: @unchecked
        ArrayVal(new Array[Value](size)) :: Nil
      },

      "getObjectArray" -> { (args: List[Value]) =>
        val (arrayVal: ArrayVal) :: IntVal(index) :: Nil = args: @unchecked
        arrayVal.content(index).asInstanceOf[Value] :: Nil
      },

      "setObjectArray" -> { (args: List[Value]) =>
        val (arrayVal: ArrayVal) :: IntVal(index) :: v :: Nil = args: @unchecked
        arrayVal.content.asInstanceOf[Array[Value]](index) = v
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
        jfile.write(content.getBytes(StandardCharsets.UTF_8))
        Nil
      },

  )


  def platformCall(args: List[Value]): List[Value] =
    val StringVal(name) :: argActual = args: @unchecked
    platformCalls.get(name) match
      case Some(fn) => fn(argActual)
      case None => throw new Exception("Unknown platform call " + name)

  //----------------------------------------------------------------------------

  def index(defs: List[Def])(using defn: Definitions, env: Env, params: Params, runtime: Runtime): Unit =
    defs.foreach:
      case fun: FunDef =>
        val sym = fun.symbol
        if sym.is(Flags.Object) then
          // instantiate object
          val classTpt = fun.resultType
          val expr = New(classTpt)(fun.span).select(Names.Constructor).appliedTo()
          val value = eval(expr)

          env.bind(sym, value)
        else
          env.bind(sym, FunVal(fun.symbol, env))

      case Section(_, defs) =>
        index(defs)

      case _ =>

  def exec(units: List[FileUnit], main: Symbol, args: List[String])(using defn: Definitions, runtime: Runtime): Unit =
    given Env = new Env.RootEnv()
    given Params = Map.empty

    for unit <- units do index(unit.defs)

    val fdef: FunDef = defn.getCode(main)

    val argList = new ArrayVal(args.map(StringVal.apply).toArray)

    call(fdef, args = argList :: Nil)

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

          case Constant.Float(d) =>
            FloatVal(d) :: Nil

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

      case ClassTest(arg, cls) =>
        val value = eval(arg)

        value match
          case _: StringVal => BoolVal(cls == defn.PlatformString_type) :: Nil

          case _: FloatVal => BoolVal(cls == defn.Float_type) :: Nil

          case _: BoolVal => BoolVal(cls == defn.Bool_type) :: Nil

          case _: IntVal =>
            // No two numeric types can appear in union types
            val isMatch =
              cls == defn.Int_type
              || cls == defn.Char_type
              || cls == defn.Byte_type

            BoolVal(isMatch) :: Nil

          case objVal: ObjectVal => BoolVal(cls == objVal.self.owner) :: Nil

          case _ => throw new Exception("Unxpected value in type test: " + value.show)

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
                    case MemberRef(_, sym) if sym.owner.isOneOf(Flags.Class | Flags.Interface) =>
                      val target =
                        if sym.is(Flags.Defer) then objVal.funs(sym.name)
                        else sym

                      val ownerClassInfo = target.owner.classInfo
                      env2.bind(ownerClassInfo.self, objVal)
                      defn.getCode(target).asInstanceOf[FunDef]

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
                  val str = strVal.value
                  val cpOffset = str.offsetByCodePoints(0, index)
                  IntVal(str.codePointAt(cpOffset)) :: Nil

                else if name == "+" then
                  val (other: StringVal) :: Nil = argVals: @unchecked
                  StringVal(strVal.value + other.value) :: Nil

                else if name == "==" then
                  val (other: StringVal) :: Nil = argVals: @unchecked
                  BoolVal(strVal.value == other.value) :: Nil

                else if name == "size" then
                  assert(argVals.isEmpty)
                  IntVal(strVal.value.codePointCount(0, strVal.value.length)) :: Nil

                else if name == "substring" then
                  val IntVal(from) :: IntVal(len) :: Nil = argVals: @unchecked
                  val str = strVal.value
                  val startOffset = str.offsetByCodePoints(0, from)
                  val endOffset = str.offsetByCodePoints(startOffset, len)
                  StringVal(str.substring(startOffset, endOffset)) :: Nil

                else if name == "indexOfFrom" then
                  val (other: StringVal) :: IntVal(from) :: Nil = argVals: @unchecked
                  val str = strVal.value
                  val target = other.value
                  val startCp =
                    if from < 0 then 0
                    else
                      val n = str.codePointCount(0, str.length)
                      if from > n then n else from
                  val startOffset = str.offsetByCodePoints(0, startCp)
                  val foundOffset = str.indexOf(target, startOffset)
                  if foundOffset < 0 then IntVal(-1) :: Nil
                  else IntVal(str.codePointCount(0, foundOffset)) :: Nil

                else if name == "toLower" then
                  assert(argVals.isEmpty)
                  StringVal(strVal.value.toLowerCase) :: Nil

                else if name == "toUpper" then
                  assert(argVals.isEmpty)
                  StringVal(strVal.value.toUpperCase) :: Nil

                else
                  val env = new Env.RootEnv
                  val stringClassInfo = defn.PlatformString_type.info.asClassInfo
                  env.bind(stringClassInfo.self, strVal)
                  val sym = stringClassInfo.memberSymbol(name)
                  val fdef = defn.getCode(sym).asInstanceOf[FunDef]
                  call(fdef, argVals)(using env)

              case floatVal: FloatVal =>
                assert(autos.isEmpty, "autos non empty")
                val argVals = args.map(eval)

                if name == "+" then
                  val FloatVal(other) :: Nil = argVals: @unchecked
                  FloatVal(floatVal.value + other) :: Nil

                else if name == "-" then
                  val FloatVal(other) :: Nil = argVals: @unchecked
                  FloatVal(floatVal.value - other) :: Nil

                else if name == "*" then
                  val FloatVal(other) :: Nil = argVals: @unchecked
                  FloatVal(floatVal.value * other) :: Nil

                else if name == "/" then
                  val FloatVal(other) :: Nil = argVals: @unchecked
                  FloatVal(floatVal.value / other) :: Nil

                else if name == ">" then
                  val FloatVal(other) :: Nil = argVals: @unchecked
                  BoolVal(floatVal.value > other) :: Nil

                else if name == "<" then
                  val FloatVal(other) :: Nil = argVals: @unchecked
                  BoolVal(floatVal.value < other) :: Nil

                else if name == ">=" then
                  val FloatVal(other) :: Nil = argVals: @unchecked
                  BoolVal(floatVal.value >= other) :: Nil

                else if name == "<=" then
                  val FloatVal(other) :: Nil = argVals: @unchecked
                  BoolVal(floatVal.value <= other) :: Nil

                else if name == "==" then
                  val FloatVal(other) :: Nil = argVals: @unchecked
                  BoolVal(floatVal.value == other) :: Nil

                else if name == "!=" then
                  val FloatVal(other) :: Nil = argVals: @unchecked
                  BoolVal(floatVal.value != other) :: Nil

                else if name == "toInt" then
                  assert(argVals.isEmpty)
                  IntVal(floatVal.value.toInt) :: Nil

                else if name == "~-" then
                  assert(argVals.isEmpty)
                  FloatVal(-floatVal.value) :: Nil

                else if name == "toString" then
                  assert(argVals.isEmpty)
                  StringVal(floatVal.value.toString) :: Nil

                else
                   throw new Exception(s"Unexpect method $name on float")

              case boolVal: BoolVal =>
                assert(autos.isEmpty, "autos non empty")

                if name == "&&" then
                  if !boolVal.value then BoolVal(false) :: Nil
                  else eval(args.head) :: Nil

                else if name == "||" then
                  if boolVal.value then BoolVal(true) :: Nil
                  else eval(args.head) :: Nil

                else if name == "==" then
                  val BoolVal(other) :: Nil = args.map(eval): @unchecked
                  BoolVal(boolVal.value == other) :: Nil

                else if name == "!=" then
                  val BoolVal(other) :: Nil = args.map(eval): @unchecked
                  BoolVal(boolVal.value != other) :: Nil

                else if name == "~!" then
                  BoolVal(!boolVal.value) :: Nil

                else if name == "toString" then
                  StringVal(boolVal.value.toString) :: Nil

                else
                  throw new Exception(s"Unexpected method $name on bool")

              case intVal: IntVal =>
                assert(autos.isEmpty, "autos non empty")
                val argVals = args.map(eval)

                if name == "+" then
                  val IntVal(other) :: Nil = argVals: @unchecked
                  IntVal(intVal.value + other) :: Nil

                else if name == "-" then
                  val IntVal(other) :: Nil = argVals: @unchecked
                  IntVal(intVal.value - other) :: Nil

                else if name == "*" then
                  val IntVal(other) :: Nil = argVals: @unchecked
                  IntVal(intVal.value * other) :: Nil

                else if name == "/" then
                  val IntVal(other) :: Nil = argVals: @unchecked
                  IntVal(intVal.value / other) :: Nil

                else if name == "%" then
                  val IntVal(other) :: Nil = argVals: @unchecked
                  IntVal(intVal.value % other) :: Nil

                else if name == ">" then
                  val IntVal(other) :: Nil = argVals: @unchecked
                  BoolVal(intVal.value > other) :: Nil

                else if name == "<" then
                  val IntVal(other) :: Nil = argVals: @unchecked
                  BoolVal(intVal.value < other) :: Nil

                else if name == ">=" then
                  val IntVal(other) :: Nil = argVals: @unchecked
                  BoolVal(intVal.value >= other) :: Nil

                else if name == "<=" then
                  val IntVal(other) :: Nil = argVals: @unchecked
                  BoolVal(intVal.value <= other) :: Nil

                else if name == "==" then
                  val IntVal(other) :: Nil = argVals: @unchecked
                  BoolVal(intVal.value == other) :: Nil

                else if name == "!=" then
                  val IntVal(other) :: Nil = argVals: @unchecked
                  BoolVal(intVal.value != other) :: Nil

                else if name == ">>" then
                  val IntVal(other) :: Nil = argVals: @unchecked
                  IntVal(intVal.value >> other) :: Nil

                else if name == "<<" then
                  val IntVal(other) :: Nil = argVals: @unchecked
                  IntVal(intVal.value << other) :: Nil

                else if name == "&" then
                  val IntVal(other) :: Nil = argVals: @unchecked
                  IntVal(intVal.value & other) :: Nil

                else if name == "|" then
                  val IntVal(other) :: Nil = argVals: @unchecked
                  IntVal(intVal.value | other) :: Nil

                else if name == "^" then
                  val IntVal(other) :: Nil = argVals: @unchecked
                  IntVal(intVal.value ^ other) :: Nil

                else if name == "toChar" then
                  assert(argVals.isEmpty)
                  IntVal(intVal.value) :: Nil

                else if name == "toByte" then
                  assert(argVals.isEmpty)
                  IntVal(intVal.value & 255) :: Nil

                else if name == "toFloat" then
                  assert(argVals.isEmpty)
                  FloatVal(intVal.value.toDouble) :: Nil

                else if name == "~-" then
                  assert(argVals.isEmpty)
                  IntVal(-intVal.value) :: Nil

                else if name == "toString" then
                  assert(argVals.isEmpty)
                  if qual.tpe.isSubtype(defn.CharType) then
                    StringVal(Character.toString(intVal.value)) :: Nil
                  else
                    StringVal(intVal.value.toString) :: Nil

                else if name == "toInt" then
                  // From Byte/Char
                  assert(argVals.isEmpty)
                  intVal :: Nil

                else
                   throw new Exception(s"Unexpect method $name on int")

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

          case _ =>
            val funDenot :: Nil = exec(fun): @unchecked
            val argVals = args.map(eval) ++ autos.map(eval)

            (funDenot: @unchecked) match
              case FunVal(sym, env) =>
                if sym == defn.jo_pass then
                  // Use 0 as Unit
                  IntVal(0) :: Nil

                else
                  val fdef = defn.getCode(sym)
                  call(fdef, argVals)(using env)

              case ClosureVal(lambda, env) =>
                val lambdaEnv = env.fresh()

                for (param, arg) <- lambda.params.zip(argVals) do
                  lambdaEnv.bind(param, arg)

                exec(lambda.body)(using lambdaEnv)

              case objVal: ObjectVal =>
                fun match
                  case Ident(sym) => assert(sym.is(Flags.Object), "Expect object accessor, found = " + fun.show)
                  case _ => throw new Exception("Expect object accessor, found = " + fun.show)

                objVal :: Nil

      case TypeApply(fun, _) =>
        exec(fun)

      case fdef: FunDef =>
        val sym = fdef.symbol
        env.bind(sym, FunVal(sym, env))
        Nil

      case lam: Lambda =>
        ClosureVal(lam, env) :: Nil

      case New(tpt) =>
        val classInfo = tpt.tpe.asClassInfo

        // All class methods are direct dispatch except when used as interfaces
        val fields = mutable.Map.empty[String, Value]
        val funs = classInfo.methods.map(f => f.name -> f).toMap
        val objVal = ObjectVal(fields, classInfo.self, funs, env = env.root)
        objVal :: Nil

      case _: TypeDef | _: PatDef =>
        Nil

      case _: Match | _: IsExpr | _: CaseDef =>
        throw new Exception("Unexpected tree: " + word.show)

  //----------------------------------------------------------------------------

  def main(args: Array[String]): Unit =
    given Reporter = Reporter.createReporter()

    val (config, remains) = cli.OptionParser.parseConfig(args, Config.appOptions)

    val sources = remains.takeWhile(arg => arg.endsWith(".jo"))
    val progArgs = remains.takeRight(remains.size - sources.size)

    if sources.isEmpty then
      Reporter.error("No source code supplied")
      return

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


        val entry = defn.resolveTerm("run.start")

        val nssRewired = rewriter.transform(nss)
        exec(nssRewired, entry, progArgs) <| "interpreter"

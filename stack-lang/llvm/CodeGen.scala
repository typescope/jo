package llvm

import common.WorkList

import sast.*
import sast.Trees.*
import sast.Symbols.*
import sast.Types.*

import scala.collection.mutable

/** Generate textual LLVM IR from the lowered SAST
  *
  * Each Jo function becomes an LLVM function. Values are typed as follows:
  *
  *     Bool        i1
  *     Byte        i8
  *     Char, Int   i32
  *     Long        i64
  *     Float       double
  *     otherwise   ptr      (Unit is always null)
  *
  * Locals and parameters live in `alloca` slots of the entry block. LLVM
  * promotes them to registers.
  */
class CodeGen(runtime: LLVMRuntime)(using defn: Definitions):
  import CodeGen.*

  /** Worklist to add all reachable functions */
  private val workList = new WorkList[Symbol]

  /** LLVM names of Jo functions */
  private val funNames = mutable.Map.empty[Symbol, String]

  /** Byte-string globals of string constants */
  private val stringTable = mutable.LinkedHashMap.empty[String, String]

  /** Declarations of C functions */
  private val cDecls = mutable.LinkedHashMap.empty[String, String]

  /** Interface tables of classes */
  private val itables = mutable.LinkedHashMap.empty[Symbol, String]

  private val functions = new StringBuilder

  private var isLoweringObjectInitProc = false

  private val StateGlobal = "@runtime.state"

  //----------------------------------------------------------------------------
  // Module

  def compile(units: List[FileUnit]): String =
    val symbolDefMap = mutable.Map.empty[Symbol, FunDef]

    for
      unit <- units
      defn <- unit
    do
      defn match
        case fdef: FunDef => symbolDefMap(fdef.symbol) = fdef
        case _ =>

    val startName = funRef(runtime.Core_start)

    workList.run: sym =>
      compileFunDef(symbolDefMap(sym))

    // must come last after traversing the whole universe
    //
    // In lowering objectInitProc, accessor calls should be called literally once.
    isLoweringObjectInitProc = true
    compileFunDef(runtime.getObjectInitProc())
    assert(workList.isEmpty, "Non-empty work list: " + workList.todos())

    val module = new StringBuilder

    for (value, name) <- stringTable do
      module ++= byteStringGlobal(name, value) += '\n'

    for holder <- runtime.objectHolders do
      module ++= s"$holder = internal global ptr null, align 8\n"

    val stateSlots = Layout.classInstanceSize(runtime.Core_State) / Layout.SlotSize
    module ++= s"$StateGlobal = internal global [$stateSlots x i64] zeroinitializer, align 8\n"

    for (cls, name) <- itables do
      module ++= interfaceTableGlobal(cls, name)

    module += '\n'

    for decl <- cDecls.values do
      module ++= decl += '\n'

    module += '\n'
    module ++= functions

    // C entry point
    val startType = runtime.Core_start.tpe.asProcType
    val startRet = retType(startType.resultType)
    module ++= "define i32 @main(i32 %argc, ptr %argv) {\n"
    module ++= s"  call $startRet $startName(i32 %argc, ptr %argv)\n"
    module ++= "  ret i32 0\n"
    module ++= "}\n"

    module.toString

  /** The LLVM name of a function, making the function reachable */
  def funRef(sym: Symbol): String =
    runtime.rewire.get(sym) match
      case Some(target) => funRef(target)

      case None =>
        funNames.get(sym) match
          case Some(name) => name
          case None =>
            val name = globalName(sym.fullName + "." + funNames.size)
            funNames(sym) = name

            // `objectInitProcSym` only has a body at the end and is handled specially.
            if sym != runtime.objectInitProcSym then
              workList.add(sym)

            name

  /** The function symbol after rewiring */
  def resolve(sym: Symbol): Symbol =
    runtime.rewire.get(sym) match
      case Some(target) => resolve(target)
      case None => sym

  def addString(value: String): String =
    stringTable.getOrElseUpdate(value, "@str." + stringTable.size)

  /** A string constant in the format of `StringOps.fromByteString`: `[size: i32][bytes]` */
  def byteStringGlobal(name: String, value: String): String =
    val bytes = value.getBytes("UTF-8")
    val n = bytes.length
    s"$name = private unnamed_addr constant <{ i32, [$n x i8] }> <{ i32 $n, [$n x i8] c\"${escapeBytes(bytes)}\" }>, align 8"

  def interfaceTable(cls: Symbol): String =
    itables.get(cls) match
      case Some(name) => name
      case None =>
        val name = "@itable." + itables.size
        itables(cls) = name

        // Mark all interface methods reachable
        for
          (_, impls) <- runtime.getInterfaceImplementations(cls.classInfo)
          impl <- impls
        do
          funRef(impl)

        name

  /** Interface table: `[count, iid1, vtable1, iid2, vtable2, ...]`, one word each */
  def interfaceTableGlobal(cls: Symbol, name: String): String =
    val buf = new StringBuilder
    val entries = runtime.getInterfaceImplementations(cls.classInfo)
    val fields = new mutable.ArrayBuffer[String]
    fields += s"i64 ${entries.size}"

    for ((interface, impls), i) <- entries.zipWithIndex do
      val vtable = s"$name.vtable.$i"
      val methods = impls.map(impl => "ptr " + funRef(impl))
      buf ++= s"$vtable = private constant [${impls.size} x ptr] [${methods.mkString(", ")}], align 8\n"
      fields += s"i64 ${runtime.getInterfaceId(interface)}"
      fields += s"ptr $vtable"

    val types = fields.map(_.takeWhile(_ != ' '))
    buf ++= s"$name = private constant { ${types.mkString(", ")} } { ${fields.mkString(", ")} }, align 8\n"
    buf.toString

  //----------------------------------------------------------------------------
  // Types

  def llvmType(tp: Type): String =
    if tp.isVoidType then "void"
    else
      tp.approx match
        case StaticRef(sym) =>
          if sym == defn.Bool_type then "i1"
          else if sym == defn.Byte_type then "i8"
          else if sym == defn.Char_type || sym == defn.Int_type then "i32"
          else if sym == defn.Long_type then "i64"
          else if sym == defn.Float_type then "double"
          else "ptr"

        case _ => "ptr"

  /** Return type of a Jo function: never void, Bottom is a pointer */
  def retType(tp: Type): String =
    if tp.isVoidType || tp.isBottomType then "ptr" else llvmType(tp)

  /** Type of a C function parameter or result */
  def cType(tp: Type): String =
    if tp.isBottomType || tp.isVoidType then "void" else llvmType(tp)

  //----------------------------------------------------------------------------
  // Functions

  def compileFunDef(fdef: FunDef): Unit = try
    val sym = fdef.symbol
    val procType = sym.tpe.asProcType
    val name = funRef(sym)

    given ctx: FunContext = new FunContext(sym)

    val paramTypes = procType.allParamTypes.map(llvmType)
    val resType = retType(procType.resultType)

    val paramDecls = paramTypes.zipWithIndex.map((ty, i) => s"$ty %arg.$i")

    for ((param, ty), i) <- fdef.allParams.zip(paramTypes).zipWithIndex do
      val slot = localSlot(param)
      emit(s"store ${coerce(V(ty, s"%arg.$i"), slot.ty).text}, ptr ${slot.name}")

    val res = compile(fdef.body)
    ret(res, resType)

    functions ++= s"define internal $resType $name(${paramDecls.mkString(", ")}) {\n"
    functions ++= "entry:\n"
    functions ++= ctx.allocas
    functions ++= ctx.body
    functions ++= "}\n\n"
  catch
    case e: Throwable =>
      println("Failed to compile " + fdef.symbol.fullName + ": " + fdef.show)
      throw e

  def ret(res: V, resType: String)(using ctx: FunContext): Unit =
    if !ctx.terminated then
      terminate(s"ret ${coerce(res, resType).text}")

  //----------------------------------------------------------------------------
  // Instructions

  def fresh()(using ctx: FunContext): String =
    ctx.tmpCount += 1
    "%t." + ctx.tmpCount

  def freshLabel(prefix: String)(using ctx: FunContext): String =
    ctx.labelCount += 1
    prefix + "." + ctx.labelCount

  def emit(instr: String)(using ctx: FunContext): Unit =
    if ctx.terminated then
      // code after a terminator lives in a block without predecessors
      label(freshLabel("dead"))

    ctx.body ++= "  " ++= instr += '\n'

  def assign(ty: String, rhs: String)(using FunContext): V =
    val res = fresh()
    emit(s"$res = $rhs")
    V(ty, res)

  def terminate(instr: String)(using ctx: FunContext): Unit =
    emit(instr)
    ctx.terminated = true

  def label(name: String)(using ctx: FunContext): Unit =
    if !ctx.terminated then
      ctx.body ++= s"  br label %$name\n"

    ctx.body ++= name ++= ":\n"
    ctx.terminated = false

  def localSlot(sym: Symbol)(using ctx: FunContext): Slot =
    ctx.slots.getOrElseUpdate(sym, {
      ctx.tmpCount += 1
      val name = "%local." + ctx.tmpCount
      val ty = llvmType(sym.tpe.widen)
      ctx.allocas ++= s"  $name = alloca $ty, align 8\n"
      Slot(name, ty)
    })

  /** Convert a value to another LLVM type */
  def coerce(v: V, ty: String)(using FunContext): V =
    if v.ty == ty then v
    else if ty == "void" then V.Void
    else if v.ty == "void" then V(ty, "undef")
    else
      (v.ty, ty) match
        case (from, to) if isInt(from) && isInt(to) =>
          val (wFrom, wTo) = (intWidth(from), intWidth(to))
          if wFrom > wTo then assign(to, s"trunc ${v.text} to $to")
          else if from == "i32" then assign(to, s"sext ${v.text} to $to")
          else assign(to, s"zext ${v.text} to $to")

        case ("ptr", to) if isInt(to) =>
          coerce(assign("i64", s"ptrtoint ${v.text} to i64"), to)

        case (from, "ptr") if isInt(from) =>
          val v64 = coerce(v, "i64")
          assign("ptr", s"inttoptr ${v64.text} to ptr")

        case ("double", to) =>
          coerce(assign("i64", s"bitcast ${v.text} to i64"), to)

        case (_, "double") =>
          val v64 = coerce(v, "i64")
          assign("double", s"bitcast ${v64.text} to double")

        case _ =>
          throw new Exception(s"Cannot convert ${v.text} to $ty")

  /** Store a value in a field of a heap object
    *
    * Initialization stores fill a freshly allocated object. Other stores mutate
    * an existing object, where a GC write barrier goes.
    */
  def storeField(obj: V, offset: Int, value: V, ty: String, isInit: Boolean)(using FunContext): Unit =
    // no write barrier yet: the allocator never frees
    val _ = isInit
    val addr = fieldAddr(obj, offset)
    emit(s"store ${coerce(value, ty).text}, ${addr.text}, align ${align(ty)}")

  def loadField(obj: V, offset: Int, ty: String)(using FunContext): V =
    val addr = fieldAddr(obj, offset)
    assign(ty, s"load $ty, ${addr.text}, align ${align(ty)}")

  def fieldAddr(obj: V, offset: Int)(using FunContext): V =
    val ptr = coerce(obj, "ptr")
    if offset == 0 then ptr
    else assign("ptr", s"getelementptr inbounds i8, ${ptr.text}, i64 $offset")

  //----------------------------------------------------------------------------
  // Words

  def compile(word: Word)(using ctx: FunContext): V =
    word match
      case Literal(c) => compile(c)

      case Block(words) =>
        var res = V.Void
        for w <- words do res = compile(w)
        res

      case Encoded(repr) =>
        val v = compile(repr)
        if word.tpe.isVoidType then V.Void
        else coerce(v, llvmType(word.tpe))

      case app: Apply => compile(app)

      case assign: Assign => compile(assign)

      case ifElse: If => compile(ifElse)

      case whileDo: While => compile(whileDo)

      case Labeled(target, resultType, body) =>
        assert(resultType.isVoidType, s"LLVM backend only supports VoidType labeled blocks, found ${resultType.show}")
        val labelEnd = freshLabel("labeled.end")
        assert(!ctx.localReturnTargets.contains(target), s"Duplicate local return label: $target")
        ctx.localReturnTargets(target) = labelEnd
        try
          compile(body)
        finally
          ctx.localReturnTargets.remove(target)

        label(labelEnd)
        V.Void

      case Return(target, value) =>
        if target.is(Flags.Fun) then
          val res = compile(value)
          ret(res, retType(ctx.fun.tpe.asProcType.resultType))
        else
          assert(value.tpe.isVoidType && value.isEmpty,
            s"LLVM backend expects empty VoidType payload for local labeled return, found ${value.show}")
          terminate(s"br label %${ctx.localReturnTargets(target)}")

        V.Void

      case id: Ident => compile(id)

      case TypeApply(fun, _) => compile(fun)

      case rec: RecordLit => compile(rec)

      case select: Select => compile(select)

      case FieldAssign(Select(qual, name), rhs) =>
        val recordType = recordTypeOf(qual)
        val obj = compile(qual)
        val value = compile(rhs)
        val ty = llvmType(Layout.fieldType(recordType, name))
        storeField(obj, Layout.fieldOffset(recordType, name), value, ty, isInit = false)
        V.Void

      case _: Def         | _: With      | _: Allow  | _: Match  | _: PatValDef |
           _: New         | _: IsExpr    | _: Lambda | _: ClassTest
      =>
        throw new Exception("Unexpected " + word)

  def compile(c: Constant)(using FunContext): V =
    c match
      case Constant.Bool(b)   => V("i1", if b then "true" else "false")
      case Constant.Byte(n)   => V("i8", (n & 0xFF).toString)
      case Constant.Char(n)   => V("i32", n.toString)
      case Constant.Int(n)    => V("i32", n.toString)
      case Constant.Long(n)   => V("i64", n.toString)
      case Constant.Float(d)  => V("double", "0x%016X".format(java.lang.Double.doubleToRawLongBits(d)))

      case Constant.String(s) =>
        val bytes = V("ptr", addString(s))
        callFun(runtime.Core_String_fromByteString, bytes :: Nil)

  def compile(id: Ident)(using ctx: FunContext): V =
    val sym = id.symbol
    if sym.is(Flags.Fun) then
      V("ptr", funRef(sym))

    else if sym.isLocal || ctx.slots.contains(sym) then
      val slot = localSlot(sym)
      assign(slot.ty, s"load ${slot.ty}, ptr ${slot.name}")

    else
      throw new Exception("accessing non-local variable " + sym)

  def compile(assign: Assign)(using ctx: FunContext): V =
    val sym = assign.symbol
    val rhs = compile(assign.rhs)

    if sym.is(Flags.Object) && isLoweringObjectInitProc then
      val holder = runtime.getObjectHolderByDataSymbol(sym)
      emit(s"store ${coerce(rhs, "ptr").text}, ptr $holder")

    else if sym.isLocal || !sym.isFunction then
      val slot = localSlot(sym)
      emit(s"store ${coerce(rhs, slot.ty).text}, ptr ${slot.name}")

    else
      throw new Exception("assigning to non-local " + sym + ", owner = " + sym.owner)

    V.Void

  def compile(ifElse: If)(using ctx: FunContext): V =
    val labelThen = freshLabel("then")
    val labelElse = freshLabel("else")
    val labelEnd = freshLabel("endif")

    val resType = llvmType(ifElse.tpe)
    val hasValue = resType != "void" && !ifElse.tpe.isBottomType
    val resSlot =
      if hasValue then
        ctx.tmpCount += 1
        val name = "%if." + ctx.tmpCount
        ctx.allocas ++= s"  $name = alloca $resType, align 8\n"
        Some(Slot(name, resType))
      else None

    val cond = coerce(compile(ifElse.cond), "i1")
    terminate(s"br ${cond.text}, label %$labelThen, label %$labelElse")

    def branch(name: String, body: Word): Unit =
      label(name)
      val v = compile(body)
      for slot <- resSlot if !ctx.terminated do
        emit(s"store ${coerce(v, slot.ty).text}, ptr ${slot.name}")
      if !ctx.terminated then
        terminate(s"br label %$labelEnd")

    branch(labelThen, ifElse.thenp)
    branch(labelElse, ifElse.elsep)

    label(labelEnd)
    resSlot match
      case Some(slot) => assign(slot.ty, s"load ${slot.ty}, ptr ${slot.name}")
      case None => V.Void

  def compile(whileDo: While)(using ctx: FunContext): V =
    val labelCond = freshLabel("while.cond")
    val labelBody = freshLabel("while.body")
    val labelEnd = freshLabel("while.end")

    label(labelCond)
    val cond = coerce(compile(whileDo.cond), "i1")
    terminate(s"br ${cond.text}, label %$labelBody, label %$labelEnd")

    label(labelBody)
    compile(whileDo.body)
    if !ctx.terminated then
      terminate(s"br label %$labelCond")

    label(labelEnd)
    V.Void

  def recordTypeOf(qual: Word): RecordType =
    if qual.tpe.isClassInfoType then
      Layout.encodeClassType(qual.tpe.classInfo)
    else
      assert(qual.tpe.isRecordType, "Expect record type, found = " + qual.tpe.show)
      qual.tpe.asRecordType

  def compile(select: Select)(using FunContext): V =
    val Select(qual, name) = select
    if qual.tpe.isInterfaceType then
      throw new Exception("Interface select should have been translated: " + select.show)

    val recordType = recordTypeOf(qual)
    val obj = compile(qual)
    val ty = llvmType(Layout.fieldType(recordType, name))
    loadField(obj, Layout.fieldOffset(recordType, name), ty)

  def compile(rec: RecordLit)(using FunContext): V =
    val recordType = rec.tpe.asRecordType
    val size = V("i32", Layout.size(recordType).toString)
    val obj = callFun(runtime.GC_alloc, size :: Nil)

    for (name, rhs) <- rec.args do
      val value = compile(rhs)
      val ty = llvmType(Layout.fieldType(recordType, name))
      storeField(obj, Layout.fieldOffset(recordType, name), value, ty, isInit = true)

    obj

  //----------------------------------------------------------------------------
  // Calls

  def compile(app: Apply)(using ctx: FunContext): V =
    val funSym =
      app.funSymbol.orElse:
        app.fun match
          case Encoded(Ident(sym)) if sym.is(Flags.Fun) => Some(sym)
          case _ => None

    funSym match
      case Some(sym) =>
        if sym.is(Flags.Object) && !isLoweringObjectInitProc then
          // make the accessor reachable
          funRef(sym)

          // skip the call and access directly the object
          val holder = runtime.getObjectHolder(sym)
          assign("ptr", s"load ptr, ptr $holder")

        else if sym.owner == runtime.LibC then
          callC(sym, app.allArgs.map(compile))

        else if sym.hasAnnotation(defn.intrinsic) then
          callIntrinsic(sym, app)

        else
          callFun(sym, app.allArgs.map(compile))

      case None =>
        val fun = coerce(compile(app.fun), "ptr")
        val funType = app.fun.tpe.asProcType
        val args = app.allArgs.map(compile)
        call(fun.v, funType.allParamTypes, funType.resultType, args)

  /** Call a Jo function directly */
  def callFun(sym: Symbol, args: List[V])(using FunContext): V =
    val target = resolve(sym)
    val funType = target.tpe.asProcType
    call(funRef(target), funType.allParamTypes, funType.resultType, args)

  def call(fun: String, paramTypes: List[Type], resultType: Type, args: List[V])(using ctx: FunContext): V =
    assert(paramTypes.size == args.size, s"Expect ${paramTypes.size} arguments for $fun, found ${args.size}")
    val argTexts = args.zip(paramTypes).map((arg, tp) => coerce(arg, llvmType(tp)).text)
    val resType = retType(resultType)
    val res = assign(resType, s"call $resType $fun(${argTexts.mkString(", ")})")

    if resultType.isBottomType then
      terminate("unreachable")
      V.Void
    else
      res

  /** Call a function of the C library */
  def callC(sym: Symbol, args: List[V])(using FunContext): V =
    val funType = sym.tpe.asProcType
    val paramTypes = funType.allParamTypes.map(cType)
    val resType = cType(funType.resultType)
    val noreturn = if funType.resultType.isBottomType then " noreturn" else ""
    cDecls.getOrElseUpdate(sym.name, s"declare $resType @${sym.name}(${paramTypes.mkString(", ")})$noreturn")

    val argTexts = args.zip(paramTypes).map((arg, ty) => coerce(arg, ty).text)
    val callText = s"call $resType @${sym.name}(${argTexts.mkString(", ")})"

    if funType.resultType.isBottomType then
      emit(callText)
      terminate("unreachable")
      V.Void
    else
      assign(resType, callText)

  //----------------------------------------------------------------------------
  // Intrinsics

  def callIntrinsic(sym: Symbol, app: Apply)(using ctx: FunContext): V =
    def args: List[V] = app.allArgs.map(compile)

    if sym == runtime.ParamSupport_paramKey then
      val paramSym = app.args.head match
        case Ident(paramSym) => paramSym
        case Encoded(Ident(paramSym)) => paramSym
        case Encoded(Apply(_, Ident(paramSym) :: Nil, Nil)) => paramSym
        case arg => throw new Exception("Unsupported argument to paramKey: " + arg)

      V("ptr", addString(paramSym.fullName))

    else if sym == runtime.Core_state then
      V("ptr", StateGlobal)

    else if sym == runtime.Core_getInterfaceTable then
      val Literal(Constant.Int(classId)) = app.args.head.runtimeChecked
      V("ptr", interfaceTable(runtime.getClassSymbol(classId)))

    else if sym == runtime.Core_initObjects then
      callFun(runtime.objectInitProcSym, Nil)

    else if sym == runtime.Core_stackOverflowHandlerAddress then
      V("ptr", funRef(runtime.Core_stackOverflowHandler))

    else if sym == runtime.Core_RefArray_ArrayClassId then
      V("i32", runtime.getClassId(defn.Array_class).toString)

    else if sym == defn.jo_pass then
      V.Unit

    else if sym == runtime.Bool_and then
      val a :: b :: Nil = app.args: @unchecked
      compile(If(a, b, BoolLit(false)(a.span))(defn.BoolType, a.span | b.span))

    else if sym == runtime.Bool_or then
      val a :: b :: Nil = app.args: @unchecked
      compile(If(a, BoolLit(true)(a.span), b)(defn.BoolType, a.span | b.span))

    else if sym.owner == runtime.Core_BoolOps then
      boolPrimitive(sym.name, args)

    else if sym.owner == runtime.Core_IntOps then
      intPrimitive(sym.name, "i32", args)

    else if sym.owner == runtime.Core_LongOps then
      intPrimitive(sym.name, "i64", args)

    else if sym.owner == runtime.Core_CharOps then
      charPrimitive(sym.name, args)

    else if sym.owner == runtime.Core_ByteOps then
      bytePrimitive(sym.name, args)

    else if sym.owner == runtime.Core_FloatOps then
      floatPrimitive(sym.name, args)

    else
      memoryPrimitive(sym, args)

  def memoryPrimitive(sym: Symbol, args: List[V])(using FunContext): V =
    def load(ty: String): V =
      val addr = coerce(args.head, "ptr")
      assign(ty, s"load $ty, ${addr.text}, align ${align(ty)}")

    def store(ty: String): V =
      val addr :: value :: Nil = args: @unchecked
      emit(s"store ${coerce(value, ty).text}, ${coerce(addr, "ptr").text}, align ${align(ty)}")
      V.Unit

    sym match
      case runtime.Core_readInt   => load("i32")
      case runtime.Core_readByte  => load("i8")
      case runtime.Core_readAddr  => load("ptr")
      case runtime.Core_writeInt  => store("i32")
      case runtime.Core_writeByte => store("i8")
      case runtime.Core_writeAddr => store("ptr")

      case runtime.Core_addAddr =>
        val addr :: offset :: Nil = args: @unchecked
        val offset64 = coerce(offset, "i64")
        assign("ptr", s"getelementptr i8, ${coerce(addr, "ptr").text}, ${offset64.text}")

      case runtime.Core_nullAddr => V("ptr", "null")

      case runtime.Core_isNull =>
        assign("i1", s"icmp eq ${coerce(args.head, "ptr").text}, null")

      case runtime.Core_sameRef =>
        val a :: b :: Nil = args: @unchecked
        assign("i1", s"icmp eq ${coerce(a, "ptr").text}, ${coerce(b, "ptr").v}")

      case runtime.Core_wordSize => V("i32", Layout.SlotSize.toString)

      case runtime.Core_intToLong => coerce(args.head, "i64")

      case runtime.Core_cast => coerce(args.head, "ptr")

      case _ =>
        throw new Exception("Unknown intrinsic: " + sym.fullName)

  def binary(op: String, ty: String, args: List[V])(using FunContext): V =
    val a :: b :: Nil = args: @unchecked
    assign(ty, s"$op ${coerce(a, ty).text}, ${coerce(b, ty).v}")

  def compare(op: String, ty: String, args: List[V])(using FunContext): V =
    val a :: b :: Nil = args: @unchecked
    assign("i1", s"$op ${coerce(a, ty).text}, ${coerce(b, ty).v}")

  def convert(op: String, from: String, to: String, args: List[V])(using FunContext): V =
    assign(to, s"$op ${coerce(args.head, from).text} to $to")

  def boolPrimitive(name: String, args: List[V])(using FunContext): V =
    name match
      case "==" => compare("icmp eq", "i1", args)
      case "!=" => compare("icmp ne", "i1", args)
      case "~!" => assign("i1", s"xor ${coerce(args.head, "i1").text}, true")
      case _ => throw new Exception("Unknown Bool primitive: " + name)

  /** Primitives of signed integers: Int and Long */
  def intPrimitive(name: String, ty: String, args: List[V])(using FunContext): V =
    name match
      case "+"  => binary("add", ty, args)
      case "-"  => binary("sub", ty, args)
      case "*"  => binary("mul", ty, args)
      case "/"  => binary("sdiv", ty, args)
      case "%"  => binary("srem", ty, args)
      case ">"  => compare("icmp sgt", ty, args)
      case "<"  => compare("icmp slt", ty, args)
      case ">=" => compare("icmp sge", ty, args)
      case "<=" => compare("icmp sle", ty, args)
      case "==" => compare("icmp eq", ty, args)
      case "!=" => compare("icmp ne", ty, args)
      case ">>" => binary("ashr", ty, args)
      case "<<" => binary("shl", ty, args)
      case "&"  => binary("and", ty, args)
      case "|"  => binary("or", ty, args)
      case "^"  => binary("xor", ty, args)
      case "~-" => assign(ty, s"sub $ty 0, ${coerce(args.head, ty).v}")
      case "~~" => assign(ty, s"xor ${coerce(args.head, ty).text}, -1")
      case "toChar" => coerce(args.head, "i32")
      case "toByte" => convert("trunc", ty, "i8", args)
      case "toInt" => convert("trunc", ty, "i32", args)
      case "toFloat" => convert("sitofp", ty, "double", args)
      case _ => throw new Exception(s"Unknown $ty primitive: " + name)

  def charPrimitive(name: String, args: List[V])(using FunContext): V =
    name match
      case "==" => compare("icmp eq", "i32", args)
      case "!=" => compare("icmp ne", "i32", args)
      case ">"  => compare("icmp sgt", "i32", args)
      case "<"  => compare("icmp slt", "i32", args)
      case ">=" => compare("icmp sge", "i32", args)
      case "<=" => compare("icmp sle", "i32", args)
      case "toByte" => convert("trunc", "i32", "i8", args)
      case "toInt" => coerce(args.head, "i32")
      case "toFloat" => convert("sitofp", "i32", "double", args)
      case _ => throw new Exception("Unknown Char primitive: " + name)

  /** Byte is unsigned in [0, 255] */
  def bytePrimitive(name: String, args: List[V])(using FunContext): V =
    name match
      case "==" => compare("icmp eq", "i8", args)
      case "!=" => compare("icmp ne", "i8", args)
      case ">"  => compare("icmp ugt", "i8", args)
      case "<"  => compare("icmp ult", "i8", args)
      case ">=" => compare("icmp uge", "i8", args)
      case "<=" => compare("icmp ule", "i8", args)
      case "toInt" | "toChar" => convert("zext", "i8", "i32", args)
      case "toFloat" => convert("uitofp", "i8", "double", args)
      case _ => throw new Exception("Unknown Byte primitive: " + name)

  def floatPrimitive(name: String, args: List[V])(using FunContext): V =
    name match
      case "+"  => binary("fadd", "double", args)
      case "-"  => binary("fsub", "double", args)
      case "*"  => binary("fmul", "double", args)
      case "/"  => binary("fdiv", "double", args)
      case ">"  => compare("fcmp ogt", "double", args)
      case "<"  => compare("fcmp olt", "double", args)
      case ">=" => compare("fcmp oge", "double", args)
      case "<=" => compare("fcmp ole", "double", args)
      case "==" => compare("fcmp oeq", "double", args)
      case "!=" => compare("fcmp une", "double", args)
      case "~-" => assign("double", s"fneg ${coerce(args.head, "double").text}")
      case "toInt" => convert("fptosi", "double", "i32", args)
      case _ => throw new Exception("Unknown Float primitive: " + name)
end CodeGen

object CodeGen:
  /** A typed LLVM value */
  case class V(ty: String, v: String):
    def text: String = ty + " " + v

  object V:
    val Void = V("void", "")
    val Unit = V("ptr", "null")

  /** A stack slot of a local variable */
  case class Slot(name: String, ty: String)

  class FunContext(val fun: Symbol):
    val allocas = new StringBuilder
    val body = new StringBuilder
    var tmpCount = 0
    var labelCount = 0
    var terminated = false
    val slots = mutable.Map.empty[Symbol, Slot]
    val localReturnTargets = mutable.Map.empty[Symbol, String]

  def isInt(ty: String): Boolean = ty == "i1" || ty == "i8" || ty == "i32" || ty == "i64"

  def intWidth(ty: String): Int = ty.drop(1).toInt

  def align(ty: String): Int =
    ty match
      case "i1" | "i8" => 1
      case "i32" => 4
      case _ => 8

  /** A global name, quoted and escaped */
  def globalName(name: String): String =
    "@\"" + escapeBytes(name.getBytes("UTF-8")) + "\""

  /** Escape bytes for an LLVM quoted string */
  def escapeBytes(bytes: Array[Byte]): String =
    val sb = new StringBuilder
    for b <- bytes do
      val c = b & 0xFF
      if c >= 0x20 && c < 0x7F && c != '"' && c != '\\' then sb += c.toChar
      else sb ++= "\\%02X".format(c)
    sb.toString
end CodeGen

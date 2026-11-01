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
class CodeGen(runtime: LLVMRuntime, regionFunctions: Set[Symbol], policy: CodeGen.RegionPolicy, placeResults: Boolean, memProfile: Boolean)(using defn: Definitions):
  import CodeGen.*

  /** Worklist to add all reachable functions */
  private val workList = new WorkList[Symbol]

  /** LLVM names of Jo functions */
  private val funNames = mutable.Map.empty[Symbol, String]

  /** Region functions, numbered from 1 in `Regions.enter`. 0 is the runtime region. */
  private val regionIds = mutable.LinkedHashMap.empty[Symbol, Int]

  /** Static string objects of string constants */
  private val stringTable = mutable.LinkedHashMap.empty[String, String]

  /** Static keys of context parameters, by full name */
  private val paramKeys = mutable.LinkedHashMap.empty[String, String]

  /** Declarations of C functions */
  private val cDecls = mutable.LinkedHashMap.empty[String, String]

  /** Interface tables of classes */
  private val itables = mutable.LinkedHashMap.empty[Symbol, String]

  /** Static tables of shared boxes, by box class */
  private val sharedBoxTables = mutable.LinkedHashMap.empty[Symbol, String]

  private val functions = new StringBuilder

  private var isLoweringObjectInitProc = false

  /** Function definitions by symbol */
  private val funDefs = mutable.Map.empty[Symbol, FunDef]

  /** The functions that may allocate, after all definitions are known */
  private lazy val allocations = new Allocations(funDefs, runtime, resolve)

  /** The result regions of the analyzed functions, `None` while analyzing */
  private val resultRegionsCache = mutable.Map.empty[Symbol, Option[ResultRegions]]

  private val StateGlobal = "@runtime.state"

  //----------------------------------------------------------------------------
  // Module

  def compile(units: List[FileUnit]): String =
    for
      unit <- units
      defn <- unit
    do
      defn match
        case fdef: FunDef => funDefs(fdef.symbol) = fdef
        case _ =>

    val startName = funRef(runtime.Core_start)

    workList.run: sym =>
      compileFunDef(funDefs(sym))

    // must come last after traversing the whole universe
    //
    // In lowering objectInitProc, accessor calls should be called literally once.
    isLoweringObjectInitProc = true
    compileFunDef(runtime.getObjectInitProc())
    assert(workList.isEmpty, "Non-empty work list: " + workList.todos())

    val module = new StringBuilder

    for (value, name) <- stringTable do
      module ++= staticStringGlobals(name, value)

    for (fullName, name) <- paramKeys do
      module ++= paramKeyGlobal(name, fullName)

    for (cls, name) <- sharedBoxTables do
      module ++= sharedBoxGlobal(cls, name)

    for holder <- runtime.objectHolders do
      module ++= s"$holder = internal global ptr null, align 8\n"

    val stateSlots = Layout.classInstanceSize(runtime.Core_State) / Layout.SlotSize
    module ++= s"$StateGlobal = internal global [$stateSlots x i64] zeroinitializer, align 8\n"

    for (cls, name) <- itables do
      module ++= interfaceTableGlobal(cls, name)

    module ++= layoutTableGlobals()
    module ++= nameTableGlobals()

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

    // Jo eliminates tail calls itself. LLVM would also turn accumulating
    // recursion into loops, so deep recursion would no longer overflow.
    module ++= "\nattributes #0 = { \"disable-tail-calls\"=\"true\" }\n"

    // Region boundaries stay opaque calls, so no memory access moves across them
    module ++= "attributes #1 = { noinline \"disable-tail-calls\"=\"true\" }\n"

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

  /** A static `Raw` string object for the constant */
  def addString(value: String): String =
    stringTable.get(value) match
      case Some(name) => name
      case None =>
        val name = "@str." + stringTable.size
        stringTable(value) = name

        // the module is emitted after codegen, when the interface table can
        // no longer make methods reachable
        interfaceTable(runtime.Core_String_Raw)
        name

  /** The address of the bytes of the string constant, as stored in `Raw.content` */
  def stringContent(value: String): String =
    s"getelementptr inbounds (i8, ptr ${addString(value)}.buf, i64 ${Layout.SlotSize})"

  /** A static buffer object with the bytes and a static `Raw` object pointing to it
    *
    * Static objects have `gc = 0` in the header: they belong to the runtime
    * region, so the garbage collector never moves or frees them.
    */
  def staticStringGlobals(name: String, value: String): String =
    val bytes = value.getBytes("UTF-8")
    val n = bytes.length
    val buffer =
      s"$name.buf = private unnamed_addr constant <{ i32, i32, [$n x i8] }> " +
      s"<{ i32 ${runtime.BufferClassId}, i32 0, [$n x i8] c\"${escapeBytes(bytes)}\" }>, align 8\n"

    val rawCls = runtime.Core_String_Raw
    val fields = Map(
      "byteSize" -> V("i32", n.toString),
      "cpCount"  -> V("i32", value.codePointCount(0, value.length).toString),
      "content"  -> V("ptr", stringContent(value)),
    )

    val ty = staticObjectType(rawCls)
    val init = staticObjectInit(rawCls, fields)
    buffer + s"$name = private unnamed_addr constant $ty $init, align 8\n"

  /** The LLVM type of a static object of a class, laid out like a heap object */
  def staticObjectType(cls: Symbol): String =
    val recordType = Layout.encodeClassType(cls.classInfo)
    recordType.fields.map(field => staticSlotType(llvmType(field.info))).mkString("<{ ", ", ", " }>")

  /** The initializer of a static object of a class, with `gc = 0` in the header */
  def staticObjectInit(cls: Symbol, fields: Map[String, V]): String =
    val header = Map(
      Layout.ClassID -> V("i32", runtime.getClassId(cls).toString),
      Layout.ITable  -> V("ptr", interfaceTable(cls)),
    )

    val values = header ++ fields
    val recordType = Layout.encodeClassType(cls.classInfo)
    recordType.fields.map(field => staticSlotInit(values(field.name))).mkString("<{ ", ", ", " }>")

  /** The type of one 8-byte slot of a static object holding a value of type `ty` */
  def staticSlotType(ty: String): String =
    ty match
      case "ptr" | "i64" => ty
      case "i32" => "i32, i32"
      case "i8" | "i1" => s"$ty, [7 x i8]"
      case _ => throw new Exception("Unsupported static slot type: " + ty)

  /** The initializer of one 8-byte slot of a static object */
  def staticSlotInit(value: V): String =
    value.ty match
      case "ptr" | "i64" => value.text
      case "i32" => s"${value.text}, i32 0"
      case "i8" | "i1" => s"${value.text}, [7 x i8] zeroinitializer"
      case ty => throw new Exception("Unsupported static slot type: " + ty)

  /** The static table of the shared boxes of a box class
    *
    * The module is emitted after codegen, when the class id and the interface
    * table can no longer make methods reachable, so they are requested here.
    */
  def sharedBoxTable(cls: Symbol): String =
    sharedBoxTables.get(cls) match
      case Some(name) => name
      case None =>
        val name = "@box." + sharedBoxTables.size
        sharedBoxTables(cls) = name
        runtime.getClassId(cls)
        interfaceTable(cls)
        name

  /** The values that have a shared box of a box class, in table order */
  def sharedBoxValues(cls: Symbol): Seq[V] =
    if cls == runtime.Core_IntBox then
      for i <- 0 until Layout.IntBoxCount yield V("i32", (Layout.IntBoxLow + i).toString)
    else if cls == runtime.Core_CharBox then
      for i <- 0 until Layout.CharBoxCount yield V("i32", i.toString)
    else if cls == runtime.Core_ByteBox then
      for i <- 0 until 256 yield V("i8", i.toByte.toString)
    else
      V("i1", "false") :: V("i1", "true") :: Nil

  /** A constant array of the shared boxes of a box class, laid out like heap objects
    *
    * The boxes are in read-only memory, so a stray write to their header
    * faults. With `gc = 0` they belong to the runtime region, like string
    * literals, so the garbage collector never lowers, moves or frees them.
    */
  def sharedBoxGlobal(cls: Symbol, name: String): String =
    val ty = staticObjectType(cls)
    val values = sharedBoxValues(cls)
    val inits = values.map(value => ty + " " + staticObjectInit(cls, Map("value" -> value)))
    s"$name = private unnamed_addr constant [${values.size} x $ty] [${inits.mkString(", ")}], align 8\n"

  /** The address of the shared box at `index` in the table of a box class */
  def sharedBox(cls: Symbol, index: V)(using FunContext): V =
    val table = sharedBoxTable(cls)
    val i = assign("i64", s"zext ${index.text} to i64")
    assign("ptr", s"getelementptr inbounds ${staticObjectType(cls)}, ptr $table, i64 ${i.v}")

  /** The key of a context parameter, an address unique to the parameter */
  def paramKey(fullName: String): String =
    val name = paramKeys.getOrElseUpdate(fullName, "@key." + paramKeys.size)
    s"getelementptr inbounds (i8, ptr $name, i64 ${Layout.SlotSize})"

  /** A static buffer holding the name of a context parameter as `[size: i32][bytes]`
    *
    * The key points past the header, like any `Addr` field. The runtime reads
    * the name with `StringOps.fromByteString` to report an unbound parameter.
    */
  def paramKeyGlobal(name: String, fullName: String): String =
    val bytes = fullName.getBytes("UTF-8")
    val n = bytes.length
    s"$name = private constant <{ i32, i32, i32, [$n x i8] }> " +
    s"<{ i32 ${runtime.BufferClassId}, i32 0, i32 $n, [$n x i8] c\"${escapeBytes(bytes)}\" }>, align 8\n"

  /** Pointer layout of every class id, for the garbage collector
    *
    * `@gc.layouts` is an array indexed by class id. Each entry points to an
    * `i32` array `[n, s1, ..., sn]` that lists the `n` slots holding pointers.
    * A slot with `InteriorFlag` set holds an `Addr`, which points just past the
    * header of a buffer. `n = -1` marks a reference array, whose elements are
    * all pointers.
    */
  def layoutTableGlobals(): String =
    val InteriorFlag = 0x40000000
    val buf = new StringBuilder
    val entries = new mutable.ArrayBuffer[String]

    for id <- 0 until runtime.classIdCount do
      val slots: List[Int] =
        if id == runtime.BufferClassId || id == runtime.BytesClassId then Nil
        else
          val cls = runtime.getClassSymbol(id)
          if cls == defn.Array_class then -1 :: Nil
          else if cls == runtime.Core_ClosureTag then
            val closureSlots = List(Layout.ClassID, Layout.Apply, Layout.Underlying)
            closureSlots.indexOf(Layout.Underlying) :: Nil
          else
            val recordType = Layout.encodeClassType(cls.classInfo)
            for
              (field, index) <- recordType.fields.zipWithIndex
              if field.name != Layout.ClassID && field.name != Layout.ITable
              if !field.info.isVoidType && llvmType(field.info) == "ptr"
            yield
              if isAddr(field.info) then index | InteriorFlag else index

      val values = slots match
        case -1 :: Nil => List(-1)
        case _ => slots.size :: slots

      val name = s"@gc.layout.$id"
      buf ++= s"$name = private constant [${values.size} x i32] [${values.map("i32 " + _).mkString(", ")}], align 4\n"
      entries += s"ptr $name"

    buf ++= s"@gc.layouts = internal constant [${entries.size} x ptr] [${entries.mkString(", ")}], align 8\n"
    buf.toString

  /** Names for the memory statistics, null-terminated arrays of C strings
    *
    * Under `--mem-profile`, `@gc.funNames` names `main` and then the region
    * functions by number, as `name at=path:line`, and `@gc.classNames` names
    * the classes by class id. Otherwise both are empty.
    */
  def nameTableGlobals(): String =
    val buf = new StringBuilder

    def table(name: String, labels: Seq[String]): Unit =
      val entries =
        for (label, i) <- labels.zipWithIndex yield
          val bytes = label.getBytes("UTF-8") :+ 0.toByte
          buf ++= s"$name.$i = private unnamed_addr constant [${bytes.length} x i8] c\"${escapeBytes(bytes)}\", align 1\n"
          s"ptr $name.$i"

      val all = entries :+ "ptr null"
      buf ++= s"$name = internal constant [${all.size} x ptr] [${all.mkString(", ")}], align 8\n"

    def label(sym: Symbol): String =
      val at =
        try
          val pos = sym.sourcePos
          val text = pos.toString
          " at=" + text.substring(0, text.lastIndexOf(':'))
        catch case _: Exception => ""

      sym.fullName + at

    if memProfile then
      val main = runtime.rewire.get(defn.main).map(label).getOrElse("main")
      table("@gc.funNames", main +: regionIds.keys.toSeq.map(label))
      val classes =
        for id <- 0 until runtime.classIdCount yield
          if id == runtime.BufferClassId then "buffer"
          else if id == runtime.BytesClassId then "jo.Bytes"
          else runtime.getClassSymbol(id).fullName

      table("@gc.classNames", classes)

    else
      table("@gc.funNames", Nil)
      table("@gc.classNames", Nil)

    buf.toString

  def isAddr(tp: Type): Boolean =
    tp.approx match
      case StaticRef(sym) => sym == runtime.Core_Addr
      case _ => false

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

    if opensRegion(sym, procType.resultType) then
      ctx.isRegion = true
      val id = regionIds.size + 1
      regionIds(sym) = id
      callFun(runtime.Regions_enter, V("i32", id.toString) :: Nil)

      if placeResults && llvmType(procType.resultType) == "ptr" then
        ctx.placement = resultRegions(sym)

    val paramTypes = procType.allParamTypes.map(llvmType)
    val resType = retType(procType.resultType)

    val paramDecls = paramTypes.zipWithIndex.map((ty, i) => s"$ty %arg.$i")

    for ((param, ty), i) <- fdef.allParams.zip(paramTypes).zipWithIndex do
      val slot = localSlot(param)
      emit(s"store ${coerce(V(ty, s"%arg.$i"), slot.ty).text}, ptr ${slot.name}")

    val res = compile(fdef.body)
    ret(res, resType)

    val attrs = if sym == runtime.Regions_enter || sym == runtime.Regions_leave then "#1" else "#0"
    functions ++= s"define internal $resType $name(${paramDecls.mkString(", ")}) $attrs {\n"
    functions ++= "entry:\n"
    functions ++= ctx.allocas
    functions ++= ctx.body
    functions ++= "}\n\n"
  catch
    case e: Throwable =>
      println("Failed to compile " + fdef.symbol.fullName + ": " + fdef.show)
      throw e

  /** The result regions of a function. A recursive call sees no summary. */
  def resultRegions(sym: Symbol): Option[ResultRegions] =
    val target = resolve(sym)
    resultRegionsCache.get(target) match
      case Some(res) => res
      case None =>
        funDefs.get(target).flatMap: fdef =>
          resultRegionsCache(target) = None
          val res = new ResultRegions(fdef, callee => resultRegions(callee).map(_.params).getOrElse(Set.empty))
          resultRegionsCache(target) = Some(res)
          Some(res)

  /** Whether a function opens a region, by `--region` or the region policy */
  def opensRegion(sym: Symbol, resultType: Type): Boolean =
    // The runtime manages the regions, and main runs in no region
    def selectable =
      !sym.fullName.startsWith("jo.runtime.") && !runtime.rewire.get(defn.main).contains(sym)

    def byPolicy = policy match
      case RegionPolicy.None => false
      case RegionPolicy.Scalar => !Set("ptr", "void").contains(llvmType(resultType))
      case RegionPolicy.All => true

    // A function that cannot allocate would always have an empty region
    regionFunctions.contains(sym) || (selectable && byPolicy && allocations.mayAllocate(sym))

  def ret(res: V, resType: String)(using ctx: FunContext): Unit =
    if !ctx.terminated then
      val value = coerce(res, resType)

      // close the region, keeping the result
      if ctx.isRegion then
        val result = if resType == "ptr" then value else V("ptr", "null")
        callFun(runtime.Regions_leave, result :: Nil)

      terminate(s"ret ${value.text}")

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
    * Initialization stores fill a freshly allocated object and need no barrier:
    * the object is in the current region, which everything it can refer to
    * outlives. Other stores of a reference go through the write barrier. An
    * `Addr` field holds memory outside the regions or an interior pointer,
    * which is never mutated.
    */
  def storeField(obj: V, offset: Int, value: V, fieldType: Type, isInit: Boolean)(using FunContext): Unit =
    val ty = llvmType(fieldType)
    if !isInit && ty == "ptr" && !isAddr(fieldType) && value.v != "null" then
      callFun(runtime.Regions_barrier, coerce(obj, "ptr") :: coerce(value, "ptr") :: Nil)

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
        storeField(obj, Layout.fieldOffset(recordType, name), value, Layout.fieldType(recordType, name), isInit = false)
        V.Void

      case _: Def         | _: With      | _: Allow  | _: Match  | _: PatValDef |
           _: New         | _: IsExpr    | _: Lambda | _: ClassTest
      =>
        throw new Exception("Unexpected " + word)

  def compile(c: Constant): V =
    c match
      case Constant.Bool(b)   => V("i1", if b then "true" else "false")
      case Constant.Byte(n)   => V("i8", (n & 0xFF).toString)
      case Constant.Char(n)   => V("i32", n.toString)
      case Constant.Int(n)    => V("i32", n.toString)
      case Constant.Long(n)   => V("i64", n.toString)
      case Constant.Float(d)  => V("double", "0x%016X".format(java.lang.Double.doubleToRawLongBits(d)))

      case Constant.String(s) =>
        V("ptr", addString(s))

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

  def compile(rec: RecordLit)(using ctx: FunContext): V =
    val recordType = rec.tpe.asRecordType
    val size = V("i32", Layout.size(recordType).toString)

    // An object in the result region is older than the current region, so
    // its fields need the barrier even when initialized. A function, like
    // the `apply` of a closure, is not a heap object.
    val inResult = ctx.placement.exists(_.allocations.contains(rec))
    val alloc = if inResult then runtime.Regions_allocResult else runtime.GC_alloc
    val obj = callFun(alloc, size :: Nil)

    for (name, rhs) <- rec.args do
      val value = compile(rhs)
      val fieldType = Layout.fieldType(recordType, name)
      val isInit = !inResult || fieldType.isProcType
      storeField(obj, Layout.fieldOffset(recordType, name), value, fieldType, isInit)

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
          val args = app.allArgs.map(compile)

          // The callee puts its result in the result region of this function
          val target = resolve(sym)
          val resultType = target.tpe.asProcType.resultType
          val inherits =
            ctx.placement.exists(_.calls.contains(app)) &&
            llvmType(resultType) == "ptr" && opensRegion(target, resultType)

          if inherits then
            callFun(runtime.Regions_inherit, Nil)

          callFun(sym, args)

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

  /** Variadic C functions and the number of their fixed parameters */
  private val variadicC = Map("snprintf" -> 3)

  /** Call a function of the C library */
  def callC(sym: Symbol, args: List[V])(using FunContext): V =
    val funType = sym.tpe.asProcType
    val paramTypes = funType.allParamTypes.map(cType)
    val resType = cType(funType.resultType)
    val noreturn = if funType.resultType.isBottomType then " noreturn" else ""

    // A variadic function is declared with its fixed parameters only
    val (declParams, callType) = variadicC.get(sym.name) match
      case Some(fixed) =>
        val params = (paramTypes.take(fixed) :+ "...").mkString(", ")
        (params, s"$resType ($params)")

      case None =>
        (paramTypes.mkString(", "), resType)

    cDecls.getOrElseUpdate(sym.name, s"declare $resType @${sym.name}($declParams)$noreturn")

    val argTexts = args.zip(paramTypes).map((arg, ty) => coerce(arg, ty).text)
    val callText = s"call $callType @${sym.name}(${argTexts.mkString(", ")})"

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

      V("ptr", paramKey(paramSym.fullName))

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

    else if sym == runtime.Regions_layoutTable then
      V("ptr", "@gc.layouts")

    else if sym == runtime.Regions_funNames then
      V("ptr", "@gc.funNames")

    else if sym == runtime.Regions_classNames then
      V("ptr", "@gc.classNames")

    else if sym == runtime.Core_intBoxLow then
      V("i32", Layout.IntBoxLow.toString)

    else if sym == runtime.Core_intBoxCount then
      V("i32", Layout.IntBoxCount.toString)

    else if sym == runtime.Core_charBoxCount then
      V("i32", Layout.CharBoxCount.toString)

    else if sym == runtime.Core_sharedIntBox then
      sharedBox(runtime.Core_IntBox, args.head)

    else if sym == runtime.Core_sharedCharBox then
      sharedBox(runtime.Core_CharBox, args.head)

    else if sym == runtime.Core_sharedByteBox then
      sharedBox(runtime.Core_ByteBox, args.head)

    else if sym == runtime.Core_sharedBoolBox then
      sharedBox(runtime.Core_BoolBox, args.head)

    else if sym == runtime.Regions_minInt then
      cDecls.getOrElseUpdate("llvm.smin.i32", "declare i32 @llvm.smin.i32(i32, i32)")
      val a :: b :: Nil = args: @unchecked
      assign("i32", s"call i32 @llvm.smin.i32(${coerce(a, "i32").text}, ${coerce(b, "i32").text})")

    else if sym == runtime.Core_bufferClassId then
      V("i32", runtime.BufferClassId.toString)

    else if sym == runtime.Core_bytesClassId then
      V("i32", runtime.BytesClassId.toString)

    else if sym == defn.Float_parseFloatDecimal then
      callFun(runtime.Core_parseFloat, args)

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

      case runtime.Core_addrToLong => coerce(args.head, "i64")

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
      case "truncateToLong" => convert("fptosi", "double", "i64", args)
      case "toBits" => convert("bitcast", "double", "i64", args)
      case "floorFinite" =>
        cDecls.getOrElseUpdate("llvm.floor.f64", "declare double @llvm.floor.f64(double)")
        assign("double", s"call double @llvm.floor.f64(${coerce(args.head, "double").text})")

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

  /** The functions that open a region besides those named by `--region`
   *
   *  - None: no other function
   *  - Scalar: functions returning a primitive value, not `Unit`
   *  - All: every function
   */
  enum RegionPolicy:
    case None, Scalar, All

  class FunContext(val fun: Symbol):
    val allocas = new StringBuilder
    val body = new StringBuilder
    var tmpCount = 0
    var labelCount = 0
    var terminated = false
    val slots = mutable.Map.empty[Symbol, Slot]
    val localReturnTargets = mutable.Map.empty[Symbol, String]

    /** Whether the function opens a region */
    var isRegion = false

    /** The allocations and calls that go to the result region */
    var placement: Option[ResultRegions] = None

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

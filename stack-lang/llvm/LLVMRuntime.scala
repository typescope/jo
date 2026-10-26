package llvm

import sast.*
import sast.Symbols.*
import sast.Types.*
import sast.Trees.*
import sast.Denotations.ClassInfo

import scala.collection.mutable

/** Runtime symbols, class ids and interface tables of the LLVM backend
  *
  * The runtime is `runtime/native/common` plus the libc platform layer
  * `runtime/native/libc`. Run-time symbols are not visible to user programs.
  */
class LLVMRuntime(val rewire: Map[Symbol, Symbol])(using defn: Definitions):
  val Native = defn.resolveContainer("jo.runtime.native")

  val Core_Addr = Native.typeMember("Addr")

  val Core_State       = Native.typeMember("State")
  val Core_state       = Native.termMember("state")
  val Core_start       = Native.termMember("start")
  val Core_parseFloat  = Native.termMember("parseFloat")
  val Core_initObjects = Native.termMember("initObjects")

  val Core_cast      = Native.termMember("cast")
  val Core_addAddr   = Native.termMember("addAddr")
  val Core_writeInt  = Native.termMember("writeInt")
  val Core_readInt   = Native.termMember("readInt")
  val Core_writeByte = Native.termMember("writeByte")
  val Core_readByte  = Native.termMember("readByte")
  val Core_readAddr  = Native.termMember("readAddr")
  val Core_writeAddr = Native.termMember("writeAddr")
  val Core_nullAddr  = Native.termMember("nullAddr")
  val Core_isNull    = Native.termMember("isNull")
  val Core_sameRef   = Native.termMember("sameRef")
  val Core_wordSize  = Native.termMember("wordSize")
  val Core_intToLong = Native.termMember("intToLong")
  val Core_addrToLong = Native.termMember("addrToLong")

  val Core_findInterfaceMethod = Native.termMember("findInterfaceMethod")
  val Core_getInterfaceTable   = Native.termMember("getInterfaceTable")
  val Core_isLambdaValue       = Native.termMember("isLambdaValue")

  // Sections for primitive operators
  val Core_BoolOps   = Native.containerMember("BoolOps")
  val Core_IntOps    = Native.containerMember("IntOps")
  val Core_ByteOps   = Native.containerMember("ByteOps")
  val Core_CharOps   = Native.containerMember("CharOps")
  val Core_FloatOps  = Native.containerMember("FloatOps")
  val Core_LongOps   = Native.containerMember("LongOps")
  val Core_StringOps = Native.containerMember("StringOps")
  val Core_RefArray  = Native.containerMember("RefArray")

  val Bool_and = Core_BoolOps.termMember("&&")
  val Bool_or  = Core_BoolOps.termMember("||")

  // Boxing classes for numeric/bool types in union types
  val Core_BoolBox    = Native.typeMember("BoolBox")
  val Core_ByteBox    = Native.typeMember("ByteBox")
  val Core_CharBox    = Native.typeMember("CharBox")
  val Core_IntBox     = Native.typeMember("IntBox")
  val Core_LongBox    = Native.typeMember("LongBox")
  val Core_FloatBox   = Native.typeMember("FloatBox")
  val Core_ClosureTag = Native.typeMember("ClosureTag")

  // Boxing class constructors (synthesized by the compiler)
  val Core_BoolBox_fun  = Native.termMember("BoolBox")
  val Core_ByteBox_fun  = Native.termMember("ByteBox")
  val Core_CharBox_fun  = Native.termMember("CharBox")
  val Core_IntBox_fun   = Native.termMember("IntBox")
  val Core_LongBox_fun  = Native.termMember("LongBox")
  val Core_FloatBox_fun = Native.termMember("FloatBox")

  val Core_bufferClassId = Native.termMember("bufferClassId")
  val Core_bytesClassId  = Native.termMember("bytesClassId")

  val Core_String_Raw    = Native.typeMember("Raw")
  val Core_String_Concat = Native.typeMember("Concat")

  val Core_RefArray_ArrayClassId = defn.resolveTerm("jo.runtime.native.RefArray.ArrayClassId")

  val Core_stackOverflowHandler        = Native.termMember("stackOverflowHandler")
  val Core_stackOverflowHandlerAddress = Native.termMember("stackOverflowHandlerAddress")

  val Regions = Native.containerMember("Regions")
  val Regions_enter       = Regions.termMember("enter")
  val Regions_leave       = Regions.termMember("leave")
  val Regions_barrier     = Regions.termMember("barrier")
  val Regions_layoutTable = Regions.termMember("layoutTable")
  val Regions_minInt      = Regions.termMember("minInt")

  val GC = defn.resolveContainer("jo.runtime.native.GC")
  val GC_alloc = GC.termMember("alloc")

  val LibC = defn.resolveContainer("jo.runtime.native.LibC")

  val ParamSupport = defn.resolveContainer("jo.runtime.native.ParamSupport")
  val ParamSupport_paramKey = ParamSupport.termMember("paramKey")

  //----------------------------------------------------------------------------
  // Class ids, interface ids and lifted methods

  private val methodToLiftedMap = mutable.Map.empty[Symbol, Symbol]
  private val classIds = mutable.Map.empty[Symbol, Int]
  private val reverseClassIds = mutable.Map.empty[Int, Symbol]
  private val interfaceIds = mutable.Map.empty[Symbol, Int]

  def getLiftedMethodOrUpdate(meth: Symbol, update: => Symbol): Symbol =
    methodToLiftedMap.getOrElseUpdate(meth, update)

  /** Class ids of heap objects that are not instances of Jo classes */
  val BufferClassId = 0
  val BytesClassId = 1
  private val FirstClassId = 2

  def getClassId(cls: Symbol): Int =
    classIds.get(cls) match
      case Some(id) => id
      case None =>
        val id = classIds.size + FirstClassId
        classIds(cls) = id
        reverseClassIds(id) = cls
        id

  def getClassSymbol(classId: Int): Symbol = reverseClassIds(classId)

  /** The number of class ids in use, including the reserved ones */
  def classIdCount: Int = classIds.size + FirstClassId

  def getInterfaceId(interface: Symbol): Int =
    interfaceIds.getOrElseUpdate(interface, interfaceIds.size)

  /** Return lifted implementation function of the given interface method in the given class
    *
    * It handles bridge methods.
    */
  def getImplementation(classInfo: ClassInfo, name: String): Symbol =
    classInfo.getMemberSymbol(name + Names.BridgeSuffix) match
      case Some(sym) => methodToLiftedMap(sym)
      case None =>
        classInfo.getMemberSymbol(name) match
          case Some(sym) => methodToLiftedMap(sym)
          case None =>
            throw new Exception(s"Implementation missing for $name in class ${classInfo.classSymbol}")

  /** The interfaces of a class with the lifted implementations of their abstract methods */
  def getInterfaceImplementations(classInfo: ClassInfo): List[(Symbol, List[Symbol])] =
    for viewType <- classInfo.views yield
      val interfaceInfo = viewType.classInfo
      val impls =
        for method <- interfaceInfo.methods if method.is(Flags.Defer) yield
          getImplementation(classInfo, method.name)

      interfaceInfo.classSymbol -> impls

  //----------------------------------------------------------------------------
  // Singleton objects

  // The object initialization function is synthesized after tranversing the
  // whole universe.
  //
  // No new functions will be reachable during initialization of the objects,
  // because their constructors are trivial, enforced by the language.
  val objectInitProcSym =
    val procType = ProcType(
      tparams = Nil,
      params = Nil,
      autos = Nil,
      resultType = defn.UnitType,
      receivesInfo = Nil,
      preParamCount = 0,
      preTypeParamCount = 0
    )

    TermSymbol.create(
      "objectInitProc",
      procType,
      Flags.Synthetic | Flags.Fun,
      visibility = Visibility.Default,
      owner = Native,
      pos = Core_initObjects.sourcePos)

  /** Map from the accessor function to the global holding the object address */
  private val accessorValueMap = mutable.LinkedHashMap.empty[Symbol, String]

  def getObjectHolder(accessor: Symbol): String =
    accessorValueMap.getOrElseUpdate(accessor, "@obj." + accessorValueMap.size)

  def objectHolders: Iterable[String] = accessorValueMap.values

  /** Map from object value symbol to the global holding the object address
    *
    * This map is created while synthesizing the function `objectInitProc` and
    * is used in lowering the synthesized function `objectInitProc`.
    */
  private val dataValueMap = mutable.Map.empty[Symbol, String]

  def getObjectHolderByDataSymbol(dataSymbol: Symbol): String =
    dataValueMap(dataSymbol)

  /** Return synthesized function that initialize singleton objects
    *
    *   def objectInitProc(): Unit =
    *     holder1 = accessor1()
    *     holder2 = accessor2()
    */
  def getObjectInitProc(): FunDef =
    val span = objectInitProcSym.sourcePos.span
    val stats = new mutable.ArrayBuffer[Word]

    for (accessor, holder) <- accessorValueMap do
      val valueType = accessor.tpe.effectiveResultType
      val holderSym = TermSymbol.create(
        holder.drop(1),
        valueType,
        Flags.Synthetic | Flags.Object,
        visibility = Visibility.Default,
        owner = Native,
        pos = Core_initObjects.sourcePos
      )

      dataValueMap(holderSym) = holder

      val id = Ident(holderSym)(span)
      val rhs = Ident(accessor)(span).appliedTo()
      stats += Assign(id, rhs)
    end for

    stats += unitValue(span)
    val body = Block(stats.toList)(span)

    FunDef(
      objectInitProcSym,
      tparams = Nil,
      params = Nil,
      autos = Nil,
      resultType = TypeTree(defn.UnitType)(span),
      effectPolicy = Effects.Policy.Infer,  // no effects needed
      body = body
    )(annots = Nil, span = span)
end LLVMRuntime

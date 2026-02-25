package native
package runtime

import sast.*
import sast.Symbols.*
import sast.Types.*
import sast.Trees.*

import native.Assembly.Label
import native.Assembler.PatchableBuffer

import scala.collection.mutable

/** Functions to support native platform at runtime
  *
  * Run-time symbols are not visible to user programs.
  */
class NativeRuntime(linkers: List[Linker], val rewire: Map[Symbol, Symbol]) (using defn: Definitions)
extends Linker:
  val itable = new InterfaceTable(this)

  /** Maps function symbols to addresses -- only reachable functions are compiled */
  val funLabelMap: mutable.Map[Symbol, Label] = mutable.Map.empty

  val Native = defn.resolveContainer("native")

  val Core_Addr = Native.typeMember("Addr")

  val Core_State       = Native.typeMember("State")
  val Core_start       = Native.termMember("start")
  val Core_initObjects = Native.termMember("initObjects")

  val Core_cast = Native.termMember("cast")
  val Core_state = Native.termMember("state")
  val Core_debug = Native.termMember("debug")

  val Core_addAddr   = Native.termMember("addAddr")
  val Core_writeInt  = Native.termMember("writeInt")
  val Core_readInt   = Native.termMember("readInt")
  val Core_writeByte = Native.termMember("writeByte")
  val Core_readByte  = Native.termMember("readByte")

  val Core_findInterfaceMethod = Native.termMember("findInterfaceMethod")
  val Core_getInterfaceTable   = Native.termMember("getInterfaceTable")

  // Sections for primitive operators
  val Core_BoolOps  = Native.containerMember("BoolOps")
  val Core_IntOps   = Native.containerMember("IntOps")
  val Core_ByteOps  = Native.containerMember("ByteOps")
  val Core_CharOps  = Native.containerMember("CharOps")
  val Core_FloatOps = Native.containerMember("FloatOps")
  val Core_StringOps = Native.containerMember("StringOps")

  // Bool primitive operators (defined in section BoolOps in Native.jo)
  val Bool_and = Core_BoolOps.termMember("&&")
  val Bool_or  = Core_BoolOps.termMember("||")
  val Bool_eq  = Core_BoolOps.termMember("==")
  val Bool_ne  = Core_BoolOps.termMember("!=")
  val Bool_not = Core_BoolOps.termMember("~!")

  // Int primitive operators (defined in section IntOps in Native.jo)
  val Int_add  = Core_IntOps.termMember("+")
  val Int_sub  = Core_IntOps.termMember("-")
  val Int_mul  = Core_IntOps.termMember("*")
  val Int_div  = Core_IntOps.termMember("/")
  val Int_mod  = Core_IntOps.termMember("%")
  val Int_gt   = Core_IntOps.termMember(">")
  val Int_lt   = Core_IntOps.termMember("<")
  val Int_ge   = Core_IntOps.termMember(">=")
  val Int_le   = Core_IntOps.termMember("<=")
  val Int_eq   = Core_IntOps.termMember("==")
  val Int_ne   = Core_IntOps.termMember("!=")
  val Int_srl  = Core_IntOps.termMember(">>")
  val Int_sll  = Core_IntOps.termMember("<<")
  val Int_land = Core_IntOps.termMember("&")
  val Int_lor  = Core_IntOps.termMember("|")
  val Int_lxor = Core_IntOps.termMember("^")
  val Int_toChar = Core_IntOps.termMember("toChar")
  val Int_toByte = Core_IntOps.termMember("toByte")
  val Int_toFloat = Core_IntOps.termMember("toFloat")
  val Int_neg    = Core_IntOps.termMember("~-")

  // Byte primitive operators (defined in section ByteOps in Native.jo)
  val Byte_eq = Core_ByteOps.termMember("==")
  val Byte_ne = Core_ByteOps.termMember("!=")
  val Byte_gt = Core_ByteOps.termMember(">")
  val Byte_lt = Core_ByteOps.termMember("<")
  val Byte_ge = Core_ByteOps.termMember(">=")
  val Byte_le = Core_ByteOps.termMember("<=")
  val Byte_toInt = Core_ByteOps.termMember("toInt")
  val Byte_toChar = Core_ByteOps.termMember("toChar")
  val Byte_toFloat = Core_ByteOps.termMember("toFloat")

  // Char primitive operators (defined in section CharOps in Native.jo)
  val Char_eq = Core_CharOps.termMember("==")
  val Char_ne = Core_CharOps.termMember("!=")
  val Char_gt = Core_CharOps.termMember(">")
  val Char_lt = Core_CharOps.termMember("<")
  val Char_ge = Core_CharOps.termMember(">=")
  val Char_le = Core_CharOps.termMember("<=")
  val Char_toByte = Core_CharOps.termMember("toByte")
  val Char_toInt = Core_CharOps.termMember("toInt")
  val Char_toFloat = Core_CharOps.termMember("toFloat")

  // Float primitive operators (defined in section FloatOps in Native.jo)
  val Float_add = Core_FloatOps.termMember("+")
  val Float_sub = Core_FloatOps.termMember("-")
  val Float_mul = Core_FloatOps.termMember("*")
  val Float_div = Core_FloatOps.termMember("/")
  val Float_gt  = Core_FloatOps.termMember(">")
  val Float_lt  = Core_FloatOps.termMember("<")
  val Float_ge  = Core_FloatOps.termMember(">=")
  val Float_le  = Core_FloatOps.termMember("<=")
  val Float_eq  = Core_FloatOps.termMember("==")
  val Float_ne  = Core_FloatOps.termMember("!=")
  val Float_toInt = Core_FloatOps.termMember("toInt")
  val Float_neg = Core_FloatOps.termMember("~-")

  // Boxing classes for numeric/bool types in union types
  val Core_BoolBox = Native.typeMember("BoolBox")
  val Core_ByteBox = Native.typeMember("ByteBox")
  val Core_CharBox = Native.typeMember("CharBox")
  val Core_IntBox = Native.typeMember("IntBox")
  val Core_FloatBox = Native.typeMember("FloatBox")

  // Boxing class constructors (synthesized by the compiler)
  val Core_BoolBox_fun = Native.termMember("BoolBox")
  val Core_ByteBox_fun = Native.termMember("ByteBox")
  val Core_CharBox_fun = Native.termMember("CharBox")
  val Core_IntBox_fun = Native.termMember("IntBox")
  val Core_FloatBox_fun = Native.termMember("FloatBox")

  val Core_String_fromByteString = Core_StringOps.termMember("fromByteString")

  val Core_String_Raw = Native.typeMember("Raw")
  val Core_String_Concat = Native.typeMember("Concat")

  val GC = defn.resolveContainer("native.GC")
  val GC_alloc = GC.termMember("alloc")

  val ParamSupport = defn.resolveContainer("native.ParamSupport")
  val ParamSupport_getParam = ParamSupport.termMember("getParam")
  val ParamSupport_setParam = ParamSupport.termMember("setParam")
  val ParamSupport_getLastOverwrittenValue = ParamSupport.termMember("getLastOverwrittenValue")
  val ParamSupport_restoreParam = ParamSupport.termMember("restoreParam")
  val ParamSupport_readValueAt = ParamSupport.termMember("readValueAt")
  val ParamSupport_getParamIndex = ParamSupport.termMember("getParamIndex")

  val runtimeStateLabel = Label("runtimeState")

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
      candidates = Nil,
      resultType = defn.UnitType,
      receivesInfo = Nil,
      preParamCount = 0,
      preTypeParamCount = 0
    )()

    TermSymbol.create(
      "objectInitProc",
      procType,
      Flags.Synthetic | Flags.Fun,
      visibility = Visibility.Default,
      owner = Native,
      pos = Core_initObjects.sourcePos)

  /** Map from the accessor function to holder address of object address */
  private val accessorValueMap: mutable.Map[Symbol, Label] = mutable.Map.empty

  def getObjectHolder(accessor: Symbol): Label =
    accessorValueMap.getOrElseUpdate(accessor, Label(accessor.name))

  /** Map from object value symbol to holder address of object address
    *
    * This map is created while synthesizing the function `objectInitProc` and
    * is used in lowering the synthesized function `objectInitProc`.
    */
  private val dataValueMap: mutable.Map[Symbol, Label] = mutable.Map.empty

  def getObjectHolderByDataSymbol(dataSymbol: Symbol): Label =
    dataValueMap(dataSymbol)

  /** Return synthesized function that initialize singleton objects
    *
    *   def objectInitProc(): Unit =
    *     label1 = accessor1()
    *     label2 = accessor2()
    *
    * The data label and accessor comes from `accessorValueMap`.
    */
  def getObjectInitProc(): FunDef =
    val span = objectInitProcSym.sourcePos.span
    val stats = new mutable.ArrayBuffer[Word]

    for (accessor, label) <- accessorValueMap do
      val valueType = accessor.info.effectiveResultType
      val labelSym = TermSymbol.create(
        label.name,
        valueType,
        Flags.Synthetic | Flags.Object,
        visibility = Visibility.Default,
        owner = Native,
        pos = Core_initObjects.sourcePos
      )

      dataValueMap(labelSym) = label

      val id = Ident(labelSym)(span)
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
      candidates = Nil,
      resultType = TypeTree(defn.UnitType)(span),
      effectPolicy = Effects.Policy.Infer,  // no effects needed
      body = body
    )(span)

  def locate(sym: Symbol): Option[Label] =
    val iter = linkers.iterator
    while iter.hasNext do
      val linker = iter.next()
      linker.locate(sym) match
        case None =>
        case res => return res

    None

  def linkData()(using pb: PatchableBuffer): Unit =
    pb.align(4)
    pb.defineLabel(runtimeStateLabel)
    val size = Memory.classInstanceSize(Core_State)
    for _ <- 1 to size do pb.addByte(0)

    // singleton objects
    for dataAddressLabel <- accessorValueMap.values do
      pb.align(4)
      pb.defineLabel(dataAddressLabel)
      // object references are 4 bytes
      pb.addInt(0)

    itable.lowerInterfaceTable()

    linkers.foreach(_.linkData())

  def linkCode()(using pb: PatchableBuffer): Unit =
    linkers.foreach(_.linkCode())

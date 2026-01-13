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
  val Core = defn.resolveContainer("jo.runtime.native.Core")

  val Core_Addr = Core.typeMember("Addr")

  val Core_start       = Core.termMember("start")
  val Core_initObjects = Core.termMember("initObjects")

  val Core_cast = Core.termMember("cast")
  val Core_state = Core.termMember("state")
  val Core_debug = Core.termMember("debug")

  val Core_addAddr   = Core.termMember("addAddr")
  val Core_writeInt  = Core.termMember("writeInt")
  val Core_readInt   = Core.termMember("readInt")
  val Core_writeByte = Core.termMember("writeByte")
  val Core_readByte  = Core.termMember("readByte")
  val Core_findInterfaceMethod = Core.termMember("findInterfaceMethod")

  // Sections for primitive operators
  val Core_IntOps   = Core.containerMember("IntOps")
  val Core_ByteOps  = Core.containerMember("ByteOps")
  val Core_CharOps  = Core.containerMember("CharOps")
  val Core_FloatOps = Core.containerMember("FloatOps")

  // Int primitive operators (defined in section IntOps in Core.jo)
  val Core_Int_add  = Core_IntOps.termMember("+")
  val Core_Int_sub  = Core_IntOps.termMember("-")
  val Core_Int_mul  = Core_IntOps.termMember("*")
  val Core_Int_div  = Core_IntOps.termMember("/")
  val Core_Int_mod  = Core_IntOps.termMember("%")
  val Core_Int_gt   = Core_IntOps.termMember(">")
  val Core_Int_lt   = Core_IntOps.termMember("<")
  val Core_Int_ge   = Core_IntOps.termMember(">=")
  val Core_Int_le   = Core_IntOps.termMember("<=")
  val Core_Int_eq   = Core_IntOps.termMember("==")
  val Core_Int_ne   = Core_IntOps.termMember("!=")
  val Core_Int_srl  = Core_IntOps.termMember(">>")
  val Core_Int_sll  = Core_IntOps.termMember("<<")
  val Core_Int_land = Core_IntOps.termMember("&")
  val Core_Int_lor  = Core_IntOps.termMember("|")
  val Core_Int_lxor = Core_IntOps.termMember("^")
  val Core_Int_toChar = Core_IntOps.termMember("toChar")
  val Core_Int_toByte = Core_IntOps.termMember("toByte")
  val Core_Int_toFloat = Core_IntOps.termMember("toFloat")

  // Byte primitive operators (defined in section ByteOps in Core.jo)
  val Core_Byte_eq = Core_ByteOps.termMember("==")
  val Core_Byte_ne = Core_ByteOps.termMember("!=")
  val Core_Byte_gt = Core_ByteOps.termMember(">")
  val Core_Byte_lt = Core_ByteOps.termMember("<")
  val Core_Byte_ge = Core_ByteOps.termMember(">=")
  val Core_Byte_le = Core_ByteOps.termMember("<=")
  val Core_Byte_toInt = Core_ByteOps.termMember("toInt")
  val Core_Byte_toChar = Core_ByteOps.termMember("toChar")
  val Core_Byte_toFloat = Core_ByteOps.termMember("toFloat")

  // Char primitive operators (defined in section CharOps in Core.jo)
  val Core_Char_eq = Core_CharOps.termMember("==")
  val Core_Char_ne = Core_CharOps.termMember("!=")
  val Core_Char_gt = Core_CharOps.termMember(">")
  val Core_Char_lt = Core_CharOps.termMember("<")
  val Core_Char_ge = Core_CharOps.termMember(">=")
  val Core_Char_le = Core_CharOps.termMember("<=")
  val Core_Char_toByte = Core_CharOps.termMember("toByte")
  val Core_Char_toInt = Core_CharOps.termMember("toInt")
  val Core_Char_toFloat = Core_CharOps.termMember("toFloat")

  // Float primitive operators (defined in section FloatOps in Core.jo)
  val Core_Float_add = Core_FloatOps.termMember("+")
  val Core_Float_sub = Core_FloatOps.termMember("-")
  val Core_Float_mul = Core_FloatOps.termMember("*")
  val Core_Float_div = Core_FloatOps.termMember("/")
  val Core_Float_gt  = Core_FloatOps.termMember(">")
  val Core_Float_lt  = Core_FloatOps.termMember("<")
  val Core_Float_ge  = Core_FloatOps.termMember(">=")
  val Core_Float_le  = Core_FloatOps.termMember("<=")
  val Core_Float_eq  = Core_FloatOps.termMember("==")
  val Core_Float_ne  = Core_FloatOps.termMember("!=")
  val Core_Float_toInt = Core_FloatOps.termMember("toInt")

  // Boxing classes for numeric types in union types
  val Core_ByteBox = Core.typeMember("ByteBox")
  val Core_CharBox = Core.typeMember("CharBox")
  val Core_IntBox = Core.typeMember("IntBox")
  val Core_FloatBox = Core.typeMember("FloatBox")

  // Boxing class constructors (synthesized by the compiler)
  val Core_ByteBox_fun = Core.termMember("ByteBox")
  val Core_CharBox_fun = Core.termMember("CharBox")
  val Core_IntBox_fun = Core.termMember("IntBox")
  val Core_FloatBox_fun = Core.termMember("FloatBox")

  val Core_String_fromByteString = Core.termMember("String_fromByteString")
  val Core_String_size           = Core.termMember("String_size")
  val Core_String_apply          = Core.termMember("String_apply")
  val Core_String_plus           = Core.termMember("String_plus")
  val Core_String_substring      = Core.termMember("String_substring")
  val Core_String_equals         = Core.termMember("String_equals")

  val Core_String_Raw = Core.typeMember("Raw")
  val Core_String_Concat = Core.typeMember("Concat")

  val GC = defn.resolveContainer("jo.runtime.native.GC")
  val GC_alloc = GC.termMember("alloc")

  val ParamSupport = defn.resolveContainer("jo.runtime.native.ParamSupport")
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
      preParamCount = 0
    )

    TermSymbol.create(
      "objectInitProc",
      procType,
      Flags.Synthetic | Flags.Fun,
      visibility = Visibility.Default,
      owner = Core,
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
        owner = Core,
        pos = Core_initObjects.sourcePos
      )

      dataValueMap(labelSym) = label

      val id = Ident(labelSym)(span)
      val rhs = Ident(accessor)(span).appliedTo()
      stats += Assign(id, rhs)
    end for

    stats += Ident(defn.Predef_Unit_def)(span).appliedTo()
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
    pb.addInt(0) // class id
    pb.addInt(0) // gc from
    pb.addInt(0) // gc to
    pb.addInt(0) // paramsuport.state

    // singleton objects
    for dataAddressLabel <- accessorValueMap.values do
      pb.align(4)
      pb.defineLabel(dataAddressLabel)
      // object references are 4 bytes
      pb.addInt(0)

    linkers.foreach(_.linkData())

  def linkCode()(using pb: PatchableBuffer): Unit =
    linkers.foreach(_.linkCode())

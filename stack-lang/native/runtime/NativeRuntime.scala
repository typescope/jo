package native
package runtime

import sast.*
import sast.Symbols.*

import native.Assembly.Label
import native.Assembler.PatchableBuffer

/** Functions to support native platform at runtime
  *
  * Run-time symbols are not visible to user programs.
  */
class NativeRuntime(linkers: List[Linker], val rewire: Map[Symbol, Symbol]) (using defn: Definitions)
extends Linker:
  val Core = defn.resolveContainer("jo.runtime.native.Core")

  val Core_Addr = Core.typeMember("Addr")

  val Core_start    = Core.termMember("start")

  val Core_cast = Core.termMember("cast")
  val Core_state = Core.termMember("state")
  val Core_debug = Core.termMember("debug")

  val Core_addAddr   = Core.termMember("addAddr")
  val Core_writeInt  = Core.termMember("writeInt")
  val Core_readInt   = Core.termMember("readInt")
  val Core_writeByte = Core.termMember("writeByte")
  val Core_readByte  = Core.termMember("readByte")

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

  // Byte primitive operators (defined in section ByteOps in Core.jo)
  val Core_Byte_eq = Core_ByteOps.termMember("==")
  val Core_Byte_ne = Core_ByteOps.termMember("!=")
  val Core_Byte_gt = Core_ByteOps.termMember(">")
  val Core_Byte_lt = Core_ByteOps.termMember("<")
  val Core_Byte_ge = Core_ByteOps.termMember(">=")
  val Core_Byte_le = Core_ByteOps.termMember("<=")

  // Char primitive operators (defined in section CharOps in Core.jo)
  val Core_Char_eq = Core_CharOps.termMember("==")
  val Core_Char_ne = Core_CharOps.termMember("!=")
  val Core_Char_gt = Core_CharOps.termMember(">")
  val Core_Char_lt = Core_CharOps.termMember("<")
  val Core_Char_ge = Core_CharOps.termMember(">=")
  val Core_Char_le = Core_CharOps.termMember("<=")

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

  def locate(sym: Symbol): Option[Label] =
    val iter = linkers.iterator
    while iter.hasNext do
      val linker = iter.next()
      linker.locate(sym) match
        case None =>
        case res => return res

    None

  def linkData()(using pb: PatchableBuffer): Unit =
    pb.defineLabel(runtimeStateLabel)
    pb.align(4)
    pb.addInt(0) // class id
    pb.addInt(0) // gc from
    pb.addInt(0) // gc to
    pb.addInt(0) // paramsuport.state

    linkers.foreach(_.linkData())

  def linkCode()(using pb: PatchableBuffer): Unit =
    linkers.foreach(_.linkCode())

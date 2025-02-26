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
class NativeRuntime(
  runtimeRootNameTable: NameTable, linkers: List[Linker], userMain: Symbol)
extends Linker:
  import runtimeRootNameTable.resolvePath

  val defn = Definitions.instance

  val Core = resolvePath("stk.runtime.native.Core")

  val Core_Addr = Core.typeMember("Addr")

  val Core_start    = Core.termMember("start")
  val Core_mainStub = Core.termMember("mainStub")

  val Core_cast = Core.termMember("cast")
  val Core_data = Core.termMember("dataAddr")

  val Core_addAddr   = Core.termMember("addAddr")
  val Core_writeInt  = Core.termMember("writeInt")
  val Core_readInt   = Core.termMember("readInt")
  val Core_writeByte = Core.termMember("writeByte")
  val Core_readByte  = Core.termMember("readByte")

  val Core_Array_create = Core.termMember("Array_create")
  val Core_Array_length = Core.termMember("Array_length")
  val Core_Array_apply  = Core.termMember("Array_apply")
  val Core_Array_set    = Core.termMember("Array_set")

  val Core_String_fromByteString = Core.termMember("String_fromByteString")
  val Core_String_length         = Core.termMember("String_length")
  val Core_String_apply          = Core.termMember("String_apply")
  val Core_String_plus           = Core.termMember("String_plus")
  val Core_String_substring      = Core.termMember("String_substring")

  val Core_abortImpl = Core.termMember("abortImpl")

  val Core_byteToChar = Core.termMember("byteToChar")
  val Core_byteToInt  = Core.termMember("byteToInt")
  val Core_charToByte = Core.termMember("charToByte")
  val Core_charToInt  = Core.termMember("charToInt")
  val Core_charToStr  = Core.termMember("charToStr")
  val Core_intToByte  = Core.termMember("intToByte")
  val Core_intToChar  = Core.termMember("intToChar")
  val Core_intToStr   = Core.termMember("intToStr")

  val Core_openFile = Core.termMember("openFile")
  val Core_createStdIn = Core.termMember("createStdIn")
  val Core_createStdOut = Core.termMember("createStdOut")
  val Core_createStdErr = Core.termMember("createStdErr")

  val GC = resolvePath("stk.runtime.native.GC")
  val GC_alloc = GC.termMember("alloc")

  val ParamSupport = resolvePath("stk.runtime.native.ParamSupport")
  val ParamSupport_getParam = ParamSupport.termMember("getParam")
  val ParamSupport_setParam = ParamSupport.termMember("setParam")
  val ParamSupport_getLastOverwrittenValue = ParamSupport.termMember("getLastOverwrittenValue")
  val ParamSupport_restoreParam = ParamSupport.termMember("restoreParam")
  val ParamSupport_readValueAt = ParamSupport.termMember("readValueAt")
  val ParamSupport_getParamIndex = ParamSupport.termMember("getParamIndex")

  val paramSupportStateLabel = Label("paramSupportState")

  def locate(sym: Symbol): Option[Label | Symbol] =
    if sym == Core_mainStub then return Some(userMain)

    val iter = linkers.iterator
    while iter.hasNext do
      val linker = iter.next()
      linker.locate(sym) match
        case None =>
        case res => return res

    None

  def locate(qualid: String): Option[Label] =
    if qualid == "stk.runtime.native.ParamSupport.state" then
      return Some(paramSupportStateLabel)

    val iter = linkers.iterator
    while iter.hasNext do
      val linker = iter.next()
      linker.locate(qualid) match
        case None =>
        case res => return res

    None

  def linkData()(using pb: PatchableBuffer): Unit =
    pb.defineLabel(paramSupportStateLabel)
    pb.align(4)
    pb.addInt(0)

    linkers.foreach(_.linkData())

  def linkCode()(using pb: PatchableBuffer): Unit =
    linkers.foreach(_.linkCode())

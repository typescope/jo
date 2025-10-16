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
  val Core = defn.resolveTermByPath("jo.runtime.native.Core")

  val Core_Addr = Core.typeMember("Addr")

  val Core_start    = Core.termMember("start")

  val Core_cast = Core.termMember("cast")
  val Core_data = Core.termMember("dataAddr")
  val Core_debug = Core.termMember("debug")

  val Core_addAddr   = Core.termMember("addAddr")
  val Core_writeInt  = Core.termMember("writeInt")
  val Core_readInt   = Core.termMember("readInt")
  val Core_writeByte = Core.termMember("writeByte")
  val Core_readByte  = Core.termMember("readByte")

  val Core_String_fromByteString = Core.termMember("String_fromByteString")
  val Core_String_size           = Core.termMember("String_size")
  val Core_String_apply          = Core.termMember("String_apply")
  val Core_String_plus           = Core.termMember("String_plus")
  val Core_String_substring      = Core.termMember("String_substring")
  val Core_String_equals         = Core.termMember("String_equals")

  val GC = defn.resolveTermByPath("jo.runtime.native.GC")
  val GC_alloc = GC.termMember("alloc")

  val ParamSupport = defn.resolveTermByPath("jo.runtime.native.ParamSupport")
  val ParamSupport_getParam = ParamSupport.termMember("getParam")
  val ParamSupport_setParam = ParamSupport.termMember("setParam")
  val ParamSupport_getLastOverwrittenValue = ParamSupport.termMember("getLastOverwrittenValue")
  val ParamSupport_restoreParam = ParamSupport.termMember("restoreParam")
  val ParamSupport_readValueAt = ParamSupport.termMember("readValueAt")
  val ParamSupport_getParamIndex = ParamSupport.termMember("getParamIndex")

  val paramSupportStateLabel = Label("paramSupportState")

  def locate(sym: Symbol): Option[Label] =
    val iter = linkers.iterator
    while iter.hasNext do
      val linker = iter.next()
      linker.locate(sym) match
        case None =>
        case res => return res

    None

  def locate(qualid: String): Option[Label] =
    if qualid == "jo.runtime.native.ParamSupport.state" then
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

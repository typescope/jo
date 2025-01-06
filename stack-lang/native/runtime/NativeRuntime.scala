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
class NativeRuntime(runtimeRootNameTable: NameTable, linkers: List[Linker])
extends Linker:
  import runtimeRootNameTable.resolvePath

  val defn = Definitions.instance

  val Core = resolvePath("stk.runtime.native.Core")

  val Core_alloc = Core.termMember("alloc")

  val Core_cast = Core.termMember("cast")
  val Core_data = Core.termMember("data")

  val Core_addAddr = Core.termMember("addAddr")
  val Core_writeInt = Core.termMember("writeInt")
  val Core_readInt = Core.termMember("readInt")
  val Core_writeByte = Core.termMember("writeByte")
  val Core_readByte = Core.termMember("readByte")

  val Core_print = Core.termMember("print")
  val Core_p = Core.termMember("p")
  val Core_finish = Core.termMember("finish")
  val Core_abortImpl = Core.termMember("abortImpl")

  def locate(sym: Symbol): Option[Label | Symbol] =
    if sym.owner == defn.Predef then
      if sym == defn.Predef_print then return Some(Core_print)
      else if sym == defn.Predef_p then return Some(Core_p)
      else if sym == defn.Predef_abort then return Some(Core_abortImpl)

    val iter = linkers.iterator
    while iter.hasNext do
      val linker = iter.next()
      linker.locate(sym) match
        case None =>
        case res => return res

    None

  def locate(qualid: String): Option[Label] =
    val iter = linkers.iterator
    while iter.hasNext do
      val linker = iter.next()
      linker.locate(qualid) match
        case None =>
        case res => return res

    None

  def linkData()(using pb: PatchableBuffer): Unit =
    linkers.foreach(_.linkData())

  def linkCode()(using pb: PatchableBuffer): Unit =
    linkers.foreach(_.linkCode())

  def inits(): List[Symbol] = linkers.flatMap(_.inits())

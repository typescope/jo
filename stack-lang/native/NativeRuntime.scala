package native

import sast.*
import sast.Symbols.*
import sast.Types.*

import Assembly.Label

/** Functions to support native platform at runtime
  *
  * Run-time symbols are not visible to user programs.
  */
class NativeRuntime(runtimeRootNameTable: NameTable, linkers: List[Linker])
extends Linker:

  val defn = Definitions.instance

  def resolveNamespace(path: String): Symbol =
    NameTable.resolvePath(runtimeRootNameTable, path, isType = false)

  val Core = resolveNamespace("stk.runtime.native.Core")
  val Core_addAddr = Core.termMember("addAddr")
  val Core_as = Core.termMember("as")

  val Core_alloc = Core.termMember("alloc")

  val Core_data = Core.termMember("data")

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
    while iter.hasNext then
      val linker = iter.next()
      linker.locate(sym)) match
        case None =>
        case res => return res

  def locate(qualid: String): Option[Label] =
    val iter = linkers.iterator
    while iter.hasNext then
      val linker = iter.next()
      linker.locate(qualid)) match
        case None =>
        case res => return res

  def linkData()(using pb: PatchableBuffer): Unit =
    linkers.foreach(_.linkData())

  def linkCode()(using pb: PatchableBuffer): Unit =
    linkers.foreach(_.linkCode())

object NativeRuntime:
  val key = new Dynamic.Key[NativeRuntime]("native-runtime")

  def initialize(runtimeRootNameTable: NameTable): Unit =
    val nativeRuntime = new NativeRuntime(runtimeRootNameTable)
    Dynamic.install(NativeRuntime.key, nativeRuntime)

  def instance: NativeRuntime = Dynamic.get(NativeRuntime.key)

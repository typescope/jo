package native.os

import sast.NameTable
import sast.Symbols.*

import native.Assembly.Label
import native.Assembler.Linker
import native.Assembler.PatchableBuffer

class LinuxBumpAllocator(runtimeRootNameTable: NameTable)
extends Linker:
  val allocatorStateLabel = Label("allocatorState")

  def resolveNamespace(path: String) =
    NameTable.resolvePath(runtimeRootNameTable, path, isType = false)

  val BumpAllocator = resolveNamespace("stk.runtime.native.BumpAllocator")
  val BumpAllocator_getState = BumpAllocator.termMember("getState")
  val BumpAllocator_init = BumpAllocator.termMember("init")
  val BumpAllocator_alloc = BumpAllocator.termMember("alloc")

  def linkData()(using pb: PatchableBuffer): Unit =
    pb.defineLabel(allocatorStateLabel)
    pb.align(4)
    pb.addInt(0)

  def linkCode()(using pb: PatchableBuffer): Unit = ()

  def locate(sym: Symbol): Option[Symbol] =
    if sym == NativeRuntime.instance.Core_alloc then
      Some(BumpAllocator_alloc)
    else
      None

  def locate(qualid: String): Option[Label] =
    if qualid == "stk.runtime.native.BumpAllocator.state" then
      Some(allocatorStateLabel)
    else
      None

  def inits(): List[Symbol] = BumpAllocator_init :: Nil

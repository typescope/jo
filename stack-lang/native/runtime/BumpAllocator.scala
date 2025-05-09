package native.runtime

import sast.Definitions
import sast.NameTable
import sast.Symbols.*

import native.Assembly.Label
import native.Assembler.PatchableBuffer
import native.Linker

class BumpAllocator(runtimeRootNameTable: NameTable)(using Definitions)
extends Linker:
  val allocatorStateLabel = Label("allocatorState")

  import runtimeRootNameTable.resolveTermByPath

  val GC = resolveTermByPath("stk.runtime.native.GC")
  val GC_alloc = GC.termMember("alloc")
  val GC_init = GC.termMember("init")

  val BumpAllocator = resolveTermByPath("stk.runtime.native.BumpAllocator")
  val BumpAllocator_init = BumpAllocator.termMember("init")
  val BumpAllocator_alloc = BumpAllocator.termMember("alloc")

  def linkData()(using pb: PatchableBuffer): Unit =
    pb.defineLabel(allocatorStateLabel)
    pb.align(4)
    pb.addInt(0)
    pb.addInt(0)

  def linkCode()(using pb: PatchableBuffer): Unit = ()

  def locate(sym: Symbol): Option[Symbol] =
    if sym == GC_alloc then
      Some(BumpAllocator_alloc)

    else if sym == GC_init then
      Some(BumpAllocator_init)

    else
      None

  def locate(qualid: String): Option[Label] =
    if qualid == "stk.runtime.native.BumpAllocator.state" then
      Some(allocatorStateLabel)
    else
      None

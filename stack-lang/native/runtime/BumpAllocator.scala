package native.runtime

import sast.Symbols.*

import native.Assembly.Label
import native.Assembler.PatchableBuffer
import native.Linker

class BumpAllocator extends Linker:
  val allocatorStateLabel = Label("allocatorState")

  def linkData()(using pb: PatchableBuffer): Unit =
    pb.defineLabel(allocatorStateLabel)
    pb.align(4)
    pb.addInt(0)
    pb.addInt(0)

  def linkCode()(using pb: PatchableBuffer): Unit = ()

  def locate(sym: Symbol): Option[Label] = None

  def locate(qualid: String): Option[Label] =
    if qualid == "jo.runtime.native.BumpAllocator.state" then
      Some(allocatorStateLabel)
    else
      None

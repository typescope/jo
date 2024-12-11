package native

import Assembler.PatchableBuffer

/**
  * The linker performs linking of all external machine code dependencies.
  */
trait Linker:
  /** Link the data part */
  def linkData()(using pb: PatchableBuffer): Unit

  /** Link the code part
    *
    * TODO: selective linking based on reachability.
    */
  def linkCode()(using pb: PatchableBuffer): Unit

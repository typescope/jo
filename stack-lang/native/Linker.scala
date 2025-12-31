package native

import sast.Symbols.Symbol
import native.Assembler.PatchableBuffer
import native.Assembly.Label

/**
  * The linker performs linking of all external machine code dependencies.
  */
trait Linker:
  /** Link the data part */
  def linkData()(using pb: PatchableBuffer): Unit

  /** Link the code part */
  def linkCode()(using pb: PatchableBuffer): Unit

  /** Locate the address of a symbol linked by the linker
    *
    * If the symbol is not handled by the current linker, return None.
    * Otherwise, return the address of the symbol or redirect to another symbol.
    *
    * The redirection mechanism allows linker to provide concrete implementation
    * to an unimplemented contract function.
    */
  def locate(sym: Symbol): Option[Label]

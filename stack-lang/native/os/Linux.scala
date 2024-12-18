/************************************************************************
 *                                                                      *
 * Encapsulates platforms on Linux                                      *
 *                                                                      *
 * - x86                                                                *
 * - x64                                                                *
 *                                                                      *
 ************************************************************************/
package native.os

import native.Assembler
import native.Assembly.*
import native.ELF32
import native.Linker

import native.cpu.X86

object Linux:
  val PAGE_SIZE  = 0x1000
  val PROG_START = 0x08048000

  val x86RegConfig = new RegisterConfig:
    val FREE_REGS = List(X86.EAX, X86.EBX, X86.ECX, X86.EDX, X86.ESI, X86.EDI)
    val SP_REG = X86.ESP
    val FP_REG = X86.EBP

  def lower(prog: Prog, layoutName: String, outFile: String, assembler: Assembler, linker: Linker): Unit =
    val layout = Assembler.continuousLayout(layoutName, PROG_START, PAGE_SIZE)
    val elf = new ELF32(outFile, layout, ELF32.EM_386)
    Assembler.lower(elf, prog, assembler, linker)

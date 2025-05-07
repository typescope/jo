/************************************************************************
 *                                                                      *
 * Encapsulates platforms on Linux                                      *
 *                                                                      *
 * - x86                                                                *
 * - x64                                                                *
 *                                                                      *
 ************************************************************************/
package native.os

import sast.NameTable
import sast.Definitions
import sast.Symbols.Symbol

import native.Assembler
import native.Assembler.PatchableBuffer
import native.Assembly.*
import native.ELF32
import native.Linker

import native.arch.X86

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
    // println(prog.show)
    Assembler.lower(elf, prog, assembler, linker)

  def createSyscallRegister(runtimeRootNameTable: NameTable)(using Definitions): LinuxSyscall =
    new LinuxSyscall(runtimeRootNameTable):
      /**
        * Implement syscalls in machine code.
        *
        * It assumes the call convention of register machines is the same as syscalls.
        */
      def linkSyscall(symbol: Symbol, label: Label)(using pb: PatchableBuffer): Unit =
        pb.defineLabel(label)

        // frame pointer must be set and untact
        X86.move(Reg(X86.ESP), X86.EBP)

        X86.int80()

        // result of syscall in EAX

        // return to caller
        X86.load(Reg(X86.ESP), X86.ESP, Size.B32)
        X86.jump(Reg(X86.ESP))

  def createSyscallStack(runtimeRootNameTable: NameTable)(using Definitions): LinuxSyscall =
    new LinuxSyscall(runtimeRootNameTable):
      /**
        * Implement syscalls in machine code.
        *
        * The input arguments are in the call convention of stack machine.
        */
      def linkSyscall(symbol: Symbol, label: Label)(using pb: PatchableBuffer): Unit =
        val procType = symbol.info.asProcType
        val paramCount = procType.paramCount

        assert(paramCount <= 4, "paraCount = " + paramCount + " for " + symbol)
        val regs = Array(X86.EAX, X86.EBX, X86.ECX, X86.EDX)

        pb.defineLabel(label)

        // frame pointer must be set and untact
        X86.move(Reg(X86.ESP), X86.EBP)

        // load argument
        for i <- 0 until paramCount do
          val reg = regs(i)
          val loc = Rel(X86.ESP, (paramCount - i - 1) * 4 + 8)
          X86.load(loc, reg, Size.B32)

        X86.int80()

        // copy EAX to result location
        X86.store(Reg(X86.EAX), Rel(X86.ESP, -4))

        // return to caller
        X86.load(Reg(X86.ESP), X86.EAX, Size.B32)
        X86.jump(Reg(X86.EAX))

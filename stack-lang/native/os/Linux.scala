/************************************************************************
 *                                                                      *
 * Encapsulates platforms on Linux                                      *
 *                                                                      *
 * - x86                                                                *
 * - x64                                                                *
 *                                                                      *
 ************************************************************************/
package native.os

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

  def createSyscallRegister()(using Definitions): LinuxSyscall =
    new LinuxSyscall():
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

  def createSyscallStack()(using Definitions): LinuxSyscall =
    new LinuxSyscall():
      /**
        * Implement syscalls in machine code.
        *
        * The input arguments are in the call convention of stack machine.
        *
        * Supports up to 6 arguments (EAX-EDI).  When args 5/6 are needed,
        * ESI/EDI are saved on the stack before loading and restored after.
        * All addressing uses EBP (= original ESP) so it stays valid after
        * any push/pop.
        */
      def linkSyscall(symbol: Symbol, label: Label)(using pb: PatchableBuffer): Unit =
        val procType = symbol.tpe.asProcType
        val paramCount = procType.paramCount

        val regs = Array(X86.EAX, X86.EBX, X86.ECX, X86.EDX, X86.ESI, X86.EDI)
        assert(paramCount <= regs.length, "paramCount = " + paramCount + " for " + symbol)

        pb.defineLabel(label)

        // EBP = original ESP; stays stable across push/pop below
        X86.move(Reg(X86.ESP), X86.EBP)

        // Save ESI/EDI if needed as syscall arg registers
        if paramCount > 4 then X86.push(X86.ESI)
        if paramCount > 5 then X86.push(X86.EDI)

        // Load args from caller frame using EBP-relative addressing
        for i <- 0 until paramCount do
          val reg = regs(i)
          val loc = Rel(X86.EBP, (paramCount - i - 1) * 4 + 8)
          X86.load(loc, reg, Size.B32)

        X86.int80()

        // Restore ESI/EDI (result is safe in EAX)
        if paramCount > 5 then X86.pop(X86.EDI)
        if paramCount > 4 then X86.pop(X86.ESI)

        // Copy result to caller's result slot at [EBP-4]
        X86.store(Reg(X86.EAX), Rel(X86.EBP, -4))

        // Return: load return address from [ESP] (= [EBP] after restoring), jump
        X86.load(Reg(X86.ESP), X86.EAX, Size.B32)
        X86.jump(Reg(X86.EAX))

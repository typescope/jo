package native.os

import sast.NameTable

import native.Assembly.*
import native.Assembler.PatchableBuffer

import native.arch.X86

/** Linker for linux system call on x86 stack machhine */
class LinuxSyscallX86RegisterLinker(runtimeRootNameTable: NameTable)
extends LinuxSyscall(runtimeRootNameTable):

  /**
    * Implement sys_write in machine code.
    *
    * It assumes the call convention of register machines.
    */
  def sysWrite()(using pb: PatchableBuffer): Unit =
    pb.defineLabel(sys_write_label)

    // init FP
    X86.move(Reg(X86.ESP), X86.EBP)

    // callee-saved reg
    X86.push(X86.EDX)

    X86.move(Reg(X86.ECX), X86.EDX)
    X86.move(Reg(X86.EBX), X86.ECX)
    X86.move(Reg(X86.EAX), X86.EBX)
    X86.move(Int32(4), X86.EAX)
    X86.int80()

    // result of syscall in EAX

    // restore callee-saved reg
    X86.pop(X86.EDX)

    // return to caller
    X86.load(Reg(X86.EBP), X86.EBX, Size.B32)
    X86.jump(Reg(X86.EBX))

  /**
    * Implement abort in machine code.
    */
  def sysExit()(using pb: PatchableBuffer): Unit =
    pb.defineLabel(sys_exit_label)

    // init FP
    X86.move(Reg(X86.ESP), X86.EBP)

    X86.move(Reg(X86.EAX), X86.EBX)
    X86.move(Int32(1), X86.EAX)
    X86.int80()

    // program exits, no need for return

  /**
    * Implement sysBrk in machine code.
    */
  def sysBrk()(using pb: PatchableBuffer): Unit =
    pb.defineLabel(sys_brk_label)

    // init FP
    X86.move(Reg(X86.ESP), X86.EBP)

    // callee-saved registers
    X86.push(X86.EBX)

    X86.move(Reg(X86.EAX), X86.EBX)
    X86.move(Int32(45), X86.EAX)
    X86.int80()

    // result of syscall in EAX

    // restore callee-saved registers -- in reverse order
    X86.pop(X86.EBX)

    // return to caller --- ESP is not used anymore
    X86.load(Reg(X86.EBP), X86.ESP, Size.B32)
    X86.jump(Reg(X86.ESP))

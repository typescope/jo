package native.os

import sast.NameTable

import native.Assembly.*
import native.Assembler.PatchableBuffer

import native.arch.X86


/** Linker for linux system call on x86 register machhine */

class LinuxSyscallX86StackLinker(runtimeRootNameTable: NameTable)
extends LinuxSyscall(runtimeRootNameTable):
  /**
    * Implement sys_write in machine code.
    *
    * The arguments are passed via stack.
    *
    * It assumes that all registers are free.
    */
  def sysWrite()(using pb: PatchableBuffer): Unit =
    pb.defineLabel(sys_write_label)

    // init FP pointer
    X86.move(Reg(X86.ESP), X86.EBP)

    // load argument
    X86.move(Int32(4), X86.EAX)
    X86.load(Rel(X86.EBP, 16), X86.EBX, Size.B32)
    X86.load(Rel(X86.EBP, 12), X86.ECX, Size.B32)
    X86.load(Rel(X86.EBP, 8), X86.EDX, Size.B32)
    X86.int80()

    // copy EAX to result location
    X86.store(Reg(X86.EAX), Rel(X86.EBP, -4))

    // return to caller
    X86.load(Reg(X86.EBP), X86.EAX, Size.B32)
    X86.jump(Reg(X86.EAX))

  /**
    * Implement sys_exit in machine code.
    */
  def sysExit()(using pb: PatchableBuffer): Unit =
    pb.defineLabel(sys_exit_label)

    // init FP pointer
    X86.move(Reg(X86.ESP), X86.EBP)

    // load argument
    X86.move(Int32(1), X86.EAX)
    X86.load(Rel(X86.EBP, 8), X86.EBX, Size.B32)
    X86.int80()

    // program exits, no need for return

  /**
    * Implement sys_brk in machine code.
    */
  def sysBrk()(using pb: PatchableBuffer): Unit =
    pb.defineLabel(sys_brk_label)

    // init FP pointer
    X86.move(Reg(X86.ESP), X86.EBP)

    // load argument
    X86.move(Int32(45), X86.EAX)
    X86.load(Rel(X86.EBP, 8), X86.EBX, Size.B32)
    X86.int80()

    // copy EAX to result location
    X86.store(Reg(X86.EAX), Rel(X86.EBP, -4))

    // return to caller
    X86.load(Reg(X86.EBP), X86.EAX, Size.B32)
    X86.jump(Reg(X86.EAX))

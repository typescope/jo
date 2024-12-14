/************************************************************************
 *                                                                      *
 * Encapsulates platforms on Linux                                      *
 *                                                                      *
 * - x86                                                                *
 * - x64                                                                *
 *                                                                      *
 ************************************************************************/
package native.os

import native.NativeRuntime

import native.register.RegisterMachine
import native.register.CallConvention
import native.stack.StackMachine

import native.cpu.X86

object Linux:
  val PAGE_SIZE  = 0x1000
  val PROG_START = 0x08048000

  val x86RegConfig = new RegisterConfig:
    val FREE_REGS = List(X86.EAX, X86.EBX, X86.ECX, X86.EDX, X86.ESI, X86.EDI)
    val SP_REG = X86.ESP
    val FP_REG = X86.EBP

  /**
    * Create a new x86 register machine
    */
  def createX86RegisterMachine(runtimeRootNameTable: NameTable): Backend =
    val bumpAllocator = new LinuxBumpAllocator(runtimeRootNameTable)
    val syscalls = new LinuxSyscallX86RegisterLinker(runtimeRootNameTable)
    val linkers = List(bumpAllocator, syscalls)
    val runtime = new NativeRuntime(runtimeRootNameTable, linkers)

    val paramRegs: List[Int] = List(X86.EAX, X86.EBX, X86.ECX, X86.EDX)
    val callConv =
      new CallConvention.RegisterCallConvention(x86RegConfig, paramRegs)

    new RegisterMachine(x86RegConfig, callConv, runtime)

  /**
    * Create a new x86 stack machine
    */
  def createX86StackMachine(runtimeRootNameTable: NameTable): Backend =
    val bumpAllocator = new LinuxBumpAllocator(runtimeRootNameTable)
    val syscalls = new LinuxSyscallX86StackLinker(runtimeRootNameTable)
    val linkers = List(bumpAllocator, syscalls)
    val runtime = new NativeRuntime(runtimeRootNameTable, linkers)

    new StackMachine(x86RegConfig, runtime)

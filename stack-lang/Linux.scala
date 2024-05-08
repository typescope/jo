/************************************************************************
 *                                                                      *
 * Encapsulates platforms on Linux                                      *
 *                                                                      *
 * - x86                                                                *
 * - x64                                                                *
 *                                                                      *
 ************************************************************************/

import scala.collection.mutable

import Assembly.*
import Assembler.PatchableBuffer

object Linux:
  val PAGE_SIZE  = 0x1000
  val PROG_START = 0x08048000

  val printLabel = Label("_print")
  val heapStartLabel = Label("_heapStart")

  val nativeFunctions = Map(
    Sast.predef.p -> printLabel
  )

  /**
    * Create a new x86 register machine
    */
  def createX86RegisterMachine(outFile: String, layoutName: String): Platform =
    val layout = Assembler.continuousLayout(layoutName, PROG_START, PAGE_SIZE)
    val elf = new ELF32(outFile, layout, ELF32.EM_386)

    val linker  = new Linker:
      def link()(using pb: PatchableBuffer) = linkPrintRegisterMachineX86()

    // TODO: pass external native link requirements
    val generator = (prog: Prog) =>
      Assembler.lower(elf, prog, heapStartLabel, X86, linker)

    new RegisterMachine(nativeFunctions, generator)

  /**
    * Create a new x86 stack machine
    */
  def createX86StackMachine(outFile: String, layoutName: String): Platform =
    val layout = Assembler.continuousLayout(layoutName, PROG_START, PAGE_SIZE)
    val elf = new ELF32(outFile, layout, ELF32.EM_386)

    val linker  = new Linker:
      def link()(using pb: PatchableBuffer) = linkPrintStackMachineX86()

    // TODO: pass external native link requirements
    val generator = (prog: Prog) =>
      Assembler.lower(elf, prog, heapStartLabel, X86, linker)

    new StackMachine(nativeFunctions, generator)

  /**
    * Implement the printing in machine code.
    *
    * The arguments are passed via stack.
    *
    * It assumes that all registers are free.
    */
  def linkPrintStackMachineX86()(using pb: PatchableBuffer): Unit =
    pb.defineLabel(printLabel)

    // use call stack to prepare string for syscall
    // reserve 16 bytes on stack
    pb.addBytes(0x89.toByte, 0xe1.toByte)          // mov    %esp,%ecx
    pb.addBytes(0x83.toByte, 0xec.toByte, 0x10)    // sub    $0x10,%esp
    pb.addByte(0xbb.toByte); pb.addInt(0x0a)       // mov    $0xa,%ebx

    // load argument
    X86.load(Rel(X86.EBP, 8), X86.EAX)

    // add new line
    pb.addByte(0x49)                               // dec      %ecx
    pb.addBytes(0x88.toByte, 0x19)                 // mov %bl, (%ecx)

    // negative numbers: store flag at %sp
    pb.addBytes(0xc6.toByte, 0x04, 0x24, 0)        // movb   $0x0,(%esp)     ; clear flag
    pb.addBytes(0x83.toByte, 0xf8.toByte, 0)       // cmp    $0x0,%eax
    pb.addBytes(0x7d, 0x0a)                        // jge    <loop>
    pb.addByte(0xba.toByte); pb.addInt(0x2d)       // mov    $0x2d,%edx
    pb.addBytes(0x88.toByte, 0x14, 0x24)           // mov    %dl,(%esp)
    pb.addBytes(0xf7.toByte, 0xd8.toByte)          // neg    %eax

    // loop
    pb.addBytes(0x31, 0xd2.toByte)                 // xor    %edx,%edx
    pb.addBytes(0xf7.toByte, 0xf3.toByte)          // div    %ebx
    pb.addByte (0x49)                              // dec    %ecx
    pb.addBytes(0x83.toByte, 0xc2.toByte, 0x30)    // add    $0x30,%edx
    pb.addBytes(0x88.toByte, 0x11)                 // mov    %dl,(%ecx)
    pb.addBytes(0x85.toByte, 0xc0.toByte)          // test   %eax,%eax
    pb.addBytes(0x75, 0xf2.toByte)                 // jne    <loop>

    // handle sign
    pb.addBytes(0x8b.toByte, 0x14, 0x24)           // mov    (%esp),%edx
    pb.addBytes(0x83.toByte, 0xfa.toByte, 0x2d)    // cmp    $0x2d,%edx
    pb.addBytes(0x8a.toByte, 0x14, 0x24)           // mov    (%esp),%dl
    pb.addBytes(0x80.toByte, 0xfa.toByte, 0x2d)    // cmp    $0x2d,%dl

    pb.addBytes(0x75, 0x03)                        // jne    <system call>
    pb.addByte (0x49)                              // dec    %ecx
    pb.addBytes(0x88.toByte, 0x11)                 // mov    %dl,(%ecx)

    // write stdout system call
    pb.addByte(0xb8.toByte); pb.addInt(0x04)       // mov    $0x4,%eax
    pb.addByte(0xbb.toByte); pb.addInt(0x01)       // mov    $0x1,%ebx
    pb.addBytes(0x8d.toByte, 0x54, 0x24, 0x10)     // lea    0x10(%esp),%edx
    pb.addBytes(0x29, 0xca.toByte)                 // sub    %ecx,%edx
    pb.addBytes(0xcd.toByte, 0x80.toByte)          // int    $0x80

    // restore stack pointer
    pb.addBytes(0x83.toByte, 0xc4.toByte, 0x10)    // add    $0x10,%esp

    // return to caller
    X86.load(Reg(X86.EBP), X86.EAX)
    X86.jump(Reg(X86.EAX))

  /**
    * Implement print in machine code.
    *
    * It assumes the call convention of register machines.
    */
  def linkPrintRegisterMachineX86()(using pb: PatchableBuffer): Unit =
    pb.defineLabel(printLabel)

    // move ebp, esp
    X86.move(Reg(X86.EBP), X86.ESP)

    // callee-saved registers
    pb.addByte((0x50 | X86.EBX).toByte)
    pb.addByte((0x50 | X86.ECX).toByte)
    pb.addByte((0x50 | X86.EDX).toByte)

    // use call stack to prepare string for syscall
    // reserve 16 bytes on stack
    pb.addBytes(0x89.toByte, 0xe1.toByte)          // mov    %esp,%ecx
    pb.addBytes(0x83.toByte, 0xec.toByte, 0x10)    // sub    $0x10,%esp
    pb.addByte(0xbb.toByte); pb.addInt(0x0a)       // mov    $0xa,%ebx

    // argument is in EAX

    // add new line
    pb.addByte(0x49)                               // dec      %ecx
    pb.addBytes(0x88.toByte, 0x19)                 // mov %bl, (%ecx)

    // negative numbers: store flag at %sp
    pb.addBytes(0xc6.toByte, 0x04, 0x24, 0)        // movb   $0x0,(%esp)     ; clear flag
    pb.addBytes(0x83.toByte, 0xf8.toByte, 0)       // cmp    $0x0,%eax
    pb.addBytes(0x7d, 0x0a)                        // jge    <loop>
    pb.addByte(0xba.toByte); pb.addInt(0x2d)       // mov    $0x2d,%edx
    pb.addBytes(0x88.toByte, 0x14, 0x24)           // mov    %dl,(%esp)
    pb.addBytes(0xf7.toByte, 0xd8.toByte)          // neg    %eax

    // loop
    pb.addBytes(0x31, 0xd2.toByte)                 // xor    %edx,%edx
    pb.addBytes(0xf7.toByte, 0xf3.toByte)          // div    %ebx
    pb.addByte (0x49)                              // dec    %ecx
    pb.addBytes(0x83.toByte, 0xc2.toByte, 0x30)    // add    $0x30,%edx
    pb.addBytes(0x88.toByte, 0x11)                 // mov    %dl,(%ecx)
    pb.addBytes(0x85.toByte, 0xc0.toByte)          // test   %eax,%eax
    pb.addBytes(0x75, 0xf2.toByte)                 // jne    <loop>

    // handle sign
    pb.addBytes(0x8b.toByte, 0x14, 0x24)           // mov    (%esp),%edx
    pb.addBytes(0x83.toByte, 0xfa.toByte, 0x2d)    // cmp    $0x2d,%edx
    pb.addBytes(0x8a.toByte, 0x14, 0x24)           // mov    (%esp),%dl
    pb.addBytes(0x80.toByte, 0xfa.toByte, 0x2d)    // cmp    $0x2d,%dl

    pb.addBytes(0x75, 0x03)                        // jne    <system call>
    pb.addByte (0x49)                              // dec    %ecx
    pb.addBytes(0x88.toByte, 0x11)                 // mov    %dl,(%ecx)

    // write stdout system call
    pb.addByte(0xb8.toByte); pb.addInt(0x04)       // mov    $0x4,%eax
    pb.addByte(0xbb.toByte); pb.addInt(0x01)       // mov    $0x1,%ebx
    pb.addBytes(0x8d.toByte, 0x54, 0x24, 0x10)     // lea    0x10(%esp),%edx
    pb.addBytes(0x29, 0xca.toByte)                 // sub    %ecx,%edx
    pb.addBytes(0xcd.toByte, 0x80.toByte)          // int    $0x80

    // restore stack pointer
    pb.addBytes(0x83.toByte, 0xc4.toByte, 0x10)    // add    $0x10,%esp

    // restore callee-saved registers -- in reverse order
    pb.addByte((0x58 | X86.EDX).toByte)
    pb.addByte((0x58 | X86.ECX).toByte)
    pb.addByte((0x58 | X86.EBX).toByte)

    // return to caller
    X86.load(Reg(X86.EBP), X86.EAX)
    X86.jump(Reg(X86.EAX))

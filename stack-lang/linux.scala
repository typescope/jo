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
import Context.UniqueName
import IO.{ ByteBuffer, Patch, PatchableBuffer }

object Linux:
  val PAGE_SIZE = 0x1000

  /**
    * Create a new x86 platform.
    *
    * `X86Platform` is marked private so that code generation is ignorant of the platform.
    */
  def createX86Platform(): Platform = new X86Platform

  /**
    * Generate ELF on Linux platform.
    *
    * TODO: Abstract processor architecture.
    */
  def lower(
    prog: Prog, heapStartLabel: Label, elf: ELF32, installServices: PatchableBuffer => Unit)(
    using bb: ByteBuffer
  ): Unit =
    val labelMap: mutable.Map[Label, Int]    = mutable.Map.empty
    val patches : mutable.ArrayBuffer[Patch] = new mutable.ArrayBuffer
    val buffer  : mutable.ArrayBuffer[Byte]  = new mutable.ArrayBuffer

    /////////////// data section ////////////

    val dataSegBaseAddr = elf.nextSegVirtAddr()
    elf.newSegment(dataSegBaseAddr, ELF32.PT_LOAD, ELF32.PF_R | ELF32.PF_W):
      val dataPB = new PatchableBuffer(dataSegBaseAddr, buffer, labelMap, patches)

      for item <- prog.data do
        item match
          case data: Data   => X86.lower(data)(using dataPB)
          case label: Label => dataPB.defineLabel(label)
          case Align(n)     => dataPB.align(n)

      assert(patches.isEmpty, "patch size non empty for data section")

      val bytes = dataPB.finish()
      val flags = ELF32.SHF_WRITE | ELF32.SHF_ALLOC
      val secIndex = elf.addSection(".bss", ELF32.SHT_PROGBITS, dataSegBaseAddr, bytes, flags, patches = Nil)

      for case label: Label <- prog.data do
        elf.addDataSymbol(label.name, labelMap(label), secIndex)

    /////////////// code section ////////////

    buffer.clear

    val codeSegBaseAddr = elf.nextSegVirtAddr()
    elf.newSegment(codeSegBaseAddr, ELF32.PT_LOAD, ELF32.PF_X | ELF32.PF_R | ELF32.PF_W):
      val newLabels : mutable.ArrayBuffer[Label] = new mutable.ArrayBuffer
      val codePB = new PatchableBuffer(codeSegBaseAddr, buffer, labelMap, patches):
        override def defineLabel(label: Label): Unit =
          super.defineLabel(label)
          newLabels += label

      installServices(codePB)

      for instr <- prog.instrs do
        instr match
          case label: Label => codePB.defineLabel(label)
          case instr: Instr => X86.lower(instr)(using codePB)

      val bytes = codePB.finish()
      val flags = ELF32.SHF_EXEC | ELF32.SHF_ALLOC
      val secIndex = elf.addSection(".text", ELF32.SHT_PROGBITS, codeSegBaseAddr, bytes, flags, patches.toList)

      for label <- newLabels do
        elf.addFunSymbol(label.name, labelMap(label), secIndex)

    /////////////// heap section ////////////

    val heapSegBaseAddr = elf.nextSegVirtAddr()
    elf.newSegment(heapSegBaseAddr, ELF32.PT_LOAD, ELF32.PF_R | ELF32.PF_W):
      val flags = ELF32.SHF_ALLOC
      val bytes = new Array[Byte](PAGE_SIZE)
      val secIndex = elf.addSection(".heap", ELF32.SHT_PROGBITS, heapSegBaseAddr, bytes, flags, patches = Nil)

      elf.addDataSymbol(heapStartLabel.name, heapSegBaseAddr, secIndex)
      labelMap(heapStartLabel) = heapSegBaseAddr

    ////////////////// write file /////////////////

    labelMap.get(prog.entry) match
      case Some(entry) =>
        elf.write(entry)(using bb)

      case None =>
        throw new Exception("Entry point not found: " + prog.entry)
  end lower


  /**
    * Linux x86 32 bit platform
    *
    * Marked private so that code generation is ignorant of the particular platform.
    */
  private class X86Platform extends Platform:
    /** The register ESP and EBP are reserved for value stack and call stack respectively. */
    val freeRegisters: List[Int] = List(X86.EAX, X86.ECX, X86.EDX, X86.EBX, X86.ESI, X86.EDI)

    /** Call stack register (high -> low address)  */
    val CALL_SP_REG: Int = X86.ESP

    /** Value stack register (low -> high address) */
    val VAL_SP_REG: Int = X86.EBP

    val uniqueName = new UniqueName
    export uniqueName.freshName

    val heapStartLabel = Label(uniqueName.freshName("_heapStart"))
    val printService = Label(uniqueName.freshName("_print"))

    /**
      * Generate code to initialize the language runtime.
      */
    def initialize(startLabel: Label)(using ctx: Context): Unit =
      // TODO: Allocate value stack space and remember stack limit.
      ctx.add(Instr.Const(heapStartLabel, VAL_SP_REG))
      ctx.add(Instr.Jump(startLabel))

    /**
      * We resort to services for functionalities that cannot be implement
      * directly with the generic assembly.
      *
      * Such functionalities usually depends on particular platform, such
      * as operating system and/or processor.
      *
      * Services are implemented by emitting platform-specific machine code.
      */
    def defineServices()(using pb: PatchableBuffer): Unit =
      definePrintService()

    /**
      * Implement the printing service.
      *
      * It assumes that all registers are free.
      */
    def definePrintService()(using pb: PatchableBuffer): Unit =
      pb.defineLabel(printService)

      // use call stack to prepare string for syscall
      // reserve 16 bytes on stack
      pb.addBytes(0x89.toByte, 0xe1.toByte)          // mov    %esp,%ecx
      pb.addBytes(0x83.toByte, 0xec.toByte, 0x10)    // sub    $0x10,%esp
      pb.addByte(0xbb.toByte); pb.addInt(0x0a)       // mov    $0xa,%ebx

      // load argument
      X86.sub(Reg(VAL_SP_REG), Int32(4))
      X86.load(Reg(VAL_SP_REG), X86.EAX)

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
      X86.load(Reg(CALL_SP_REG), X86.EAX)
      X86.add(Reg(CALL_SP_REG), Int32(4))
      X86.jump(Reg(X86.EAX))

    /**
      * Generate code to be run after main program finishes.
      */
    def finish()(using ctx: Context): Unit = exit(0)

    def exit(code: Int)(using ctx: Context): Unit =
      ctx.add(Instr.Const(Int32(code), X86.EBX))  // exit code
      ctx.add(Instr.Const(Int32(1), X86.EAX))     // syscall number
      ctx.add(Instr.Special(X86.Syscall))         // syscall

    /**
      * Generate executable for the given assembly progrram.
      */
    def generate(prog: Prog)(using bb: ByteBuffer): Unit =
      val elf = new ELF32(0x08048000, PAGE_SIZE, ELF32.EM_386)
      Linux.lower(prog, heapStartLabel, elf, pb => defineServices()(using pb))


    /**
      * Print the value on top of the stack.
      *
      * Assumes that all registers are free.
      */
    def print()(using ctx: Context): Unit = call(printService)

    /** Return from a procedure or function.
      *
      * Call stack goes from high address to low address.
      */
    def ret()(using ctx: Context) =
      ctx.useReg: r =>
        ctx.add(Instr.Load(Reg(CALL_SP_REG), r))
        ctx.add(Instr.Add(Reg(CALL_SP_REG), Int32(4), CALL_SP_REG))
        ctx.add(Instr.Jump(Reg(r)))

    /**
      * Call the procedure or funtion at the given address.
      *
      * Call stack goes from high address to low address.
      */
    def call(addr: Addr)(using ctx: Context) =
      val returnLoc = Label(ctx.freshName("returnLoc"))
      ctx.add(Instr.Sub(Reg(CALL_SP_REG), Int32(4), CALL_SP_REG))
      ctx.add(Instr.Store(returnLoc, Reg(CALL_SP_REG)))
      ctx.add(Instr.Jump(addr))
      ctx.addCodeLabel(returnLoc)

    /** Pop the value on the top of the value stack to the given register.
      *
      * Value stack goes from low address to high address.
      */
    def pop(destReg: Int)(using ctx: Context) =
      // TODO: empty stack
      ctx.add(Instr.Sub(Reg(VAL_SP_REG), Int32(4), VAL_SP_REG))
      ctx.add(Instr.Load(Reg(VAL_SP_REG), destReg))

    /**
      * Pop the value on the top of the value stack without using it.
      */
    def pop()(using ctx: Context) =
      // TODO: empty stack
      ctx.add(Instr.Sub(Reg(VAL_SP_REG), Int32(4), VAL_SP_REG))

    /**
      * Push the value at the specified index on the top of stack.
      *
      * [index ..., v, ... ]   =>  [v, ..., v, ...]
      */
    def peek()(using ctx: Context): Unit =
      ctx.useReg: r =>
        val addr1 = X86.Rel(VAL_SP_REG, -4)
        ctx.add(Instr.Special(X86.LoadRel(addr1, r)))
        ctx.add(Instr.Mul(Reg(r), Int32(4), r))
        ctx.add(Instr.Add(Reg(r), Int32(8), r))
        ctx.add(Instr.Sub(Reg(VAL_SP_REG), Reg(r), r))
        ctx.add(Instr.Load(Reg(r), r))
        ctx.add(Instr.Special(X86.StoreRel(Reg(r), addr1)))

    /**
      * Push value on the value stack.
      *
      * It could be address of a procedure, represented by a label.
      */
    def push(v: Value)(using ctx: Context) =
      // TODO: grow stack if necessary
      ctx.add(Instr.Store(v, Reg(VAL_SP_REG)))
      ctx.add(Instr.Add(Reg(VAL_SP_REG), Int32(4), VAL_SP_REG))

    /** Swap items on top of stack.
      *
      * It overrides the default implementations to generate optimized code.
      */
    def swap(ctx: Context) =
      // TODO: empty stack
      ctx.useTwoReg: (r1, r2) =>
        val addr1 = X86.Rel(VAL_SP_REG, -4)
        val addr2 = X86.Rel(VAL_SP_REG, -8)
        ctx.add(Instr.Special(X86.LoadRel(addr1, r1)))
        ctx.add(Instr.Special(X86.LoadRel(addr2, r2)))
        ctx.add(Instr.Special(X86.StoreRel(Reg(r1), addr2)))
        ctx.add(Instr.Special(X86.StoreRel(Reg(r2), addr1)))

    /**
      * Duplicate the value on the top of stack.
      *
      * It overrides the default implementations to generate optimized code.
      */
    def duplicate(ctx: Context) =
      // TODO: empty stack
      ctx.useReg: r =>
        val addr = X86.Rel(VAL_SP_REG, -4)
        ctx.add(Instr.Special(X86.LoadRel(addr, r)))
        ctx.push(Reg(r))

    /** Choose between two values depending on the third.
      *
      *     [v1 v2 true  ...]   => [v2 ...]
      *     [v1 v2 false ...]   => [v1 ...]
      *
      * It overrides the default implementations to generate optimized code.
      */
    def choose(ctx: Context) =
      val labelFalse = Label(ctx.freshName("_false"))
      val labelEnd = Label(ctx.freshName("_falseEnd"))
      ctx.useReg: r =>
        val elseAddr = X86.Rel(VAL_SP_REG, -4)
        val thenAddr = X86.Rel(VAL_SP_REG, -8)
        val condAddr = X86.Rel(VAL_SP_REG, -12)

        ctx.add(Instr.Special(X86.LoadRel(condAddr, r)))
        ctx.add(Instr.JZero(Reg(r), labelFalse))

        ctx.add(Instr.Special(X86.LoadRel(thenAddr, r)))
        ctx.add(Instr.Jump(labelEnd))

        ctx.addCodeLabel(labelFalse)
        ctx.add(Instr.Special(X86.LoadRel(elseAddr, r)))

        ctx.addCodeLabel(labelEnd)
        ctx.add(Instr.Special(X86.StoreRel(Reg(r), condAddr)))
        ctx.add(Instr.Sub(Reg(VAL_SP_REG), Int32(8), VAL_SP_REG))
    end choose

    /**
      * Create root scope for compilation.
      *
      * Override the default implementation of primitives to generate optimized code.
      */
    override def createRootScope() =
      val rootScope = new Scope.RootScope()
      val primitives = Primitive.operators
          .updated(Sast.predef.dup, this.duplicate)
          .updated(Sast.predef.swap, this.swap)
          .updated(Sast.predef.choose, this.choose)

      for (k, v) <- primitives do
        rootScope.bind(k, Denotation.Prim(v))
      rootScope

  end X86Platform


  /**
    * Linux x86 64 bit platform
    *
    * TODO
    */
  abstract class X64Platform extends Platform:
    val startAddress: Int = 0x400000

    val align = 0x1000

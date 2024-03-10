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
import IO.{ ByteBuffer, Patch, PatchableBuffer }
import Sast.{ predef, Symbol }

object Linux:
  val PAGE_SIZE = 0x1000

  /**
    * Create a new x86 platform.
    *
    * `X86Platform` is marked private so that code generation is ignorant of the platform.
    */
  def createX86Platform(outFile: String): Platform = new X86Platform(outFile)

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

      // The patches depend on labels of other sections or segments they need to
      // be applied during ELF32 generation.
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
  private class X86Platform(outFile: String) extends Platform:
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

    val symbolLabels: mutable.Map[Symbol, Label] = mutable.Map.empty

    val entry = Label(freshName("_entry"))
    val regAlloc = new RegisterAllocator(freeRegisters)
    val cb = new CodeBuffer(entry)

    export regAlloc.{ useReg, useTwoReg }

    /**
      * Generate entry code
      *
      * The entry code initializes the language runtime, call the main function and exit.
      *
      * Calling the passed function will compile the user entry code.
      */
    def entry(init: => Unit): Unit =
      // TODO: Allocate value stack space and remember stack limit.
      cb.addCodeLabel(this.entry)
      cb.add(Instr.Const(heapStartLabel, VAL_SP_REG))
      init
      exit(0)

    /** Declare the symbol to the platform as a preparation for compilation */
    def declare(sym: Symbol): Unit =
      val label = Label(freshName(sym.name))
      symbolLabels(sym) = label
      if sym.isVal then
        cb.addDataLabel(label)
        cb.add(Data.Uninit(Type.Int32))

    /** Compile a function
      *
      * Calling the passed function will compile the body of the function.
      */
    def function(sym: Symbol, body: () => Unit): Unit =
      val label = symbolLabels(sym)
      cb.addCodeLabel(label)
      body()
      ret()

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

    def exit(code: Int): Unit =
      cb.add(Instr.Const(Int32(code), X86.EBX))  // exit code
      cb.add(Instr.Const(Int32(1), X86.EAX))     // syscall number
      cb.add(Instr.Special(X86.Syscall))         // syscall

    /** Return from a procedure or function.
      *
      * Call stack goes from high address to low address.
      */
    def ret() =
      useReg: r =>
        cb.add(Instr.Load(Reg(CALL_SP_REG), r))
        cb.add(Instr.Add(Reg(CALL_SP_REG), Int32(4), CALL_SP_REG))
        cb.add(Instr.Jump(Reg(r)))

    /**
      * Call the procedure or funtion at the given address.
      *
      * Call stack goes from high address to low address.
      */
    def call(fun: Symbol) =
      val label = symbolLabels(fun)
      call(label)

    def call(addr: Addr) =
      val returnLoc = Label(freshName("returnLoc"))
      cb.add(Instr.Sub(Reg(CALL_SP_REG), Int32(4), CALL_SP_REG))
      cb.add(Instr.Store(returnLoc, Reg(CALL_SP_REG)))
      cb.add(Instr.Jump(addr))
      cb.addCodeLabel(returnLoc)

    /** Pop the value on the top of the value stack to the given register.
      *
      * Value stack goes from low address to high address.
      */
    def pop(destReg: Int) =
      // TODO: empty stack
      cb.add(Instr.Sub(Reg(VAL_SP_REG), Int32(4), VAL_SP_REG))
      cb.add(Instr.Load(Reg(VAL_SP_REG), destReg))

    /**
      * Pop the value on the top of the value stack without using it.
      */
    def pop() =
      // TODO: empty stack
      cb.add(Instr.Sub(Reg(VAL_SP_REG), Int32(4), VAL_SP_REG))

    /**
      * Push value on the value stack.
      *
      * It could be address of a procedure, represented by a label.
      */
    def push(v: Value) =
      // TODO: grow stack if necessary
      cb.add(Instr.Store(v, Reg(VAL_SP_REG)))
      cb.add(Instr.Add(Reg(VAL_SP_REG), Int32(4), VAL_SP_REG))


    /** Push a procedure literal to value stack
      *
      * Calling the passed function will compile the initializer.
      */
    def initVal(sym: Symbol, initializer: () => Unit): Unit =
      val label = symbolLabels(sym)
      initializer()
      useReg: r =>
        pop(r)
        cb.add(Instr.Store(Reg(r), label))

    /** Push an integer literal to value stack */
    def push(v: Int): Unit = push(Int32(v))

    /** Push a Boolean literal to value stack */
    def push(v: Boolean): Unit = push(Int32(if v then 1 else 0))

    /** Push a procedure literal to value stack
      *
      * Calling the passed function will compile the body of the procedure.
      */
    def push(proc: () => Unit): Unit =
      val labelStart = Label(freshName("proc_start"))
      val labelEnd = Label(freshName("proc_end"))

      cb.add(Instr.Jump(labelEnd))
      cb.addCodeLabel(labelStart)
      proc()
      ret()
      cb.addCodeLabel(labelEnd)

      push(labelStart)

    /** Push the value associated with the given symbol to value stack */
    def push(sym: Symbol): Unit =
      val label = symbolLabels(sym)
      useReg: r =>
        cb.add(Instr.Load(label, r))
        push(Reg(r))

    def primitive(sym: Symbol): Unit =
      sym match
        case predef.add    =>   add()
        case predef.sub    =>   sub()
        case predef.mul    =>   mul()
        case predef.div    =>   div()
        case predef.mod    =>   mod()
        case predef.gt     =>   gt()
        case predef.lt     =>   lt()
        case predef.ge     =>   ge()
        case predef.le     =>   le()
        case predef.srl    =>   srl()
        case predef.sll    =>   sll()
        case predef.land   =>   land()
        case predef.lor    =>   lor()
        case predef.lxor   =>   lxor()
        case predef.band   =>   band()
        case predef.bor    =>   bor()
        case predef.bnot   =>   bnot()
        case predef.run    =>   run()
        case predef.eql    =>   eql()
        case predef.dup    =>   dup()
        case predef.swap   =>   swap()
        case predef.peek   =>   peek()
        case predef.pop    =>   pop()
        case predef.choose =>   choose()
        case predef.p      =>   print()
        case _             =>   throw new Exception("Unknown primitive: " + sym.name)
    end primitive

    /** Load a value in value stack relative to the stack pointer.
      *
      * The offset is in bytes.
      */
    def loadValue(destReg: Int, offset: Byte): Unit =
      val addr = X86.Rel(VAL_SP_REG, offset)
      cb.add(Instr.Special(X86.LoadRel(addr, destReg)))

    /** Store a value to value stack relative to the stack pointer.
      *
      * The offset is in bytes.
      */
    def storeValue(fromReg: Int, offset: Byte): Unit =
      val addr = X86.Rel(VAL_SP_REG, offset)
      cb.add(Instr.Special(X86.StoreRel(Reg(fromReg), addr)))

    def int2(fn: (Int, Int, Int) => Instr) =
      // TODO: check type of value
      useTwoReg: (r1, r2) =>
        // Reduce arithmetic on stack pointer to 1
        loadValue(r1, -8)
        loadValue(r2, -4)
        cb.add(fn(r1, r2, r1))
        storeValue(r1, -8)
        pop()

    def add() =
      int2((r1, r2, d) => Instr.Add(Reg(r1), Reg(r2), d))

    def sub() =
      int2((r1, r2, d) => Instr.Sub(Reg(r1), Reg(r2), d))

    def mul() =
      int2((r1, r2, d) => Instr.Mul(Reg(r1), Reg(r2), d))

    def div() =
      int2((r1, r2, d) => Instr.Div(Reg(r1), Reg(r2), d))

    def mod() =
      int2((r1, r2, d) => Instr.Mod(Reg(r1), Reg(r2), d))

    def lt() =
      int2((r1, r2, d) => Instr.Lt(Reg(r1), Reg(r2), d))

    def gt() =
      int2((r1, r2, d) => Instr.Gt(Reg(r1), Reg(r2), d))

    def le() =
      int2((r1, r2, d) => Instr.Le(Reg(r1), Reg(r2), d))

    def ge() =
      int2((r1, r2, d) => Instr.Ge(Reg(r1), Reg(r2), d))

    def sll() =
      int2((r1, r2, d) => Instr.Sll(Reg(r1), Reg(r2), d))

    def srl() =
      int2((r1, r2, d) => Instr.Srl(Reg(r1), Reg(r2), d))

    def land() =
      int2((r1, r2, d) => Instr.And(Reg(r1), Reg(r2), d))

    def lor() =
      int2((r1, r2, d) => Instr.Or(Reg(r1), Reg(r2), d))

    def lxor() =
      int2((r1, r2, d) => Instr.Xor(Reg(r1), Reg(r2), d))

    def band() =
      int2((r1, r2, d) => Instr.And(Reg(r1), Reg(r2), d))

    def bor() =
      int2((r1, r2, d) => Instr.Or(Reg(r1), Reg(r2), d))

    def bnot() =
      useReg: r =>
        loadValue(r, -4)
        cb.add(Instr.Not(Reg(r), r))
        cb.add(Instr.And(Reg(r), Int32(1), r))
        storeValue(r, -4)

    def run() =
      // TODO: check type of value
      useReg: r =>
        pop(r)
        call(Reg(r))

    def eql() =
      useTwoReg: (r1, r2) =>
        loadValue(r1, -4)
        loadValue(r2, -8)
        cb.add(Instr.Eq(Reg(r1), Reg(r2), r2))
        storeValue(r2, -8)
        pop()

    /** Print the value on top of the stack. */
    def print(): Unit = call(printService)


    /**
      * Push the value at the specified index on the top of stack.
      *
      * [index ..., v, ... ]   =>  [v, ..., v, ...]
      */
    def peek(): Unit =
      useReg: r =>
        val addr1 = X86.Rel(VAL_SP_REG, -4)
        loadValue(r, -4)
        cb.add(Instr.Mul(Reg(r), Int32(4), r))
        cb.add(Instr.Add(Reg(r), Int32(8), r))
        cb.add(Instr.Sub(Reg(VAL_SP_REG), Reg(r), r))
        cb.add(Instr.Load(Reg(r), r))
        storeValue(r, -4)

    /** Swap items on top of stack. */
    def swap() =
      // TODO: empty stack
      useTwoReg: (r1, r2) =>
        loadValue(r1, -4)
        loadValue(r2, -8)
        storeValue(r1, -8)
        storeValue(r2, -4)

    /** Duplicate the value on the top of stack. */
    def dup() =
      // TODO: empty stack
      useReg: r =>
        loadValue(r, -4)
        push(Reg(r))

    /** Choose between two values depending on the third.
      *
      *     [v1 v2 true  ...]   => [v2 ...]
      *     [v1 v2 false ...]   => [v1 ...]
      */
    def choose() =
      val labelFalse = Label(freshName("_false"))
      val labelEnd = Label(freshName("_falseEnd"))
      useReg: r =>
        loadValue(r, -12)
        cb.add(Instr.JZero(Reg(r), labelFalse))

        loadValue(r, -8)
        cb.add(Instr.Jump(labelEnd))

        cb.addCodeLabel(labelFalse)
        loadValue(r, -4)

        cb.addCodeLabel(labelEnd)
        storeValue(r, -12)
        cb.add(Instr.Sub(Reg(VAL_SP_REG), Int32(8), VAL_SP_REG))
    end choose

    /** Prepare to start the compilation */
    def start(): Unit = ()

    /** Finish compilation session. */
    def finish(): Unit =
      IO.withExeFile(outFile): bb =>
        val prog: Prog = cb.getResult()
        val elf = new ELF32(0x08048000, PAGE_SIZE, ELF32.EM_386)
        Linux.lower(prog, heapStartLabel, elf, pb => defineServices()(using pb))(using bb)
  end X86Platform

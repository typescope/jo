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
import Sast.*
import Symbol.{ FunSymbol, PrimSymbol }

object Linux:
  val PAGE_SIZE  = 0x1000
  val PROG_START = 0x08048000

  /**
    * Create a new x86 platform.
    *
    * `X86Platform` is marked private so that code generation is ignorant of the platform.
    */
  def createX86Platform(outFile: String, layout: String): Platform =
    new X86Platform(outFile, layout)

  /**
    * Linux x86 32 bit platform
    *
    * Marked private so that code generation is ignorant of the particular platform.
    */
  private class X86Platform(outFile: String, layout: String) extends Platform with Assembler:
    /** The register ESP and EBP are reserved for value stack and call stack respectively. */
    val freeRegisters: List[Int] = List(X86.EAX, X86.ECX, X86.EDX, X86.EBX, X86.ESI, X86.EDI)

    /** Call stack register (high -> low address)  */
    val SP_REG: Int = X86.ESP

    /** Frame pointer register */
    val FP_REG: Int = X86.EBP

    val uniqueName = new UniqueName
    export uniqueName.freshName

    val heapStartLabel = Label(uniqueName.freshName("_heapStart"))
    val printService = Label(uniqueName.freshName("_print"))

    // Index of function parameter, begins from 0
    type ParamIndex = Int
    val symbolMap: mutable.Map[Symbol, Label | ParamIndex] = mutable.Map.empty

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
      // Stack pointer is initialized by the kernel, initialize frame pointer
      cb.mark(this.entry)
      cb.add(Instr.Add(Reg(SP_REG), Int32(0), FP_REG))
      init
      exit(0)

    /** Declare the symbol to the platform as a preparation for compilation */
    def declare(sym: Symbol): Unit =
      assert(!sym.isPrim, "Unexpected primitive symbol " + sym)
      val label = Label(freshName(sym.name))
      symbolMap(sym) = label
      if sym.isVal then
        cb.add(Data.Uninit(label, Type.Int32))

    /** Compile a function
      *
      * Calling the passed function will compile the body of the function.
      */
    def function(sym: FunSymbol, params: List[Symbol], body: () => Unit): Unit =
      val label = symbolMap(sym).asInstanceOf[Label]
      for (param, index) <- params.zipWithIndex do
        symbolMap(param) = index
      cb.mark(label)
      body()
      for param <- params do symbolMap.remove(param)
      ret()

    /** Compile a conditional statement, i.e if/then/else */
    def conditional(ifWord: Word.IfStat, compile: List[Word] => Unit): Unit =
      val labelFalse = Label(freshName("_false"))
      val labelEnd = Label(freshName("_ifEnd"))

      compile(ifWord.cond)

      useReg: r =>
        pop(r)
        cb.add(Instr.JZero(Reg(r), labelFalse))

        compile(ifWord.thenp)

        if ifWord.elsep.nonEmpty then
          cb.add(Instr.Jump(labelEnd))
          cb.mark(labelFalse)
          compile(ifWord.elsep)
        else
          cb.mark(labelFalse)

        cb.mark(labelEnd)

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
      X86.load(X86.Rel(FP_REG, 8), X86.EAX)

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
      X86.load(Reg(FP_REG), X86.EAX)
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
        cb.add(Instr.Load(Reg(FP_REG), r))
        cb.add(Instr.Jump(Reg(r)))

    /**
      * Call the procedure or funtion at the given address.
      *
      * Call stack goes from high address to low address.
      */
    def call(fun: FunSymbol) =
      val label = symbolMap(fun).asInstanceOf[Label]
      val info = fun.info
      call(label, info.paramCount, info.resCount)

    /**
      * Call stack
      *
      *  ┌─────────────┐
      *  │    ...      │
      *  ├─────────────┤
      *  │    arg 0    │
      *  ├─────────────┤
      *  │    ...      │
      *  ├─────────────┤
      *  │    arg N    │
      *  ├─────────────┤
      *  │  saved FP   │
      *  ├─────────────┤
      *  │     RET     │
      *  ├─────────────┤ ◄──────  FP
      *  │   value 0   │
      *  ├─────────────┤
      *  │    ...      │
      *  ├─────────────┤
      *  │   value M   │
      *  └─────────────┘ ◄─────── SP
      */
    def call(addr: Addr, argCount: Int, resCount: Int) =
      // TODO: return value & pop args
      val returnLoc = Label(freshName("returnLoc"))

      // 1. save FP
      cb.add(Instr.Sub(Reg(SP_REG), Int32(4), SP_REG))
      cb.add(Instr.Store(Reg(FP_REG), Reg(SP_REG)))

      // 2. save return
      cb.add(Instr.Sub(Reg(SP_REG), Int32(4), SP_REG))
      cb.add(Instr.Store(returnLoc, Reg(SP_REG)))

      // 3. set FP
      cb.add(Instr.Add(Reg(SP_REG), Int32(0), FP_REG))

      // 4. jump to target
      cb.add(Instr.Jump(addr))

      cb.mark(returnLoc)
      useReg: r =>
        // 5. restore SP
        val spOffset = 2 + argCount - resCount
        cb.add(Instr.Add(Reg(FP_REG), Int32(spOffset * 4), SP_REG))

        // 6. copy result
        var i = 0
        while i < resCount do
          val srcAddr = X86.Rel(FP_REG, (-(i + 1) * 4).toByte)
          val destAddr = X86.Rel(SP_REG, ((resCount - 1 - i) * 4).toByte)
          cb.add(Instr.Special(X86.LoadRel(srcAddr, r)))
          cb.add(Instr.Special(X86.StoreRel(Reg(r), destAddr)))
          i += 1

        // 7. restore FP
        val fpAddr = X86.Rel(FP_REG, 4)
        cb.add(Instr.Special(X86.LoadRel(fpAddr, FP_REG)))

    /** Pop the value on the top of the value stack to the given register.
      *
      * Value stack goes from low address to high address.
      */
    def pop(destReg: Int) =
      cb.add(Instr.Load(Reg(SP_REG), destReg))
      cb.add(Instr.Add(Reg(SP_REG), Int32(4), SP_REG))

    /**
      * Pop the value on the top of the value stack without using it.
      */
    def pop() =
      cb.add(Instr.Add(Reg(SP_REG), Int32(4), SP_REG))

    /**
      * Push value on the value stack.
      *
      * It could be address of a procedure, represented by a label.
      */
    def push(v: Value) =
      cb.add(Instr.Sub(Reg(SP_REG), Int32(4), SP_REG))
      cb.add(Instr.Store(v, Reg(SP_REG)))


    /** Push a procedure literal to value stack
      *
      * Calling the passed function will compile the initializer.
      */
    def initVal(sym: Symbol, initializer: () => Unit): Unit =
      val label = symbolMap(sym).asInstanceOf[Label]
      initializer()
      useReg: r =>
        pop(r)
        cb.add(Instr.Store(Reg(r), label))

    /** Push an integer literal to value stack */
    def push(v: Int): Unit = push(Int32(v))

    /** Push a Boolean literal to value stack */
    def push(v: Boolean): Unit = push(Int32(if v then 1 else 0))

    /** Push the value associated with the given symbol to value stack */
    def push(sym: Symbol): Unit =
      symbolMap(sym) match
        case label: Label =>
          useReg: r =>
            cb.add(Instr.Load(label, r))
            push(Reg(r))

        case paramIndex: Int =>
          val funSym = sym.asParam.owner
          val paramCount = funSym.info.paramCount
          val addr = X86.Rel(FP_REG, ((paramCount + 1 - paramIndex) * 4).toByte)
          useReg: r =>
            cb.add(Instr.Special(X86.LoadRel(addr, r)))
            push(Reg(r))

    def primitive(sym: PrimSymbol): Unit =
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
        case predef.eql    =>   eql()
        case predef.p      =>   print()
        case _             =>   throw new Exception("Unknown primitive: " + sym.name)
    end primitive

    /** Load a value in value stack relative to the stack pointer.
      *
      * The index begins from 0.
      */
    def loadValue(destReg: Int, index: Byte): Unit =
      val addr = X86.Rel(SP_REG, (index * 4).toByte)
      cb.add(Instr.Special(X86.LoadRel(addr, destReg)))

    /** Store a value to value stack relative to the stack pointer.
      *
      * The index begins from 0.
      */
    def storeValue(fromReg: Int, index: Byte): Unit =
      val addr = X86.Rel(SP_REG, (index * 4).toByte)
      cb.add(Instr.Special(X86.StoreRel(Reg(fromReg), addr)))

    def int2(fn: (Int, Int, Int) => Instr) =
      // TODO: check type of value
      useTwoReg: (r1, r2) =>
        // Reduce arithmetic on stack pointer to 1
        loadValue(r1, 1)
        loadValue(r2, 0)
        cb.add(fn(r1, r2, r1))
        storeValue(r1, 1)
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
        loadValue(r, 0)
        cb.add(Instr.Not(Reg(r), r))
        cb.add(Instr.And(Reg(r), Int32(1), r))
        storeValue(r, 0)

    def eql() =
      useTwoReg: (r1, r2) =>
        loadValue(r1, 0)
        loadValue(r2, 1)
        cb.add(Instr.Eq(Reg(r1), Reg(r2), r2))
        storeValue(r2, 1)
        pop()

    /** Print the value on top of the stack. */
    def print(): Unit = call(printService, 1, 0)


    /** Prepare to start the compilation */
    def start(): Unit = ()

    /** Finish compilation session. */
    def finish(): Unit =
      val prog: Assembly.Prog = cb.getResult()
      val layout = Assembler.continuousLayout(this.layout, PROG_START, PAGE_SIZE)
      val elf = new ELF32(outFile, layout, ELF32.EM_386)
      Assembler.lower(elf, prog, heapStartLabel, this)

    def lowerData(data: List[Data])(using pb: PatchableBuffer): Unit =
      for item <- data do X86.lower(item)

    def lowerCode(instrs: List[Instr | Label])(using pb: PatchableBuffer): Unit =
      defineServices()

      for instr <- instrs do
        instr match
          case label: Label => pb.defineLabel(label)
          case instr: Instr => X86.lower(instr)
  end X86Platform

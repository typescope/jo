import scala.collection.mutable

import Assembly.*
import PreAssembly.*
import Assembler.{ Patch, PatchableBuffer }
import Sast.*

/** Fast x86 implementation with register allocation  */
class X86LinuxFast(outFile: String, layout: String) extends Platform:
  /** The register ESP and EBP are reserved for value stack and call stack respectively. */
  val freeRegisters: List[Int] = List(X86.EAX, X86.ECX, X86.EDX, X86.EBX, X86.ESI, X86.EDI)
  val reservedRegisters: List[Int] = List(X86.ESP, X86.EBP)

  /** Registers for function call and return in the order corresponding to arguments */
  val argRegisters: List[Int] = List(X86.EAX, X86.EBX, X86.ECX, X86.EDX)

  /** Call stack register (high -> low address)  */
  val SP_REG: Byte = X86.ESP

  /** Frame pointer register */
  val FP_REG: Byte = X86.EBP

  val heapStartLabel = Label("_heapStart")
  val printService = FunLabel("_print", List(X86.EAX), Nil)

  // maps global symbols to addresses
  val symbolAddrMap: mutable.Map[Symbol, Addr] = mutable.Map.empty

  // maps local symbols to virtual registers
  val VIRTUAL_REG_START_INDEX = 100
  var virtualRegisterIndex = VIRTUAL_REG_START_INDEX
  val symbolRegMap: mutable.Map[Symbol, Int] = mutable.Map.empty

  val entryLabel = Label("_entry")
  var cb = new CodeBuffer(entryLabel)

  /** The code of a function before register allocation */
  val preAsm: PreAssembly.ItemBuffer = new PreAssembly.ItemBuffer

  import preAsm.{ add, mark, place }

  def initVirtualRegisterIndex() =
    virtualRegisterIndex = VIRTUAL_REG_START_INDEX

  /** Allocate a virtual register */
  def allocVirtualReg(): Int =
    virtualRegisterIndex += 1
    virtualRegisterIndex

  class ValueStack:
    val stack: mutable.ArrayBuffer[Operand] = new mutable.ArrayBuffer

    def pop(): Operand =
      if stack.nonEmpty then stack.remove(stack.size - 1)
      else throw new Exception("Stack is empty")

    def pop(n: Int): Unit = stack.dropRightInPlace(n)

    def push(v: Operand): Unit = stack.append(v)

    // peek relative to top --- top is 0
    def peek(i: Int): Operand = stack(size - 1 - i)

    def clear() = stack.clear()

    def size: Int = stack.size

    override def toString() = stack.toString()
  end ValueStack

  private val vs: ValueStack = new ValueStack

  /**
    * Generate entry code
    *
    * The entry code initializes the language runtime, call the main function and exit.
    *
    * Calling the passed function will compile the user entry code.
    */
  def entry(init: => Unit): Unit =
    // Stack pointer is initialized by the kernel, initialize frame pointer
    cb.mark(entryLabel)
    cb.add(Instr.Move(Reg(SP_REG), FP_REG))
    allocRegisters(this.entryLabel, StackInfo(0, 0)):
      init
      exit(0)


  /** Declare the symbol to the platform as a preparation for compilation */
  def declare(sym: Symbol): Unit =
    assert(!sym.isPrimitive, "Unexpected primitive symbol " + sym)
    val label =
      if sym.isFunction then
        val paramRegs = argRegisters.take(sym.info.paramCount)
        val resRegs = argRegisters.take(sym.info.resCount)
        FunLabel(sym.name, paramRegs, resRegs)
      else
        Label(sym.name)

    symbolAddrMap(sym) = label
    if sym.isValue then
      cb.add(Data.Uninit(label, Type.Int32))

  /** Compile a function
    *
    * Calling the passed function will compile the body of the function.
    */
  def function(fdef: Def.FunDef, compile: Compiler): Unit =
    val sym = fdef.symbol
    val label = symbolAddrMap(sym).asInstanceOf[Label]
    val paramCount = fdef.params.size

    assert(paramCount < 31, s"At most 30 parameters, $sym has " + paramCount)

    allocRegisters(label, sym.info):
      // Compile function to a temporary buffer for register allocation
      place(PlaceHolder.InitStackPointer)

      // bind param address to registers and load data from stack
      var index = 0
      for param <- fdef.params do
        if index < argRegisters.size then
          // We need to assign a stable virtual register because the call
          // convention cannot guarantee that the protocol register will only
          // be used by the corresponding parameter in the function body.
          val reg = allocVirtualReg()
          val argReg = argRegisters(index)
          add(Instr.Move(Reg(argReg), reg))
          symbolRegMap(param) = reg
        else
          val offset = paramCount - index + 1
          val addr = Rel(FP_REG, (offset << 2).toByte)
          val reg = allocVirtualReg()
          symbolRegMap(param) = reg
          add(Instr.Load(addr, reg))
        index += 1

      // callee-saved registers
      place(PlaceHolder.SaveRegisters)

      compile(fdef.words)

      ret()

  def allocRegisters(label: Label, stackInfo: StackInfo)(compile: => Unit): Unit =
    initVirtualRegisterIndex()
    preAsm.clear()
    symbolRegMap.clear()
    vs.clear()

    compile

    // clean up
    symbolRegMap.clear()
    assert(vs.size == 0, vs.size)

    // Register allocation
    var continue = true
    var spillCount = 0
    var instrs = preAsm.getResult()
    while continue do
      // println(s"<${label.name}>:")
      // println(Assembly.Prog(Nil, instrs, label).show())

      val liveness = Liveness.analyze(instrs)
      // println(liveness)

      val GraphColoring.Result(regAlloc, stackAlloc) =
          GraphColoring.alloc(
            label.name,
            liveness,
            freeRegisters,
            reservedRegisters,
            VIRTUAL_REG_START_INDEX
          )

      // println(regAlloc)
      // println(stackAlloc)

      def addr(i: Int): Addr = Rel(FP_REG, (-(i + 1 + spillCount) << 2).toByte)

      if stackAlloc.isEmpty then
        commitAlloc(label, stackInfo, instrs, regAlloc, spillCount)
        continue = false
      else
        // rewrite program with spill and perform allocation again
        continue = true
        instrs = rewrite(instrs, stackAlloc, addr)
        spillCount += stackAlloc.size
    end while


  def rewrite(
    instrs: List[PreAssembly.Item],
    stackAlloc: Map[Int, Int],
    addr: Int => Addr
  ): List[PreAssembly.Item] =

    instrs.flatMap:
      case l: Label       => l :: Nil
      case p: PlaceHolder => p :: Nil
      case i: Instr       => spill(i, stackAlloc, allocVirtualReg, addr)

  /** Commit register allocation result and emit assembly from pre-assembly */
  def commitAlloc(
    funLabel: Label,
    stackInfo: StackInfo,
    instrs: List[PreAssembly.Item],
    regAlloc: Map[Int, Int],
    spillCount: Int) =

    // mark beginning of function
    if funLabel != this.entryLabel then
      cb.mark(funLabel)

    val StackInfo(paramNum, resNum) = stackInfo
    val numCallerSavedRegs = if paramNum > resNum then paramNum else resNum
    val calleeSavedRegs = freeRegisters.diff(argRegisters.take(numCallerSavedRegs))
    val usedRegs = regAlloc.values.toList
    val actualSavedRegs = calleeSavedRegs.filter(usedRegs.contains)

    for item <- instrs do
      item match
        case l: Label =>
          cb.mark(l)

        case PlaceHolder.InitStackPointer =>
          // Update SP at the beginning of function
          val frameSize = spillCount + actualSavedRegs.size
          cb.add(Instr.Sub(Reg(FP_REG), Int32(frameSize << 2), SP_REG))

        case PlaceHolder.SaveRegisters =>
          for (savedReg, i) <- actualSavedRegs.zipWithIndex do
            val addr = Rel(FP_REG, (-((spillCount + i) << 2)).toByte)
            add(Instr.Store(Reg(savedReg), addr))

        case PlaceHolder.RestoreRegisters =>
          for (savedReg, i) <- actualSavedRegs.zipWithIndex do
            val addr = Rel(FP_REG, (-((spillCount + i) << 2)).toByte)
            add(Instr.Load(addr, savedReg))

        case instr: Instr =>
          for instr2 <- subst(instr, regAlloc) do
            val RegInfo(defs, uses) = instr2.regInfo
            for dest <- defs do assert(dest < VIRTUAL_REG_START_INDEX, dest)
            for use <- uses do assert(use < VIRTUAL_REG_START_INDEX, use)
            cb.add(instr2)

  /** Compile a conditional statement, i.e if/then/else */
  def conditional(ifword: Word.If, compile: Compiler): Unit =
    val labelFalse = Label("_false")
    val labelEnd = Label("_ifEnd")

    val resCount = ifword.info.resCount

    compile(ifword.cond)

    vs.pop() match
      case Int32(i) =>
        if i == 0 then
          compile(ifword.elsep)
        else
          compile(ifword.thenp)

      case Reg(r) =>
        add(Instr.JZero(Reg(r), labelFalse))

        compile(ifword.thenp)

        if resCount == 0 then
          if ifword.elsep.nonEmpty then
            add(Instr.Jump(labelEnd))
            mark(labelFalse)
            compile(ifword.elsep)
          else
            mark(labelFalse)

          mark(labelEnd)

        else
          assert(ifword.elsep.nonEmpty)
          val resRegs = (0 until resCount).map(_ => allocVirtualReg())

          // finish true branch
          for reg <- resRegs do add(Instr.Move(vs.pop(), reg))
          add(Instr.Jump(labelEnd))

          // false branch
          mark(labelFalse)
          compile(ifword.elsep)
          for reg <- resRegs do add(Instr.Move(vs.pop(), reg))

          mark(labelEnd)
          for reg <- resRegs do vs.push(Reg(reg))
        end if
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
    definePrintService(printService)

  /**
    * Implement the printing service.
    *
    * It assumes that all registers are free.
    */
  def definePrintService(printService: Label)(using pb: PatchableBuffer): Unit =
    pb.defineLabel(printService)

    // move ebp, esp
    X86.move(Reg(FP_REG), SP_REG)

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
    X86.load(Reg(FP_REG), X86.EAX)
    X86.jump(Reg(X86.EAX))


  def exit(code: Int): Unit =
    add(Instr.Move(Int32(code), X86.EBX))  // exit code
    add(Instr.Move(Int32(1), X86.EAX))     // syscall number
    add(Instr.Special(X86.Syscall))         // syscall

  /** Return from a function. */
  def ret() =
    var i = 0
    val size = vs.size
    while i < size do
      val res = vs.peek(size - 1 - i)
      val index = i - argRegisters.size

      if index < 0 then
        add(Instr.Move(res, argRegisters(i)))
      else
        val addr = Rel(FP_REG, (index << 2).toByte)
        add(Instr.Store(res, addr))

      i += 1

    vs.clear()

    place(PlaceHolder.RestoreRegisters)

    // Use SP_REG for simplicity
    add(Instr.Load(Reg(FP_REG), SP_REG))
    add(Instr.Jump(Reg(SP_REG)))

  /**
    * Call the procedure or funtion at the given address.
    *
    * Call stack goes from high address to low address.
    */
  def call(fun: Symbol) =
    val label = symbolAddrMap(fun).asInstanceOf[Label]
    val info = fun.info
    call(label, info.paramCount, info.resCount)

  /**
    * Call stack
    *
    *  ┌─────────────┐
    *  │    ...      │
    *  ├─────────────┤
    *  │    arg 4    │
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
    *
    * The first 4 arguments are passed by registers EAX, EBX, ECX, EDX
    */
  def call(addr: Addr, argCount: Int, resCount: Int) =
    val returnLoc = Label("returnLoc")

    // offset between caller SP and callee FP
    val stackArgCount =
      if argCount <= argRegisters.size then 0
      else argCount - argRegisters.size
    val spOffset = 2 + stackArgCount
    var index = -1

    // 2. save args -- the first 4 arguments are passed via registers
    var i = 0
    while i < argCount do
      // ordering of args
      val arg = vs.peek(argCount - 1 - i)
      if i < argRegisters.size then
        add(Instr.Move(arg, argRegisters(i)))
      else
        storeValue(arg, index.toByte)
        index -= 1
      i += 1

    for _ <- 0 until argCount do vs.pop() // consume all args

    // 3. save FP
    storeValue(Reg(FP_REG), index.toByte)
    index -= 1

    // 4. save return
    storeValue(returnLoc, index.toByte)
    index -= 1

    // 5. update FP
    add(Instr.Sub(Reg(SP_REG), Int32(spOffset << 2), FP_REG))

    // 6. jump to target
    add(Instr.Jump(addr))

    // post call
    mark(returnLoc)

    // 7. restore SP and FP
    index = -spOffset + 1
    add(Instr.Add(Reg(FP_REG), Int32(spOffset << 2), SP_REG))
    loadValue(FP_REG, index.toByte)

    // 9. copy result -- the first 4 results are passed via registers
    i = 0
    while i < resCount do
      val index = i - argRegisters.size
      if index < 0 then
        val reg = argRegisters(i)
        val virtualReg = allocVirtualReg()
        add(Instr.Move(Reg(reg), virtualReg))
        vs.push(Reg(virtualReg))
      else
        val reg = allocVirtualReg()
        loadValue(reg, (-spOffset - index - 1).toByte)
        vs.push(Reg(reg))
      i += 1

  /** Initialize a value definition
    *
    * Calling the passed function will compile the initializer.
    */
  def initVal(vdef: Def.ValDef, compile: Compiler): Unit =
    val label = symbolAddrMap(vdef.symbol).asInstanceOf[Label]
    compile(vdef.words)
    add(Instr.Store(vs.pop(), label))

  /** Push an integer literal to value stack */
  def push(v: Int): Unit = vs.push(Int32(v))

  /** Push a Boolean literal to value stack */
  def push(v: Boolean): Unit = vs.push(Int32(if v then 1 else 0))

  /** Push the value associated with the given symbol to value stack */
  def push(sym: Symbol): Unit =
    // TODO: handle function local value definitions
    if sym.isParameter then
      val reg = symbolRegMap(sym)
      vs.push(Reg(reg))
    else
      val reg = allocVirtualReg()
      val addr = symbolAddrMap(sym)
      add(Instr.Load(addr, reg))
      vs.push(Reg(reg))

  def primitive(sym: Symbol): Unit =
    sym match
      case predef.add    =>   int2(Instr.Add)
      case predef.sub    =>   int2(Instr.Sub)
      case predef.mul    =>   int2(Instr.Mul)
      case predef.div    =>   int2(Instr.Div)
      case predef.mod    =>   int2(Instr.Mod)
      case predef.gt     =>   int2(Instr.Gt)
      case predef.lt     =>   int2(Instr.Lt)
      case predef.ge     =>   int2(Instr.Ge)
      case predef.le     =>   int2(Instr.Le)
      case predef.srl    =>   int2(Instr.Srl)
      case predef.sll    =>   int2(Instr.Sll)
      case predef.land   =>   int2(Instr.And)
      case predef.lor    =>   int2(Instr.Or)
      case predef.lxor   =>   int2(Instr.Xor)
      case predef.band   =>   int2(Instr.And)
      case predef.bor    =>   int2(Instr.Or)
      case predef.bnot   =>   bnot()
      case predef.eql    =>   eql()
      case predef.p      =>   print()
      case _             =>   throw new Exception("Unknown primitive: " + sym.name)
  end primitive

  /** Load a value relative to the stack pointer.
    *
    * The index begins from 0.
    */
  def loadValue(destReg: Int, index: Byte): Unit =
    val addr = Rel(SP_REG, (index * 4).toByte)
    add(Instr.Load(addr, destReg))

  /** Store a value to value stack relative to the stack pointer.
    *
    * The index begins from 0.
    */
  def storeValue(value: Value, index: Byte): Unit =
    val addr = Rel(SP_REG, (index * 4).toByte)
    add(Instr.Store(value, addr))

  def int2(fn: (Operand, Operand, Int) => Instr) =
    // TODO: check type of value
    val arg2 = vs.pop()
    val arg1 = vs.pop()
    val reg = allocVirtualReg()
    add(fn(arg1, arg2, reg))
    vs.push(Reg(reg))

  def bnot() =
    val reg = allocVirtualReg()
    val v = vs.pop()
    add(Instr.Nor(v, v, reg))
    add(Instr.And(Reg(reg), Int32(1), reg))
    vs.push(Reg(reg))

  def eql() =
    val reg = allocVirtualReg()
    add(Instr.Eq(vs.pop(), vs.pop(), reg))
    vs.push(Reg(reg))

  /** Print the value on top of the stack. */
  def print(): Unit = call(printService, 1, 0)

  /** Prepare to start the compilation */
  def start(): Unit = ()

  /** Finish compilation session. */
  def finish(): Unit =
    val prog: Assembly.Prog = cb.getResult()
    val layout = Assembler.continuousLayout(this.layout, Linux.PROG_START, Linux.PAGE_SIZE)
    val elf = new ELF32(outFile, layout, ELF32.EM_386)
    val assembler = new X86.Lowerer(pb ?=> defineServices())
    Assembler.lower(elf, prog, heapStartLabel, assembler)

end X86LinuxFast

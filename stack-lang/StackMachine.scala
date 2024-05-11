import scala.collection.mutable

import Assembly.*
import Assembler.{ Patch, PatchableBuffer }
import Sast.*

import StackMachine.{ RegisterAllocator, RegisterConfig }

/**
  * Implementation based on a stack machine.
  *
  * The class is CPU- and OS-agnostic.
  */
class StackMachine(
  registerConfig: RegisterConfig,
  nativeFunctions: Map[Symbol, Label],
  generator: Assembly.Prog => Unit
) extends Platform:
  import registerConfig.{ FP_REG, SP_REG }

  /** Maps symbols to addresses */
  val symbolAddrMap: mutable.Map[Symbol, Addr] = mutable.Map.from(nativeFunctions)

  /** Program entry pointer */
  val entry = Label("_entry")

  /** Assembly code buffer */
  val cb = new CodeBuffer(entry)

  /** A simple register allocator */
  val regAlloc = new RegisterAllocator(registerConfig.freeRegisters)

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
    cb.add(Instr.Move(Reg(SP_REG), FP_REG))
    init
    exit(0)

  /** Declare the symbol to the platform as a preparation for compilation */
  def declare(sym: Symbol): Unit =
    assert(!sym.isPrimitive, "Unexpected primitive symbol " + sym)
    val label = Label(sym.name)
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
    cb.mark(label)

    assert(paramCount < 31, s"At most 30 parameters, $sym has " + paramCount)

    // bind param address relative to FP_REG
    for (param, index) <- fdef.params.zipWithIndex do
      val offset = ((paramCount + 1 - index) * 4).toByte
      symbolAddrMap(param) = Rel(FP_REG, offset)

    compile(fdef.words)

    for param <- fdef.params do
      symbolAddrMap -= param

    ret()

  /** Compile a conditional statement, i.e if/then/else */
  def conditional(ifword: Word.If, compile: Compiler): Unit =
    val labelFalse = Label("_false")
    val labelEnd = Label("_ifEnd")

    compile(ifword.cond)

    useReg: r =>
      pop(r)
      val target = if ifword.elsep.isEmpty then labelFalse else labelEnd
      cb.add(Instr.JZero(Reg(r), target))

      compile(ifword.thenp)

      if ifword.elsep.nonEmpty then
        cb.add(Instr.Jump(labelEnd))
        cb.mark(labelFalse)
        compile(ifword.elsep)

      cb.mark(labelEnd)


  // TODO: platform-agnostic
  def exit(code: Int): Unit =
    cb.add(Instr.Move(Int32(code), X86.EBX))  // exit code
    cb.add(Instr.Move(Int32(1), X86.EAX))     // syscall number
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
    val returnLoc = Label("returnLoc")

    // 1. save FP
    storeValue(Reg(FP_REG), -1)

    // 2. save return
    storeValue(returnLoc, -2)

    // 3. update FP and SP
    cb.add(Instr.Sub(Reg(SP_REG), Int32(8), SP_REG))
    cb.add(Instr.Move(Reg(SP_REG), FP_REG))

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
        loadValue(r, (-spOffset - i - 1).toByte)
        storeValue(Reg(r), (resCount - 1 - i).toByte)
        i += 1

      // 7. restore FP
      val fpAddr = Rel(FP_REG, 4)
      cb.add(Instr.Load(fpAddr, FP_REG))

  /** Pop the value on the top of the value stack to the given register.
    *
    * Value stack goes from low address to high address.
    */
  def pop(destReg: Byte) =
    cb.add(Instr.Load(Reg(SP_REG), destReg))
    cb.add(Instr.Add(Reg(SP_REG), Int32(4), SP_REG))

  /**
    * Pop the value on the top of the value stack without using it.
    */
  def pop() =
    cb.add(Instr.Add(Reg(SP_REG), Int32(4), SP_REG))

  /**
    * Push value on the value stack.
    */
  def push(v: Value) =
    cb.add(Instr.Sub(Reg(SP_REG), Int32(4), SP_REG))
    cb.add(Instr.Store(v, Reg(SP_REG)))

  /** Initialize a value definition
    *
    * Calling the passed function will compile the initializer.
    */
  def initVal(vdef: Def.ValDef, compile: Compiler): Unit =
    val label = symbolAddrMap(vdef.symbol).asInstanceOf[Label]
    compile(vdef.words)
    useReg: r =>
      pop(r)
      cb.add(Instr.Store(Reg(r), label))

  /** Push an integer literal to value stack */
  def push(v: Int): Unit = push(Int32(v))

  /** Push a Boolean literal to value stack */
  def push(v: Boolean): Unit = push(Int32(if v then 1 else 0))

  /** Push the value associated with the given symbol to value stack */
  def push(sym: Symbol): Unit =
    val addr = symbolAddrMap(sym)
    useReg: r =>
      cb.add(Instr.Load(addr, r))
      push(Reg(r))

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
      case predef.p      =>   call(predef.p)
      case _             =>   throw new Exception("Unknown primitive: " + sym.name)
  end primitive

  /** Load a value in value stack relative to the stack pointer.
    *
    * The index begins from 0.
    */
  def loadValue(destReg: Int, index: Byte): Unit =
    val addr = Rel(SP_REG, (index * 4).toByte)
    cb.add(Instr.Load(addr, destReg))

  /** Store a value to value stack relative to the stack pointer.
    *
    * The index begins from 0.
    */
  def storeValue(value: Value, index: Byte): Unit =
    val addr = Rel(SP_REG, (index * 4).toByte)
    cb.add(Instr.Store(value, addr))

  def int2(fn: (Operand, Operand, Byte) => Instr) =
    // TODO: check type of value
    useTwoReg: (r1, r2) =>
      // Reduce arithmetic on stack pointer to 1
      loadValue(r1, 1)
      loadValue(r2, 0)
      cb.add(fn(Reg(r1), Reg(r2), r1))
      storeValue(Reg(r1), 1)
      pop()

  def bnot() =
    useReg: r =>
      loadValue(r, 0)
      cb.add(Instr.Nor(Reg(r), Reg(r), r))
      cb.add(Instr.And(Reg(r), Int32(1), r))
      storeValue(Reg(r), 0)

  def eql() =
    useTwoReg: (r1, r2) =>
      loadValue(r1, 0)
      loadValue(r2, 1)
      cb.add(Instr.Eq(Reg(r1), Reg(r2), r2))
      storeValue(Reg(r2), 1)
      pop()

  /** Prepare to start the compilation */
  def start(): Unit = ()

  /** Finish compilation session. */
  def finish(): Unit =
    val prog: Assembly.Prog = cb.getResult()
    generator(prog)

end StackMachine

object StackMachine:
  trait RegisterConfig:
    /** Registers available for free usage  */
    val freeRegisters: List[Byte]

    /** Reserved call stack register */
    val SP_REG: Byte

    /** Reserved frame pointer register */
    val FP_REG: Byte


  /**
    * A simple register allocator.
    *
    * @param freeRegs All registers for temporary usage in a processor.
    *
    * The registers reserved for call stack pointer and value stack pointer are excluded.
    */
  class RegisterAllocator(freeRegs: List[Byte]):
    var freeIndex = 0

    /**
      * Allocate a temp register for usage.
      *
      * The allocated register will be released after the function return.
      *
      * TODO: spilling if no temp registers are available?
      */
    def useReg(fn: Byte => Unit): Unit =
      if freeIndex >= freeRegs.size then
        throw new Exception("No register available")
      else
        val freeReg = freeIndex
        freeIndex += 1
        fn(freeRegs(freeReg))
        freeIndex -= 1


    /**
      * Allocate two temporary registers for usage.
      *
      * @see useReg
      */
    def useTwoReg(fn: (Byte, Byte) => Unit): Unit =
      useReg: r1 =>
        useReg: r2 =>
          fn(r1, r2)

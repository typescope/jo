import scala.collection.mutable

import Assembly.{ Type => _, * }
import Assembler.{ Patch, PatchableBuffer }
import Sast.*
import Symbols.*
import Types.*

import StackMachine.RegisterAllocator

/**
  * Implementation based on a stack machine.
  *
  * The class is CPU- and OS-agnostic.
  */
class StackMachine(
  registerConfig: RegisterConfig,
  nativeFunctions: Map[Symbol, Label],
  generator: Assembly.Prog => Unit)
extends Backend:
  import registerConfig.{ FP_REG, SP_REG, FREE_REGS }


  type Context = Unit

  /** Maps symbols to addresses */
  val symbolAddrMap: mutable.Map[Symbol, Addr] = mutable.Map.from(nativeFunctions)

  /** Program entry pointer */
  val entry = Label("_entry")

  /** Assembly code buffer */
  val cb = new CodeBuffer(entry)

  /** The memory allocator */
  val allocatorType = Type.Proc("size" :: Nil, Type.Int :: Nil, Type.Int)
  val allocatorSym = Symbol.createFunSymbol("alloc", allocatorType)
  symbolAddrMap(allocatorSym) = Label(allocatorSym.name)

  /** A simple register allocator */
  val regAlloc = new RegisterAllocator(FREE_REGS)

  export regAlloc.{ useReg, useTwoReg }

  def compile(prog: Sast.Prog): Unit =
    given Context = ()

    for fun <- prog.funs do
      symbolAddrMap(fun.symbol) = Label(fun.name)

    for sym <- prog.vals do
      val label = Label(sym.name)
      symbolAddrMap(sym) = label
      cb.add(Data.Uninit(label, Assembly.Type.Int32))

    // Compile functions
    for fun <- prog.funs do
      compile(fun)

    emitEntry(prog.main)

    // generate code
    generator(cb.getResult())

  def emitEntry(main: Symbol) =
    // Stack pointer is initialized by the kernel, initialize frame pointer
    cb.mark(this.entry)
    cb.add(Instr.Sub(Reg(SP_REG), Int32(4), SP_REG))

    genAllocator()

    val endLabel = Label("_end")
    cb.add(Instr.Store(endLabel, Reg(SP_REG)))
    cb.add(Instr.Move(Reg(SP_REG), FP_REG))

    cb.add(Instr.Jump(symbolAddrMap(main)))

    cb.mark(endLabel)
    exit(0)

  /** Compile a function
    *
    * Calling the passed function will compile the body of the function.
    */
  def compile(fdef: Fun)(using Context): Unit =
    val sym = fdef.symbol
    val funType = sym.info.asInstanceOf[Type.Proc]
    val label = symbolAddrMap(sym).asInstanceOf[Label]

    val paramCount = funType.paramCount
    val resCount = funType.resCount

    cb.mark(label)

    // bind param address relative to FP_REG
    for (param, index) <- fdef.params.zipWithIndex do
      val offset = (paramCount + 1 - index) << 2
      symbolAddrMap(param) = Rel(FP_REG, offset)

    // the ordering does not matter
    for (local, index) <- fdef.locals.zipWithIndex do
      val offset = -(index + 1) << 2
      symbolAddrMap(local) = Rel(FP_REG, offset)

    val sizeLocals = fdef.locals.size << 2
    cb.add(Instr.Sub(Reg(FP_REG), Int32(sizeLocals), SP_REG))

    compile(fdef.body)
    ret(resCount)

    for param <- fdef.params do
      symbolAddrMap -= param

    for local <- fdef.locals do
      symbolAddrMap -= local


  def compile(ifword: If)(using Context): Unit =
    val labelFalse = Label("_false")
    val labelEnd = Label("_ifEnd")

    compile(ifword.cond)

    useReg: r =>
      pop(r)
      val target = if ifword.elsep.isEmpty then labelEnd else labelFalse
      cb.add(Instr.JZero(Reg(r), target))

      compile(ifword.thenp)

      if !ifword.elsep.isEmpty then
        cb.add(Instr.Jump(labelEnd))
        cb.mark(labelFalse)
        compile(ifword.elsep)

      cb.mark(labelEnd)

  def compile(whileDo: While)(using Context): Unit =
    val labelBegin = Label("_whileBegin")
    val labelEnd = Label("_whileEnd")

    cb.mark(labelBegin)
    compile(whileDo.cond)
    useReg: r =>
      pop(r)
      cb.add(Instr.JZero(Reg(r), labelEnd))

      compile(whileDo.body)

      cb.add(Instr.Jump(labelBegin))
      cb.mark(labelEnd)

  // TODO: platform-agnostic
  def exit(code: Int): Unit =
    cb.add(Instr.Move(Int32(code), X86.EBX))  // exit code
    cb.add(Instr.Move(Int32(1), X86.EAX))     // syscall number
    cb.add(Instr.Special(X86.Syscall))        // syscall

  /** Return from a procedure or function.
    *
    * Call stack goes from high address to low address.
    */
  def ret(resCount: Int) =
    var i = resCount - 1
    while i >= 0 do
      val src = Rel(SP_REG, i << 2)
      val dest = Rel(FP_REG, (i - resCount) << 2)
      useReg: r =>
        cb.add(Instr.Load(src, r))
        cb.add(Instr.Store(Reg(r), dest))
      i -= 1

    useReg: r =>
      cb.add(Instr.Load(Reg(FP_REG), r))
      cb.add(Instr.Jump(Reg(r)))

  /**
    * Call the procedure or funtion at the given address.
    *
    * Call stack goes from high address to low address.
    *
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
  def call(fun: Symbol)(using Context) =
    val addr = symbolAddrMap(fun).asInstanceOf[Label]
    val funType = fun.info.asInstanceOf[Type.Proc]
    val argCount = funType.paramCount
    val resCount = funType.resCount
    val returnLoc = Label("returnLoc")

    // 1. save FP
    storeValue(Reg(FP_REG), -1)

    // 2. save return
    storeValue(returnLoc, -2)

    // 3. update FP
    cb.add(Instr.Sub(Reg(SP_REG), Int32(8), FP_REG))

    // 4. jump to target
    cb.add(Instr.Jump(addr))

    cb.mark(returnLoc)

    useReg: r =>
      // 5. restore SP
      val spOffset = 2 + argCount - resCount
      cb.add(Instr.Add(Reg(FP_REG), Int32(spOffset * 4), SP_REG))

      // 6. restore FP before copy result --- avoid overwriting
      val fpAddr = Rel(FP_REG, 4)
      cb.add(Instr.Load(fpAddr, FP_REG))

      // 7. copy result -- after restoring FP to avoid overwriting
      var i = 0
      while i < resCount do
        val src = Rel(SP_REG, (-spOffset - i - 1) << 2)
        val dest = Rel(SP_REG, (resCount - i - 1) << 2)
        cb.add(Instr.Load(src, r))
        cb.add(Instr.Store(Reg(r), dest))
        i += 1

  /** Pop the value on the top of the value stack to the given register.
    *
    * Value stack goes from low address to high address.
    */
  def pop(destReg: Int)(using Context) =
    cb.add(Instr.Load(Reg(SP_REG), destReg))
    cb.add(Instr.Add(Reg(SP_REG), Int32(4), SP_REG))

  /**
    * Pop the value on the top of the value stack without using it.
    */
  def pop()(using Context) =
    cb.add(Instr.Add(Reg(SP_REG), Int32(4), SP_REG))

  /**
    * Push value on the value stack.
    */
  def push(v: Value)(using Context) =
    cb.add(Instr.Sub(Reg(SP_REG), Int32(4), SP_REG))
    cb.add(Instr.Store(v, Reg(SP_REG)))

  /** Initialize a value definition
    *
    * Calling the passed function will compile the initializer.
    */
  def compile(assign: Assign)(using Context): Unit =
    val addr = symbolAddrMap(assign.symbol)
    compile(assign.rhs)
    useReg: r =>
      pop(r)
      cb.add(Instr.Store(Reg(r), addr))

  /** Generate a bump allocator
    *
    * TODO: implement it in Stk.
    */
  def genAllocator(): Unit =
    val allocLabel = symbolAddrMap(allocatorSym).asInstanceOf[Label]

    val initBreakLabel = Label("init_break")
    val curBreakLabel = Label("current_break")

    cb.add(Data.Uninit(initBreakLabel, Assembly.Type.Int32))
    cb.add(Data.Uninit(curBreakLabel, Assembly.Type.Int32))

    val doAllocLabel = Label("doAlloc")
    val allocEndLabel = Label("allocEnd")

    // use invalid arg to get the current break
    cb.add(Instr.Move(Int32(0), X86.EBX))          // new break
    cb.add(Instr.Move(Int32(45), X86.EAX))         // syscall number sys_brk
    cb.add(Instr.Special(X86.Syscall))             // syscall
    cb.add(Instr.Store(Reg(X86.EAX), initBreakLabel))
    cb.add(Instr.Store(Reg(X86.EAX), curBreakLabel))

    cb.add(Instr.Jump(allocEndLabel))

    // start alloc function
    cb.mark(allocLabel)
    cb.add(Instr.Move(Reg(FP_REG), SP_REG))
    cb.add(Instr.Load(Rel(FP_REG, 8), X86.EAX))
    cb.add(Instr.Load(curBreakLabel, X86.EBX))
    cb.add(Instr.Load(initBreakLabel, X86.ECX))
    cb.add(Instr.Add(Reg(X86.EAX), Reg(X86.ECX), X86.ECX))
    cb.add(Instr.Gt(Reg(X86.ECX), Reg(X86.EBX), X86.EDX))
    cb.add(Instr.JZero(Reg(X86.EDX), doAllocLabel))

    // Avoid allocating at page boundary
    cb.add(Instr.Add(Reg(X86.EAX), Reg(X86.EBX), X86.ECX))

    // backup EAX
    cb.add(Instr.Move(Reg(X86.EAX), X86.EDX))

    // Add more pages
    cb.add(Instr.Add(Reg(X86.EBX), Int32(1 << 12), X86.EBX))     // new break
    cb.add(Instr.Move(Int32(45), X86.EAX))                  // syscall number sys_brk
    cb.add(Instr.Special(X86.Syscall))                      // syscall
    cb.add(Instr.Store(Reg(X86.EAX), curBreakLabel))

    // restore EAX
    cb.add(Instr.Move(Reg(X86.EDX), X86.EAX))

    cb.mark(doAllocLabel)
    cb.add(Instr.Store(Reg(X86.ECX), initBreakLabel))
    cb.add(Instr.Sub(Reg(X86.ECX), Reg(X86.EAX), X86.EAX))
    cb.add(Instr.Store(Reg(X86.EAX), Rel(FP_REG, -4)))

    cb.add(Instr.Load(Reg(FP_REG), X86.EBX))
    cb.add(Instr.Jump(Reg(X86.EBX)))
    cb.mark(allocEndLabel)

  /** Allocate a block of memory and push the start address onto value stack.
    */
  def alloc(size: Int)(using Context): Unit =
    push(size)
    call(allocatorSym)

  /** Compile [x = 3, y = 5] */
  def compile(record: RecordLit)(using Context): Unit =
    val recordType = record.tpe.asInstanceOf[Type.Record]
    val size = Memory.size(recordType)

    alloc(size)
    useTwoReg: (r1, r2) =>
      pop(r1)
      for (name, rhs) <- record.args do
        compile(rhs)
        pop(r2)
        val offset = Memory.fieldOffset(recordType, name)
        val fieldAddr = Rel(r1, offset)
        cb.add(Instr.Store(Reg(r2), fieldAddr))
      end for
      push(Reg(r1))

  /** Compile p.x */
  def compile(select: Select)(using Context): Unit =
    val field = select.name
    val qualType = select.qual.tpe.dealias.asInstanceOf[Type.Record]
    val offset = Memory.fieldOffset(qualType, field)
    compile(select.qual)
    useReg: r =>
      pop(r)
      val fieldAddr = Rel(r, offset)
      cb.add(Instr.Load(fieldAddr, r))
      push(Reg(r))

  /** Push an integer literal to value stack */
  def push(v: Int)(using Context): Unit = push(Int32(v))

  /** Push a Boolean literal to value stack */
  def push(v: Boolean)(using Context): Unit =
    push(Int32(if v then 1 else 0))

  /** Push the value associated with the given symbol to value stack */
  def push(sym: Symbol)(using Context): Unit =
    val addr = symbolAddrMap(sym)
    useReg: r =>
      cb.add(Instr.Load(addr, r))
      push(Reg(r))

  def primitive(sym: Symbol)(using Context): Unit =
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
    val addr = Rel(SP_REG, index << 2)
    cb.add(Instr.Load(addr, destReg))

  /** Store a value to value stack relative to the stack pointer.
    *
    * The index begins from 0.
    */
  def storeValue(value: Value, index: Byte): Unit =
    val addr = Rel(SP_REG, index << 2)
    cb.add(Instr.Store(value, addr))

  def int2(fn: (Operand, Operand, Int) => Instr)(using Context) =
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

  def eql()(using Context) =
    useTwoReg: (r1, r2) =>
      loadValue(r1, 0)
      loadValue(r2, 1)
      cb.add(Instr.Eq(Reg(r1), Reg(r2), r2))
      storeValue(Reg(r2), 1)
      pop()

end StackMachine

object StackMachine:
  /**
    * A simple register allocator.
    *
    * @param freeRegs All registers for temporary usage in a processor.
    *
    * The registers reserved for call stack pointer and value stack pointer are excluded.
    */
  class RegisterAllocator(freeRegs: List[Int]):
    var freeIndex = 0

    /**
      * Allocate a temp register for usage.
      *
      * The allocated register will be released after the function return.
      *
      * TODO: spilling if no temp registers are available?
      */
    def useReg(fn: Int => Unit): Unit =
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
    def useTwoReg(fn: (Int, Int) => Unit): Unit =
      useReg: r1 =>
        useReg: r2 =>
          fn(r1, r2)

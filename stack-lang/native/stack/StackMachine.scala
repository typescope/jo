package native.stack

import common.WorkList

import sast.*
import sast.Sast.*
import sast.Symbols.*

import native.Backend
import native.Memory
import native.NativeRuntime

import native.Assembly
import native.Assembly.*
import native.cpu.X86

import StackMachine.RegisterAllocator

import scala.collection.mutable

/**
  * Implementation based on a stack machine.
  *
  * The class is CPU- and OS-agnostic.
  */
class StackMachine(registerConfig: RegisterConfig, runtime: NativeRuntime)
extends Backend:

  import registerConfig.{ FP_REG, SP_REG, FREE_REGS }


  type Context = StackMachine.Context

  /** Program entry pointer */
  val entry = Label("_entry")

  /** A simple register allocator */
  val regAlloc = new RegisterAllocator(FREE_REGS)

  export regAlloc.{ useReg, useTwoReg }

  def cb(using ctx: Context): CodeBuffer = ctx.cb

  def ctx(using ctx: Context): Context = ctx

  def compile(nss: List[Namespace], main: Symbol): Prog =
    val cb = new CodeBuffer(entry)

    workList.add(main)

    val symbolDefMap = mutable.Map.empty[Symbol, FunDef]
    for
      ns <- nss
      case fdef: FunDef <- ns.defs
    do
      symbolDefMap(fdef.symbol) = fdef

    workList.run: sym =>
      val fun = symbolDefMap(sym)
      compile(fun, cb)

    // Add string constants
    for (v, label) <- stringTable do
      cb.add(Data.StringLit(label, v))

    given Context = new Context(cb, Map.empty)

    // Stack pointer is initialized by the kernel, initialize frame pointer
    cb.mark(this.entry)
    cb.add(Instr.Sub(Reg(SP_REG), Int32(4), SP_REG))

    // Call init from linkers
    for init <- runtime.inits do call(init)

    call(main)

    // exit
    call(runtime.Core_finish)

    // generate code
    cb.getResult()

  def compile(phrase: Phrase)(using Context): Unit =
    for word <- phrase.words do compile(word)

  def compile(word: Word)(using Context): Unit =
    word match
      case IntLit(v) => push(Int32(v))

      case BoolLit(v) => push(Int32(if v then 1 else 0))

      case StringLit(v) =>
        val label = addString(v)

        useReg: r =>
          cb.add(Instr.Move(label, r))
          push(Reg(r))

      case record: RecordLit => compile(record)

      case select: Select => compile(select)

      case phrase: Phrase => compile(phrase)

      case encoded: Encoded => compile(encoded)

      case app: Apply => compile(app)

      case TypeApply(fun, _) => compile(fun)

      case assign: Assign => compile(assign)

      case ifElse: If => compile(ifElse)

      case whileDo: While => compile(whileDo)

      case id: Ident => compile(id)

      case _: ValDef | _: FunDef | _: TypeDef | _: With =>
        throw new Exception("Unexpected " + word)

  /** Compile a function */
  def compile(fdef: FunDef, cb: CodeBuffer): Unit =
    val sym = fdef.symbol
    val funType = TypeOps.erasePolyType(sym.info).asProcType

    val label = getAddress(sym)

    val paramCount = funType.paramCount
    val resCount = funType.resCount

    cb.mark(label)

    val symAddrMap = mutable.Map.empty[Symbol, Addr]

    // bind param address relative to FP_REG
    for (param, index) <- fdef.params.zipWithIndex do
      val offset = (paramCount + 1 - index) << 2
      symAddrMap(param) = Rel(FP_REG, offset)

    // the ordering does not matter
    for (local, index) <- fdef.locals.zipWithIndex do
      val offset = -(index + 1) << 2
      symAddrMap(local) = Rel(FP_REG, offset)

    val sizeLocals = fdef.locals.size << 2
    cb.add(Instr.Move(Reg(SP_REG), FP_REG))
    cb.add(Instr.Sub(Reg(SP_REG), Int32(sizeLocals), SP_REG))

    compile(fdef.body)(using new Context(cb, symAddrMap.toMap))
    ret(resCount, cb)

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

  def compile(encoded: Encoded)(using Context): Unit =
    compile(encoded.repr)
    if encoded.isValueDrop then
      pop()

  /** Return from a procedure or function.
    *
    * Stack goes from high address to low address.
    */
  def ret(resCount: Int, cb: CodeBuffer) =
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
  def call(fun: Symbol)(using Context): Unit =
    val addr = getAddress(fun)
    val funType = TypeOps.erasePolyType(fun.info).asProcType
    val argCount = funType.paramCount
    val resCount = funType.resCount
    call(addr, argCount, resCount)

  def call(addr: Addr, argCount: Int, resCount: Int, funAddrOnStack: Boolean = false)(using Context): Unit =
    val returnLoc = Label("returnLoc")

    // 1. save FP
    storeValue(Reg(FP_REG), -1)

    // 2. save return
    storeValue(returnLoc, -2)

    // 3. update SP
    cb.add(Instr.Sub(Reg(SP_REG), Int32(8), SP_REG))

    // 4. jump to target
    cb.add(Instr.Jump(addr))

    cb.mark(returnLoc)

    useReg: r =>
      // 5. restore SP
      val spOffset = 2 + argCount - resCount + (if funAddrOnStack then 1 else 0)
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

  /** Initialize a value definition */
  def compile(assign: Assign)(using Context): Unit =
    val addr = ctx.symbolAddrMap(assign.symbol)
    compile(assign.rhs)
    useReg: r =>
      pop(r)
      cb.add(Instr.Store(Reg(r), addr))

  /** Compile a reference */
  def compile(ref: Ident)(using Context): Unit =
    val addr =
      if ref.symbol.isLocal then ctx.symbolAddrMap(ref.symbol)
      else getAddress(ref.symbol)

    if ref.symbol.isValue then
      useReg: r =>
        cb.add(Instr.Load(addr, r))
        push(Reg(r))
    else
      push(addr.asInstanceOf[Value])

  /** Compile function call */
  def compile(app: Apply)(using Context): Unit =
    fun match
      case Ident(sym) =>
        for arg <- app.args do compile(arg)
        if sym.owner == Definitions.instance.Predef then
          callPredef(sym)
        else
          call(sym)

      case _ =>
        compile(app.fun)
        for arg <- app.args do compile(arg)

        useReg: r =>
          val resCount = if app.tpe.isValueType then 1 else 0
          loadValue(r, app.args.size.toByte)
          this.call(Reg(r), app.args.size, resCount, funAddrOnStack = true)

  /** Compile [x = 3, y = 5] */
  def compile(record: RecordLit)(using Context): Unit =
    val recordType = record.tpe.asRecordType
    val size = Memory.size(recordType)

    // TODO: Explicit allocation in a separate phase
    alloc(size)
    for (name, rhs) <- record.args do
      dup()
      compile(rhs)
      useTwoReg: (r1, r2) =>
        pop(r2)
        pop(r1)
        val offset = Memory.fieldOffset(recordType, name)
        val fieldAddr = Rel(r1, offset)
        cb.add(Instr.Store(Reg(r2), fieldAddr))
    end for

  /** Compile p.x */
  def compile(select: Select)(using Context): Unit =
    val field = select.name
    val qualType = select.qual.tpe.asRecordType
    val offset = Memory.fieldOffset(qualType, field)
    compile(select.qual)
    useReg: r =>
      pop(r)
      val fieldAddr = Rel(r, offset)
      cb.add(Instr.Load(fieldAddr, r))
      push(Reg(r))

  /** Allocate a block of memory and push the start address onto stack */
  def alloc(size: Int)(using Context): Unit =
    push(Int32(size))
    call(runtime.Core_alloc)

  /** Pop the value on the top of the stack to the given register */
  def pop(destReg: Int)(using Context) =
    cb.add(Instr.Load(Reg(SP_REG), destReg))
    cb.add(Instr.Add(Reg(SP_REG), Int32(4), SP_REG))

  /** Pop the value on the top of the stack without using it */
  def pop()(using Context) =
    cb.add(Instr.Add(Reg(SP_REG), Int32(4), SP_REG))

  /** Push value or address on the stack */
  def push(v: Value)(using Context) =
    cb.add(Instr.Sub(Reg(SP_REG), Int32(4), SP_REG))
    cb.add(Instr.Store(v, Reg(SP_REG)))

  def callPredef(sym: Symbol)(using Context): Unit =
    val defn = Definitions.instance

    sym match
      case defn.Predef_add    =>   int2(Instr.Add)
      case defn.Predef_sub    =>   int2(Instr.Sub)
      case defn.Predef_mul    =>   int2(Instr.Mul)
      case defn.Predef_div    =>   int2(Instr.Div)
      case defn.Predef_mod    =>   int2(Instr.Mod)
      case defn.Predef_gt     =>   int2(Instr.Gt)
      case defn.Predef_lt     =>   int2(Instr.Lt)
      case defn.Predef_ge     =>   int2(Instr.Ge)
      case defn.Predef_le     =>   int2(Instr.Le)
      case defn.Predef_srl    =>   int2(Instr.Srl)
      case defn.Predef_sll    =>   int2(Instr.Sll)
      case defn.Predef_land   =>   int2(Instr.And)
      case defn.Predef_lor    =>   int2(Instr.Or)
      case defn.Predef_lxor   =>   int2(Instr.Xor)
      case defn.Predef_band   =>   int2(Instr.And)
      case defn.Predef_bor    =>   int2(Instr.Or)
      case defn.Predef_bnot   =>   bnot()
      case defn.Predef_eql    =>   eql()
      case _                  =>   call(sym)
  end primitive

  /** Duplicate the value on the top of stack. */
  def dup()(using Context) =
    useReg: r =>
      loadValue(r, 0)
      push(Reg(r))

  /** Load a value on stack relative to the stack pointer.
    *
    * The index begins from 0.
    */
  def loadValue(destReg: Int, index: Byte)(using Context): Unit =
    val addr = Rel(SP_REG, index << 2)
    cb.add(Instr.Load(addr, destReg))

  /** Store a value to stack relative to the stack pointer.
    *
    * The index begins from 0.
    */
  def storeValue(value: Value, index: Byte)(using Context): Unit =
    val addr = Rel(SP_REG, index << 2)
    cb.add(Instr.Store(value, addr))

  def int2(fn: (Operand, Operand, Int) => Instr)(using Context) =
    useTwoReg: (r1, r2) =>
      // Reduce arithmetic on stack pointer to 1
      loadValue(r1, 1)
      loadValue(r2, 0)
      cb.add(fn(Reg(r1), Reg(r2), r1))
      storeValue(Reg(r1), 1)
      pop()

  def bnot()(using Context) =
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
  class Context(val cb: CodeBuffer, val symbolAddrMap: Map[Symbol, Addr])

  /**
    * A simple register allocator.
    *
    * @param freeRegs All registers for temporary usage in a processor.
    *
    * The registers reserved for stack pointer are excluded.
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

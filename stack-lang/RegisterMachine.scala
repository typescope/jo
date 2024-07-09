import scala.collection.mutable

import Assembly.{ Type => _, * }
import PreAssembly.*
import Assembler.{ Patch, PatchableBuffer }
import Sast.*
import Symbols.*
import Types.*
import CallConvention.*
import RegisterMachine.*

/** Fast implementation with register allocation
  *
  * The class is CPU- and OS-agnostic.
  */
class RegisterMachine(
  registerConfig: RegisterConfig,
  callConvention: CallConvention,
  nativeFunctions: Map[Symbol, Label],
  generator: Assembly.Prog => Unit)
extends Backend:
  import registerConfig.{ FP_REG, SP_REG, FREE_REGS }

  type Context = FunctionContext

  /** A dummy parameter representing the return address ---
    *
    * Its type does not matter.
    */
  val returnAddrSym = Symbol.createParamSymbol("return", IntType, pos = null)

  /** Maps global symbols to addresses */
  val symbolAddrMap: mutable.Map[Symbol, Addr] = mutable.Map.from(nativeFunctions)

  /** The memory allocator */
  val allocatorType = ProcType("size" :: Nil, IntType :: Nil, IntType)
  val allocatorSym = Symbol.createFunSymbol("alloc", allocatorType, pos = null)
  symbolAddrMap(allocatorSym) = Label(allocatorSym.name)

  def freshVirtualReg()(using ctx: Context): Int =
    ctx.generator.fresh()

  def gen(instr : Instr)(using ctx: Context): Unit =
    ctx.buffer.gen(instr)

  def gen(instr : PreInstr)(using ctx: Context): Unit =
    ctx.buffer.gen(instr)

  def gen(holder: PlaceHolder)(using ctx: Context): Unit =
    ctx.buffer.gen(holder)

  def gen(label: Label)(using ctx: Context): Unit =
    ctx.buffer.gen(label)

  def compile(prog: Sast.Prog): Unit =
    // Buffer to hold the generated assembly code
    val entryLabel = Label("_entry")
    val cb = new CodeBuffer(entryLabel)

    for fun <- prog.funs do
      symbolAddrMap(fun.symbol) = Label(fun.name)

    for ValDef(sym, _) <- prog.vals do
      val label = Label(sym.name)
      symbolAddrMap(sym) = label
      cb.add(Data.Uninit(label, Assembly.Type.Int32))

    // Compile functions
    for fun <- prog.funs do
      val sym = fun.symbol
      val ctx = freshFunctionContext(sym)
      val proto = compile(fun)(using ctx)

      // perform register allocation
      assert(ctx.vs.size == 0, sym.name + " " + ctx.vs.size)
      val label = symbolAddrMap(sym).asInstanceOf[Label]
      doGraphColoring(
        label, ctx.buffer.getResult(), registerConfig, proto.savedRegs,
        cb, ctx.generator)

    entry(entryLabel, prog.init, cb)

    // generate code
    generator(cb.getResult())

  def entry(label: Label, main: Symbol, cb: CodeBuffer) =
    // Stack pointer is initialized by the kernel, initialize frame pointer
    cb.mark(label)
    cb.add(Instr.Sub(Reg(SP_REG), Int32(4), SP_REG))

    genAllocator(cb)

    val endLabel = Label("_end")
    cb.add(Instr.Store(endLabel, Reg(SP_REG)))
    cb.add(Instr.Move(Reg(SP_REG), FP_REG))

    cb.add(Instr.Jump(symbolAddrMap(main)))

    cb.mark(endLabel)
    exit(Int32(0))(cb)

  def load(loc: Location, dest: Int)(using Context): Unit =
    loc match
      case Location.Reg(arg) =>
        gen(Instr.Move(Reg(arg), dest))

      case Location.Mem(baseReg, offset) =>
        val addr = Rel(baseReg, offset)
        gen(Instr.Load(addr, dest))
    end match

  def bindParam(param: Symbol, loc: Location)(using ctx: Context): Unit =
    val paramReg = freshVirtualReg()
    ctx.setRegForLocal(param, paramReg)
    load(loc, paramReg)

  /** Compile a function
    *
    * Calling the passed function will compile the body of the function.
    */
  def compile(fdef: FunDef)(using ctx: Context): CalleeProtocol =
    val sym = fdef.symbol
    val paramCount = fdef.params.size
    val funType = TypeOps.erasePolyType(sym.info).asProcType

    // TODO: bind retLoc
    val proto @ CalleeProtocol(paramLocs, retLoc, resLocs, savedRegs) =
      callConvention.callee(funType.paramTypes, funType.resultType)

    // Compile function to a temporary buffer for register allocation
    gen(PlaceHolder.InitStackPointer)

    // callee-saved registers
    gen(PlaceHolder.CalleeSaveRegisters)

    // bind param to virtual registers and load data
    for (param, loc) <- fdef.params.zip(paramLocs) do
      bindParam(param, loc)

    bindParam(returnAddrSym, retLoc)

    for local <- fdef.locals do
      val localReg = freshVirtualReg()
      ctx.setRegForLocal(local, localReg)

    compile(fdef.body)

    ret(resLocs)

    proto


  /** Compile a conditional statement, i.e if/then/else */
  def compile(ifword: If)(using ctx: Context): Unit =
    val labelFalse = Label("_false")
    val labelEnd = Label("_ifEnd")

    compile(ifword.cond)

    val vs = ctx.vs

    vs.pop() match
      case Int32(i) =>
        if i == 0 then
          compile(ifword.elsep)
        else
          compile(ifword.thenp)

      case Reg(r) =>
        val target = if ifword.elsep.isEmpty then labelEnd else labelFalse
        gen(Instr.JZero(Reg(r), target))

        compile(ifword.thenp)

        if ifword.tpe.isVoid then

          if !ifword.elsep.isEmpty then
            gen(Instr.Jump(labelEnd))
            gen(labelFalse)
            compile(ifword.elsep)

          gen(labelEnd)

        else
          assert(!ifword.elsep.isEmpty)
          val resReg = freshVirtualReg()

          // finish true branch
          gen(Instr.Move(vs.pop(), resReg))
          gen(Instr.Jump(labelEnd))

          // false branch
          gen(labelFalse)
          compile(ifword.elsep)
          gen(Instr.Move(vs.pop(), resReg))

          gen(labelEnd)
          vs.push(Reg(resReg))
        end if

  def compile(whileDo: While)(using ctx: Context): Unit =
    val labelBegin = Label("_whileBegin")
    val labelEnd = Label("_whileEnd")

    gen(labelBegin)
    compile(whileDo.cond)

    ctx.vs.pop() match
      case Int32(i) =>
        if i != 0 then
          compile(whileDo.body)
        else
          gen(Instr.Jump(labelEnd))

      case Reg(r) =>
        gen(Instr.JZero(Reg(r), labelEnd))
        compile(whileDo.body)

    gen(Instr.Jump(labelBegin))
    gen(labelEnd)

  def compile(encoded: Encoded)(using ctx: Context): Unit =
     compile(encoded.repr)
     if encoded.isValueDrop then
       ctx.vs.pop()

  // TODO: platform-agnostic
  def exit(code: Operand)(cb: CodeBuffer): Unit =
    // TODO: abstract over target buffer using context
    cb.add(Instr.Move(code, X86.EBX))  // exit code
    cb.add(Instr.Move(Int32(1), X86.EAX))     // syscall number
    cb.add(Instr.Special(X86.Syscall))        // syscall

  /** Return from a function. */
  def ret(resLocs: List[Location])(using ctx: Context) =
    val values = ctx.vs.pop(resLocs.size)
    val resRegs = mutable.ArrayBuffer.empty[Int]
    for (value, loc) <- values.zip(resLocs) do
      loc match
        case Location.Reg(dest) =>
          resRegs += dest
          gen(Instr.Move(value, dest))

        case Location.Mem(reg, offset) =>
          val addr = Rel(reg, offset)
           gen(Instr.Store(value, addr))
      end match

    val retAddrReg = ctx.getRegForLocal(this.returnAddrSym)
    gen(PreInstr.Return(retAddrReg, resRegs.toList))

  /** Call the funtion */
  def call(fun: Symbol)(using ctx: Context) =
    // TODO: erasure better handled together with boxing/unboxing?
    val funType = TypeOps.erasePolyType(fun.info).asProcType
    val target = symbolAddrMap(fun).asInstanceOf[Label]
    call(target, funType.paramTypes, funType.resultType)

  def call(target: Addr, paramTypes: List[Type], resType: Type)(using ctx: Context) =
    val proto @ CallerProtocol(inRegs, onStack, resLocs) =
      callConvention.caller(paramTypes, resType)

    val args = ctx.vs.pop(paramTypes.size)
    val returnLoc = Label("returnLoc")

    for (fixed, dest) <- inRegs do
      fixed match
        case Fixed.Argument(i) =>
          gen(Instr.Move(args(i), dest))

        case Fixed.ReturnAddress =>
          gen(Instr.Move(returnLoc, dest))

    var index = 0
    val regPositions = mutable.Map.empty[Int, Int]
    for item <- onStack do
      index -= 1
      item match
        case reg: Flex =>
          regPositions(reg.reg) = index
          storeValue(Reg(reg.reg), index)

        case Fixed.Argument(i) =>
          storeValue(args(i), index)

        case Fixed.ReturnAddress =>
          storeValue(returnLoc, index)

    // update FP
    val stackDelta = onStack.size << 2
    gen(Instr.Sub(Reg(SP_REG), Int32(stackDelta), FP_REG))

    // jump to target
    gen(PreInstr.Call(target, proto.argRegs, proto.resRegs))

    // post call
    gen(returnLoc)

    // restore SP
    gen(Instr.Add(Reg(FP_REG), Int32(stackDelta), SP_REG))

    // copy result
    for loc <- resLocs do
      val virtualReg = freshVirtualReg()
      ctx.vs.push(Reg(virtualReg))
      load(loc, virtualReg)

    // restore registers
    for (reg, index) <- regPositions do
      loadValue(reg, index)

  /** Initialize a value definition
    *
    * Calling the passed function will compile the initializer.
    */
  def compile(assign: Assign)(using ctx: Context): Unit =
    val sym = assign.symbol

    compile(assign.rhs)
    val rhsValue = ctx.vs.pop()
    val instr =
      if sym.isLocal then Instr.Move(rhsValue, ctx.getRegForLocal(sym))
      else Instr.Store(rhsValue, symbolAddrMap(sym))
    gen(instr)

  /** Compile a reference to a function */
  def compile(ref: FunRef)(using ctx: Context): Unit =
    val target = symbolAddrMap(ref.symbol).asInstanceOf[Label]
    val targetReg = freshVirtualReg()
    gen(Instr.Move(target, targetReg))
    ctx.vs.push(Reg(targetReg))

  /** Compile function call */
  def compile(call: Call)(using ctx: Context): Unit =
    compile(call.word)

    val funType = call.tpe.asFunctionType
    val recordType = ElimCapture.encodedRecordType(funType)

    val closure = ctx.vs.pop().asInstanceOf[Reg]
    val envReg = freshVirtualReg()
    val envOffset = Memory.fieldOffset(recordType, ElimCapture.EnvFieldName)
    val envAddr = Rel(closure.index, envOffset)
    gen(Instr.Load(envAddr, envReg))
    ctx.vs.push(Reg(envReg))

    val procReg = freshVirtualReg()
    val procOffset = Memory.fieldOffset(recordType, ElimCapture.ProcFieldName)
    val procAddr = Rel(closure.index, procOffset)
    gen(Instr.Load(procAddr, procReg))

    this.call(Reg(procReg), funType.paramTypes :+ AnyType, funType.resultType)

  /** Generate a bump allocator
    *
    * TODO: implement it in Stk.
    */
  def genAllocator(cb: CodeBuffer): Unit =
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

    // callee-saved registers
    cb.add(Instr.Add(Reg(FP_REG), Int32(-12), SP_REG))
    cb.add(Instr.Store(Reg(X86.EBX), Rel(SP_REG, 0)))
    cb.add(Instr.Store(Reg(X86.ECX), Rel(SP_REG, 4)))
    cb.add(Instr.Store(Reg(X86.EDX), Rel(SP_REG, 8)))

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

    // restore callee-saved regs
    cb.add(Instr.Load(Rel(SP_REG, 0), X86.EBX))
    cb.add(Instr.Load(Rel(SP_REG, 4), X86.ECX))
    cb.add(Instr.Load(Rel(SP_REG, 8), X86.EDX))

    cb.add(Instr.Load(Reg(FP_REG), SP_REG))
    cb.add(Instr.Jump(Reg(SP_REG)))
    cb.mark(allocEndLabel)

  /** Allocate a block of memory and push the start address onto value stack.
    */
  def alloc(size: Int)(using ctx: Context): Unit =
    ctx.vs.push(Int32(size))
    call(allocatorSym)

  /** Compile [x = 3, y = 5] */
  def compile(record: RecordLit)(using ctx: Context): Unit =
    val recordType = record.tpe.asRecordType
    val size = Memory.size(recordType)

    alloc(size)
    val recordReg = ctx.vs.pop().asInstanceOf[Reg]

    for (name, rhs) <- record.args do
      compile(rhs)
      val fieldValue = ctx.vs.pop()
      val offset = Memory.fieldOffset(recordType, name)
      val fieldAddr = Rel(recordReg.index, offset)
      gen(Instr.Store(fieldValue, fieldAddr))

    ctx.vs.push(recordReg)

  /** Compile p.x */
  def compile(select: Select)(using ctx: Context): Unit =
    val field = select.name
    val qualType = select.qual.tpe.asRecordType
    val offset = Memory.fieldOffset(qualType, field)

    compile(select.qual)

    val recordReg = ctx.vs.pop().asInstanceOf[Reg]
    val fieldAddr = Rel(recordReg.index, offset)

    val fieldReg = freshVirtualReg()
    gen(Instr.Load(fieldAddr, fieldReg))
    ctx.vs.push(Reg(fieldReg))

  /** Push an integer literal to value stack */
  def push(v: Int)(using ctx: Context): Unit =
    ctx.vs.push(Int32(v))

  /** Push a Boolean literal to value stack */
  def push(v: Boolean)(using ctx: Context): Unit =
    ctx.vs.push(Int32(if v then 1 else 0))

  /** Push the value associated with the given symbol to value stack */
  def push(sym: Symbol)(using ctx: Context): Unit =
    if sym.isLocal then
      val reg = ctx.getRegForLocal(sym)
      ctx.vs.push(Reg(reg))
    else
      val reg = freshVirtualReg()
      val addr = symbolAddrMap(sym)
      gen(Instr.Load(addr, reg))
      ctx.vs.push(Reg(reg))

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
      case runtime.abort =>   abort()
      case _             =>   throw new Exception("Unknown primitive: " + sym.name)
  end primitive

  /** Load a value relative to the stack pointer.
    *
    * The index begins from 0.
    */
  def loadValue(destReg: Int, index: Int)(using Context): Unit =
    val addr = Rel(SP_REG, index << 2)
    gen(Instr.Load(addr, destReg))

  /** Store a value to value stack relative to the stack pointer.
    *
    * The index begins from 0.
    */
  def storeValue(value: Value, index: Int)(using Context): Unit =
    val addr = Rel(SP_REG, index << 2)
    gen(Instr.Store(value, addr))

  def int2(fn: (Operand, Operand, Int) => Instr)(using ctx: Context) =
    val arg2 = ctx.vs.pop()
    val arg1 = ctx.vs.pop()
    val reg = freshVirtualReg()
    gen(fn(arg1, arg2, reg))
    ctx.vs.push(Reg(reg))

  def bnot()(using ctx: Context) =
    val reg = freshVirtualReg()
    val v = ctx.vs.pop()
    gen(Instr.Nor(v, v, reg))
    gen(Instr.And(Reg(reg), Int32(1), reg))
    ctx.vs.push(Reg(reg))

  def eql()(using ctx: Context) =
    val reg = freshVirtualReg()
    gen(Instr.Eq(ctx.vs.pop(), ctx.vs.pop(), reg))
    ctx.vs.push(Reg(reg))

  def abort()(using ctx: Context) =
    val v = ctx.vs.pop()
    gen(Instr.Move(v, X86.EBX))            // exit code
    gen(Instr.Move(Int32(1), X86.EAX))     // syscall number
    gen(Instr.Special(X86.Syscall))        // syscall

    // return a dummy value for compiler invariant -- abort never returns
    ctx.vs.push(Int32(-1))

end RegisterMachine

object RegisterMachine:
  /** The abstract value stack for compilation */
  class ValueStack:
    val stack: mutable.ArrayBuffer[Operand] = new mutable.ArrayBuffer

    def pop(): Operand =
      if stack.nonEmpty then stack.remove(this.size - 1)
      else throw new Exception("Stack is empty")

    def pop(n: Int): Seq[Operand] =
      assert(this.size >= n, s"size = $size, n = $n")
      val slice = stack.slice(this.size - n, this.size)
      stack.dropRightInPlace(n)
      slice.toSeq

    def push(v: Operand): Unit = stack.append(v)

    def size: Int = stack.size

    override def toString() = stack.toString()
  end ValueStack

  class FunctionContext(
    val fun: Symbol,                          // function symbol
    val vs: ValueStack,                       // value stack
    val generator: VirtualRegGenerator,       // virtual register allocator
    val buffer: PreAssembly.ItemBuffer,       // preassembly buffer
    localsToReg: mutable.Map[Symbol, Int]):   // local -> virtual register

    def getRegForLocal(local: Symbol): Int = localsToReg(local)

    def setRegForLocal(local: Symbol, reg: Int) =
      assert(!localsToReg.contains(local))
      localsToReg(local) = reg

  def freshFunctionContext(fun: Symbol): FunctionContext =
    val localsMap = mutable.Map.empty[Symbol, Int]
    val vs = new ValueStack
    val generator = new VirtualRegGenerator
    val buffer = new PreAssembly.ItemBuffer
    FunctionContext(fun, vs, generator, buffer, localsMap)

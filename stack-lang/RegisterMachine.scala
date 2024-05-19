import scala.collection.mutable

import Assembly.*
import PreAssembly.*
import Assembler.{ Patch, PatchableBuffer }
import Sast.*

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

  /** Maps global symbols to addresses */
  val symbolAddrMap: mutable.Map[Symbol, Addr] = mutable.Map.from(nativeFunctions)

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

    for sym <- prog.vals do
      val label = Label(sym.name)
      symbolAddrMap(sym) = label
      cb.add(Data.Uninit(label, Type.Int32))

    // Compile functions
    for fun <- prog.funs do
      val sym = fun.symbol
      val ctx = freshFunctionContext(sym)
      val proto = compile(fun)(using ctx)

      // perform register allocation
      assert(ctx.vs.size == 0, ctx.vs.size)
      val label = symbolAddrMap(sym).asInstanceOf[Label]
      doGraphColoring(
        label, ctx.buffer.getResult(), registerConfig, proto.savedRegs,
        cb, ctx.generator)

    entry(entryLabel, prog.main, cb)

    // generate code
    generator(cb.getResult())

  def entry(label: Label, main: Symbol, cb: CodeBuffer) =
    // Stack pointer is initialized by the kernel, initialize frame pointer
    cb.mark(label)
    cb.add(Instr.Sub(Reg(SP_REG), Int32(4), SP_REG))
    val endLabel = Label("_end")
    cb.add(Instr.Store(endLabel, Reg(SP_REG)))
    cb.add(Instr.Move(Reg(SP_REG), FP_REG))

    cb.add(Instr.Jump(symbolAddrMap(main)))

    cb.mark(endLabel)
    exit(0)(cb)

  /** Compile a function
    *
    * Calling the passed function will compile the body of the function.
    */
  def compile(fdef: Fun)(using ctx: Context): CalleeProtocol =
    val sym = fdef.symbol
    val paramCount = fdef.params.size

    assert(paramCount < 31, s"At most 30 parameters, $sym has " + paramCount)

    val proto @ CalleeProtocol(paramLocs, resLocs, savedRegs) =
      callConvention.callee(sym.info)

    // Compile function to a temporary buffer for register allocation
    gen(PlaceHolder.InitStackPointer)

    // callee-saved registers
    gen(PlaceHolder.SaveRegisters)

    // bind param to virtual registers and load data
    for (param, loc) <- fdef.params.zip(paramLocs) do
      val paramReg = freshVirtualReg()
      ctx.setRegForLocal(param, paramReg)

      loc match
        case Location.Reg(arg) =>
          gen(Instr.Move(Reg(arg), paramReg))

        case Location.Stack(baseReg, offset) =>
          val addr = Rel(baseReg, offset.toByte)
          gen(Instr.Load(addr, paramReg))
      end match
    end for

    compile(fdef.body)

    ret(resLocs)

    proto

  /** Compile a conditional statement, i.e if/then/else */
  def compile(ifword: Word.If)(using ctx: Context): Unit =
    val labelFalse = Label("_false")
    val labelEnd = Label("_ifEnd")

    val resCount = ifword.info.resCount

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

        if resCount == 0 then
          if ifword.elsep.nonEmpty then
            gen(Instr.Jump(labelEnd))
            gen(labelFalse)
            compile(ifword.elsep)

          gen(labelEnd)

        else
          assert(ifword.elsep.nonEmpty)
          val resRegs = (0 until resCount).map(_ => freshVirtualReg())

          // finish true branch
          for reg <- resRegs do gen(Instr.Move(vs.pop(), reg))
          gen(Instr.Jump(labelEnd))

          // false branch
          gen(labelFalse)
          compile(ifword.elsep)
          for reg <- resRegs do gen(Instr.Move(vs.pop(), reg))

          gen(labelEnd)
          for reg <- resRegs do vs.push(Reg(reg))
        end if

  // TODO: platform-agnostic
  def exit(code: Int)(cb: CodeBuffer): Unit =
    // TODO: abstract over target buffer using context
    cb.add(Instr.Move(Int32(code), X86.EBX))  // exit code
    cb.add(Instr.Move(Int32(1), X86.EAX))     // syscall number
    cb.add(Instr.Special(X86.Syscall))        // syscall

  /** Return from a function. */
  def ret(resLocs: List[Location])(using ctx: Context) =
    val values = ctx.vs.pop(resLocs.size)
    for (value, loc) <- values.zip(resLocs) do
      loc match
        case Location.Reg(dest) =>
          gen(Instr.Move(value, dest))

        case Location.Stack(reg, offset) =>
          val addr = Rel(reg, offset.toByte)
           gen(Instr.Store(value, addr))
      end match

    gen(PlaceHolder.RestoreRegisters)
    gen(PreInstr.Return)

  /** Call the funtion */
  def call(fun: Symbol)(using ctx: Context) =
    val target = symbolAddrMap(fun).asInstanceOf[Label]
    val StackInfo(argCount, resCount) = fun.info

    val proto @ CallerProtocol(argLocs, resLocs, savedRegs) =
      callConvention.caller(fun.info)

    // TODO: save registers

    // save args
    val args = ctx.vs.pop(argCount)
    for (arg, loc) <- args.zip(argLocs) do
      loc match
        case Location.Reg(dest) =>
          gen(Instr.Move(arg, dest))

        case Location.Stack(baseReg, offset) =>
          val addr = Rel(baseReg, offset.toByte)
          gen(Instr.Store(arg, addr))

    // FP/SP and return address
    // required for all protocols
    val indexFP = -proto.stackArgNum - 1
    storeValue(Reg(FP_REG), indexFP.toByte)

    val indexRet = -proto.stackArgNum - 2
    val returnLoc = Label("returnLoc")
    storeValue(returnLoc, indexRet.toByte)

    // update FP
    val stackDelta = (proto.stackArgNum + 2) << 2
    gen(Instr.Sub(Reg(SP_REG), Int32(stackDelta), FP_REG))

    // jump to target
    gen(PreInstr.Call(target, proto.argRegs, proto.resRegs))

    // post call
    gen(returnLoc)

    // restore SP and FP
    gen(Instr.Add(Reg(FP_REG), Int32(stackDelta), SP_REG))

    // copy result
    for loc <- resLocs do
      val virtualReg = freshVirtualReg()
      ctx.vs.push(Reg(virtualReg))
      loc match
        case Location.Reg(res) =>
          gen(Instr.Move(Reg(res), virtualReg))

        case Location.Stack(baseReg, offset) =>
          val addr = Rel(baseReg, offset.toByte)
          gen(Instr.Load(addr, virtualReg))
      end match

    // TODO restore registers

    // restore FP
    loadValue(FP_REG, indexFP.toByte)

  /** Initialize a value definition
    *
    * Calling the passed function will compile the initializer.
    */
  def compile(init: Word.Init)(using ctx: Context): Unit =
    val label = symbolAddrMap(init.symbol).asInstanceOf[Label]
    compile(init.rhs)
    gen(Instr.Store(ctx.vs.pop(), label))

  /** Push an integer literal to value stack */
  def push(v: Int)(using ctx: Context): Unit =
    ctx.vs.push(Int32(v))

  /** Push a Boolean literal to value stack */
  def push(v: Boolean)(using ctx: Context): Unit =
    ctx.vs.push(Int32(if v then 1 else 0))

  /** Push the value associated with the given symbol to value stack */
  def push(sym: Symbol)(using ctx: Context): Unit =
    // TODO: handle function local value definitions
    if sym.isParameter then
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
      case _             =>   throw new Exception("Unknown primitive: " + sym.name)
  end primitive

  /** Load a value relative to the stack pointer.
    *
    * The index begins from 0.
    */
  def loadValue(destReg: Int, index: Byte)(using Context): Unit =
    val addr = Rel(SP_REG, (index * 4).toByte)
    gen(Instr.Load(addr, destReg))

  /** Store a value to value stack relative to the stack pointer.
    *
    * The index begins from 0.
    */
  def storeValue(value: Value, index: Byte)(using Context): Unit =
    val addr = Rel(SP_REG, (index * 4).toByte)
    gen(Instr.Store(value, addr))

  def int2(fn: (Operand, Operand, Int) => Instr)(using ctx: Context) =
    // TODO: check type of value
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

    def clear() = stack.clear()

    def size: Int = stack.size

    override def toString() = stack.toString()
  end ValueStack

  class FunctionContext(
    val fun: Symbol,                          // function symbol
    val vs: ValueStack,                       // value stack
    val generator: VirtualRegGenerator,       // virtual register allocator
    val buffer: PreAssembly.ItemBuffer,       // preassembly buffer
    localsToReg: mutable.Map[Symbol, Int]):   // local -> virtual register

    def getRegForLocal(local: Symbol) = localsToReg(local)

    def setRegForLocal(local: Symbol, reg: Int) =
      assert(!localsToReg.contains(local))
      localsToReg(local) = reg

  def freshFunctionContext(fun: Symbol): FunctionContext =
    val localsMap = mutable.Map.empty[Symbol, Int]
    val vs = new ValueStack
    val generator = new VirtualRegGenerator
    val buffer = new PreAssembly.ItemBuffer
    FunctionContext(fun, vs, generator, buffer, localsMap)

import scala.collection.mutable

import Assembly.*
import PreAssembly.*
import Assembler.{ Patch, PatchableBuffer }
import Sast.*

import CallConvention.*

import RegisterMachine.ValueStack

/** Fast implementation with register allocation
  *
  * The class is CPU- and OS-agnostic.
  */
class RegisterMachine(
  registerConfig: RegisterConfig,
  callConvention: CallConvention,
  nativeFunctions: Map[Symbol, Label],
  generator: Assembly.Prog => Unit)
extends Platform:
  import registerConfig.{ FP_REG, SP_REG, FREE_REGS }

  /** Maps global symbols to addresses */
  val symbolAddrMap: mutable.Map[Symbol, Addr] = mutable.Map.from(nativeFunctions)

  /** To generate unique IDs of virtual registers */
  val VIRTUAL_REG_START_INDEX = 100
  var virtualRegisterIndex = VIRTUAL_REG_START_INDEX

  /** Maps local symbols to virtual registers */
  val symbolRegMap: mutable.Map[Symbol, Int] = mutable.Map.empty

  /** Entry point of the program */
  val entryLabel = Label("_entry")

  /** Buffer to hold the generated assembly code */
  val cb = new CodeBuffer(entryLabel)


  /** The code of a function before register allocation */
  val preAsm: PreAssembly.ItemBuffer = new PreAssembly.ItemBuffer

  import preAsm.gen

  def initVirtualRegisterIndex() =
    virtualRegisterIndex = VIRTUAL_REG_START_INDEX

  /** Allocate a virtual register */
  def allocVirtualReg(): Int =
    virtualRegisterIndex += 1
    virtualRegisterIndex

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
    allocRegisters(this.entryLabel, calleeSavedRegs = Nil):
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

    assert(paramCount < 31, s"At most 30 parameters, $sym has " + paramCount)

    val CalleeProtocol(paramLocs, resLocs, savedRegs) =
      callConvention.callee(sym.info)

    allocRegisters(label, savedRegs):
      // Compile function to a temporary buffer for register allocation
      gen(PlaceHolder.InitStackPointer)

      // callee-saved registers
      gen(PlaceHolder.SaveRegisters)

      // bind param to virtual registers and load data
      for (param, loc) <- fdef.params.zip(paramLocs) do
        val paramReg = allocVirtualReg()
        symbolRegMap(param) = paramReg

        loc match
          case Location.Reg(arg) =>
            gen(Instr.Move(Reg(arg), paramReg))

          case Location.Stack(baseReg, offset) =>
            val addr = Rel(baseReg, offset.toByte)
            gen(Instr.Load(addr, paramReg))
        end match
      end for

      compile(fdef.words)

      ret(resLocs)

  def allocRegisters
    (label: Label, calleeSavedRegs: List[Int])
    (compile: => Unit): Unit =

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

      val reservedRegisters: List[Int] = List(FP_REG, SP_REG)

      val GraphColoring.Result(regAlloc, stackAlloc, usedRegs) =
          GraphColoring.alloc(
            label.name,
            liveness,
            registerConfig.FREE_REGS,
            reservedRegisters,
            VIRTUAL_REG_START_INDEX
          )

      // println(regAlloc)
      // println(stackAlloc)

      def addr(i: Int): Addr = Rel(FP_REG, (-(i + 1 + spillCount) << 2).toByte)

      if stackAlloc.isEmpty then
        commitAlloc(label, calleeSavedRegs, instrs, regAlloc, usedRegs, spillCount)
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
    addr: Int => Addr): List[PreAssembly.Item] =

    instrs.flatMap:
      case label: Label        => label :: Nil

      case holder: PlaceHolder => holder :: Nil

      case preInstr: PreInstr  =>
       preInstr match
         case PreInstr.Call(_, _, _) | PreInstr.Return =>
           // spill should never concern call/return
           preInstr :: Nil

         case PreInstr.Instr(instr) =>
           for instr2 <- spill(instr, preInstr.regInfo,  stackAlloc, allocVirtualReg, addr)
           yield PreInstr.Instr(instr2)

  /** Commit register allocation result and emit assembly from pre-assembly */
  def commitAlloc(
    funLabel: Label, calleeSavedRegs: List[Int], instrs: List[PreAssembly.Item],
    regAlloc: Map[Int, Int], usedRegs: Set[Int], spillCount: Int): Unit =

    // mark beginning of function
    if funLabel != this.entryLabel then
      cb.mark(funLabel)

    val actualSavedRegs = calleeSavedRegs.filter(usedRegs.contains)
    // println(s"$funLabel, calleeSavedRegs = $calleeSavedRegs, usedRegs = $usedRegs, actual = $actualSavedRegs")

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
            val addr = Rel(FP_REG, (-((spillCount + i + 1) << 2)).toByte)
            cb.add(Instr.Store(Reg(savedReg), addr))

        case PlaceHolder.RestoreRegisters =>
          for (savedReg, i) <- actualSavedRegs.zipWithIndex do
            val addr = Rel(FP_REG, (-((spillCount + i + 1) << 2)).toByte)
            cb.add(Instr.Load(addr, savedReg))

        case preInstr: PreInstr =>
          preInstr match
            case PreInstr.Instr(instr) =>
              for instr2 <- subst(instr, regAlloc) do
                val RegInfo(defs, uses) = PreAssembly.analyzeRegInfo(instr2)
                for dest <- defs do assert(dest < VIRTUAL_REG_START_INDEX, dest)
                for use <- uses do assert(use < VIRTUAL_REG_START_INDEX, use)
                cb.add(instr2)

            case PreInstr.Call(addr, _, _) =>
              cb.add(Instr.Jump(addr))

            case PreInstr.Return =>
              // Use SP_REG for simplicity
              cb.add(Instr.Load(Reg(FP_REG), SP_REG))
              cb.add(Instr.Jump(Reg(SP_REG)))

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
          val resRegs = (0 until resCount).map(_ => allocVirtualReg())

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
  def exit(code: Int): Unit =
    gen(Instr.Move(Int32(code), X86.EBX))  // exit code
    gen(Instr.Move(Int32(1), X86.EAX))     // syscall number
    gen(Instr.Special(X86.Syscall))        // syscall

  /** Return from a function. */
  def ret(resLocs: List[Location]) =
    val values = vs.pop(resLocs.size)
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
  def call(fun: Symbol) =
    val target = symbolAddrMap(fun).asInstanceOf[Label]
    val StackInfo(argCount, resCount) = fun.info

    val proto @ CallerProtocol(argLocs, resLocs, savedRegs) =
      callConvention.caller(fun.info)

    // TODO: save registers

    // save args
    val args = vs.pop(argCount)
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
      val virtualReg = allocVirtualReg()
      vs.push(Reg(virtualReg))
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
  def initVal(vdef: Def.ValDef, compile: Compiler): Unit =
    val label = symbolAddrMap(vdef.symbol).asInstanceOf[Label]
    compile(vdef.words)
    gen(Instr.Store(vs.pop(), label))

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
      gen(Instr.Load(addr, reg))
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
      case predef.p      =>   call(predef.p)
      case _             =>   throw new Exception("Unknown primitive: " + sym.name)
  end primitive

  /** Load a value relative to the stack pointer.
    *
    * The index begins from 0.
    */
  def loadValue(destReg: Int, index: Byte): Unit =
    val addr = Rel(SP_REG, (index * 4).toByte)
    gen(Instr.Load(addr, destReg))

  /** Store a value to value stack relative to the stack pointer.
    *
    * The index begins from 0.
    */
  def storeValue(value: Value, index: Byte): Unit =
    val addr = Rel(SP_REG, (index * 4).toByte)
    gen(Instr.Store(value, addr))

  def int2(fn: (Operand, Operand, Int) => Instr) =
    // TODO: check type of value
    val arg2 = vs.pop()
    val arg1 = vs.pop()
    val reg = allocVirtualReg()
    gen(fn(arg1, arg2, reg))
    vs.push(Reg(reg))

  def bnot() =
    val reg = allocVirtualReg()
    val v = vs.pop()
    gen(Instr.Nor(v, v, reg))
    gen(Instr.And(Reg(reg), Int32(1), reg))
    vs.push(Reg(reg))

  def eql() =
    val reg = allocVirtualReg()
    gen(Instr.Eq(vs.pop(), vs.pop(), reg))
    vs.push(Reg(reg))

  /** Prepare to start the compilation */
  def start(): Unit = ()

  /** Finish compilation session. */
  def finish(): Unit =
    val prog: Assembly.Prog = cb.getResult()
    generator(prog)

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

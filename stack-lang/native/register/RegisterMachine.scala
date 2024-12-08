package native.register

import sast.Sast.*
import sast.Symbols.*
import sast.Types.*

import Assembly.{ Type => _, * }
import PreAssembly.*
import CallConvention.*
import RegisterMachine.*

import scala.collection.mutable

/** Fast implementation with register allocation
  *
  * The class is CPU- and OS-agnostic.
  */
class RegisterMachine(
  registerConfig: RegisterConfig,
  callConvention: CallConvention,
  nativeFunctions: Map[Symbol, Label],
  generator: Prog => Unit):

  import registerConfig.{ FP_REG, SP_REG }

  type Context = FunctionContext

  /** A dummy parameter representing the return address
    *
    * Its type does not matter.
    */
  val returnAddrSym = Symbol.createParamSymbol("return", IntType, owner = Predef.predefSym, pos = null)

  /** Maps function symbols to addresses */
  val funLabelMap: mutable.Map[Symbol, Label] = mutable.Map.from(nativeFunctions)

  def getAddress(sym: Symbol): Label =
    assert(sym.isFunction || funLabelMap.contains(sym), "Not a function, sym = " + sym)

    funLabelMap.get(sym) match
      case Some(addr) => addr

      case None =>
        val label = Label(sym.name)
        funLabelMap(sym) = label

        // Add function to work list
        if !sym.isPrimitive then workList.add(sym)

        label

  /** Maps string constants to labels */
  val stringTable: mutable.Map[String, Label] = mutable.Map.empty

  def addString(v: String): Label =
    stringTable.get(v) match
      case Some(label) => label
      case None =>
        val label = Label("string")
        stringTable(v) = label
        label

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

  val workList = new WorkList[Symbol]

  def compile(nss: List[Namespace], main: Symbol): Unit =
    // Buffer to hold the generated assembly code
    val entryLabel = Label("_entry")
    val cb = new CodeBuffer(entryLabel)

    workList.add(main)

    val symbolDefMap = mutable.Map.empty[Symbol, FunDef]
    for
      ns <- nss
      case fdef: FunDef <- ns.defs
    do
      symbolDefMap(fdef.symbol) = fdef

    workList.run: sym =>
      val fun = symbolDefMap(sym)
      val ctx = freshFunctionContext(sym)
      val proto = compile(fun)(using ctx)

      // perform register allocation
      assert(ctx.vs.size == 0, sym.name + " " + ctx.vs.size)
      val label = getAddress(sym)
      doGraphColoring(
        label, ctx.buffer.getResult(),
        registerConfig, proto.savedRegs,
        cb, ctx.generator)

    // Add string constants
    for (v, label) <- stringTable do
      cb.add(Data.String(label, v))

    entry(entryLabel, main, cb)

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

    cb.add(Instr.Jump(getAddress(main)))

    // exit runtime
    cb.add(Instr.Jump(getAddress(NativeRuntime.finish)))

    cb.mark(endLabel)

  def compile(phrase: Phrase)(using Context): Unit =
    for word <- phrase.words do compile(word)

  def compile(word: Word)(using Context): Unit =
    word match
      case IntLit(v) => ctx.vs.push(Int32(v))

      case BoolLit(v) =>
        ctx.vs.push(Int32(if v then 1 else 0))

      case StringLit(v) =>
        val label = addString(v)
        val reg = freshVirtualReg()
        gen(Instr.Move(label, reg))
        ctx.vs.push(Reg(reg))

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

      case _: ValDef | _: FunDef | _: TypeDef =>
        throw new Exception("Unexpected " + word)

  def load(loc: Location, dest: Int, base: Rel)(using Context): Unit =
    loc match
      case Location.Reg(reg) =>
        gen(Instr.Move(Reg(reg), dest))

      case loc: Location.Stack =>
        val addr = Location.map(loc, base)
        gen(Instr.Load(addr, dest))
    end match

  def save(value: Value, loc: Location, base: Rel)(using Context): Unit =
    loc match
      case Location.Reg(reg) =>
        gen(Instr.Move(value, reg))

      case loc: Location.Stack =>
        val addr = Location.map(loc, base)
        gen(Instr.Store(value, addr))
    end match

  def bindParam(param: Symbol, loc: Location, base: Rel)(using ctx: Context): Unit =
    val paramReg = freshVirtualReg()
    ctx.setRegForLocal(param, paramReg)
    load(loc, paramReg, base)

  /** Compile a function */
  def compile(fdef: FunDef)(using ctx: Context): Protocol =
    val sym = fdef.symbol
    val funType = TypeOps.erasePolyType(sym.info).asProcType

    val proto @ Protocol(inProto, outProto, savedRegs) =
      callConvention.callee(funType.paramTypes, funType.resultType)

    // Compile function to a temporary buffer for register allocation
    gen(PlaceHolder.InitStackPointer)

    // callee-saved registers
    gen(PlaceHolder.CalleeSaveRegisters)

    val base = Rel(FP_REG, (inProto.stackItemCount - 1) << 2)

    // bind param to virtual registers and load data
    for (param, loc) <- fdef.params.zip(inProto.paramLocs) do
      bindParam(param, loc, base)

    bindParam(returnAddrSym, inProto.retLoc, base)

    for local <- fdef.locals do
      val localReg = freshVirtualReg()
      ctx.setRegForLocal(local, localReg)

    compile(fdef.body)

    ret(outProto)

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

        if ifword.tpe.isVoidType then

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

  /** Return from a function. */
  def ret(resLocs: List[Location])(using ctx: Context) =
    val values = ctx.vs.pop(resLocs.size)
    val resRegs = mutable.ArrayBuffer.empty[Int]

    val base = Rel(FP_REG, -4)
    for (value, loc) <- values.zip(resLocs) do
      loc match
        case Location.Reg(dest) =>
          resRegs += dest
          gen(Instr.Move(value, dest))

        case loc: Location.Stack =>
          val addr = Location.map(loc, base)
          gen(Instr.Store(value, addr))
      end match

    val retAddrReg = ctx.getRegForLocal(this.returnAddrSym)
    gen(PreInstr.Return(retAddrReg, resRegs.toList))

  /** Call the funtion */
  def call(fun: Symbol)(using ctx: Context): Unit =
    // TODO: erasure better handled together with boxing/unboxing?
    val funType = TypeOps.erasePolyType(fun.info).asProcType
    val target = getAddress(fun)
    call(target, funType.paramTypes, funType.resultType)

  def call(target: Addr, paramTypes: List[Type], resType: Type)(using ctx: Context): Unit =
    val proto @ Protocol(inProto, outProto, savedRegs) =
      callConvention.caller(paramTypes, resType)

    val args = ctx.vs.pop(paramTypes.size)
    val returnLoc = Label("returnLoc")

    var index = 0
    val savedRegPositions = mutable.Map.empty[Int, Int]
    for savedReg <- savedRegs do
      index -= 1
      savedRegPositions(savedReg) = index
      storeValue(Reg(savedReg), index)

    val base = Rel(SP_REG, (index - 1) << 2)
    for (paramLoc, i) <- inProto.paramLocs.zipWithIndex do
      save(args(i), paramLoc, base)

    save(returnLoc, inProto.retLoc, base)

    // We cannot update FP because spilling is relative to FP --- changing FP
    // will cause problem for the next jump instruction.
    val stackDelta = (savedRegs.size + inProto.stackItemCount) << 2
    gen(Instr.Sub(Reg(SP_REG), Int32(stackDelta), SP_REG))

    // jump to target
    gen(PreInstr.Call(target, proto.inRegs, proto.outRegs))

    // post call
    gen(returnLoc)

    // restore SP
    gen(Instr.Add(Reg(FP_REG), Int32(stackDelta), SP_REG))

    // Restore FP before copying result, spilling may happen for copying
    // result which will depend on FP being restored.
    //
    // However, we need to ensure that restoring regs will not overwrite return.
    // Therefore, other regs are restored after copying the result.
    loadValue(FP_REG, savedRegPositions(FP_REG))

    val resBase = Rel(SP_REG, -4 - stackDelta)
    // copy result
    for loc <- outProto do
      val virtualReg = freshVirtualReg()
      ctx.vs.push(Reg(virtualReg))
      load(loc, virtualReg, resBase)

    // restore remaining regs
    for
      (reg, index) <- savedRegPositions if reg != FP_REG
    do
      loadValue(reg, index)

  /** Compile assignment */
  def compile(assign: Assign)(using ctx: Context): Unit =
    val sym = assign.symbol

    compile(assign.rhs)
    val rhsValue = ctx.vs.pop()
    val instr =
      if sym.isLocal then Instr.Move(rhsValue, ctx.getRegForLocal(sym))
      else throw new Exception("assigning to non-local " + sym) // Instr.Store(rhsValue, getAddress(sym))
    gen(instr)

  /** Compile a reference to a name that produces a runtime value */
  def compile(id: Ident)(using ctx: Context): Unit =
    val sym = id.symbol
    if sym.isValue then
      if sym.isLocal then
        val reg = ctx.getRegForLocal(sym)
        ctx.vs.push(Reg(reg))
      else
        throw new Exception("accessing non-local " + sym)
        // val reg = freshVirtualReg()
        // val addr = getAddress(sym)
        // gen(Instr.Load(addr, reg))
        // ctx.vs.push(Reg(reg))
    else
      if sym.isPrimitive then
        throw new Exception("Unexpected primitive " + sym)
      else
        val target = getAddress(id.symbol)
        val targetReg = freshVirtualReg()
        gen(Instr.Move(target, targetReg))
        ctx.vs.push(Reg(targetReg))

  /** Compile function call */
  def compile(app: Apply)(using ctx: Context): Unit =
    if app.isPrimitiveCall then
      for arg <- app.args do compile(arg)
      primitive(app.primitive)

    else
      compile(app.fun)
      val fun = ctx.vs.pop().asInstanceOf[Reg]
      val funType = app.fun.tpe.asInvokableType

      for arg <- app.args do compile(arg)
      this.call(fun, funType.paramTypes, funType.resultType)

  /** Generate a bump allocator
    *
    * TODO: implement it in Stk.
    */
  def genAllocator(cb: CodeBuffer): Unit =
    val allocLabel = getAddress(Predef.allocate)

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

    // init FP
    cb.add(Instr.Move(Reg(SP_REG), FP_REG))

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

  /** Allocate a block of memory and push the start address onto value stack */
  def alloc(size: Int)(using ctx: Context): Unit =
    ctx.vs.push(Int32(size))
    call(Predef.allocate)

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

  def primitive(sym: Symbol)(using Context): Unit =
    sym match
      case Predef.add    =>   int2(Instr.Add)
      case Predef.sub    =>   int2(Instr.Sub)
      case Predef.mul    =>   int2(Instr.Mul)
      case Predef.div    =>   int2(Instr.Div)
      case Predef.mod    =>   int2(Instr.Mod)
      case Predef.gt     =>   int2(Instr.Gt)
      case Predef.lt     =>   int2(Instr.Lt)
      case Predef.ge     =>   int2(Instr.Ge)
      case Predef.le     =>   int2(Instr.Le)
      case Predef.srl    =>   int2(Instr.Srl)
      case Predef.sll    =>   int2(Instr.Sll)
      case Predef.land   =>   int2(Instr.And)
      case Predef.lor    =>   int2(Instr.Or)
      case Predef.lxor   =>   int2(Instr.Xor)
      case Predef.band   =>   int2(Instr.And)
      case Predef.bor    =>   int2(Instr.Or)
      case Predef.bnot   =>   bnot()
      case Predef.eql    =>   eql()
      case Predef.p      =>   call(Predef.p)
      case Predef.print  =>   call(Predef.print)
      case Predef.abort  =>   call(Predef.abort)
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

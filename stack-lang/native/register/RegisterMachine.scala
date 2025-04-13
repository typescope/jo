package native.register

import common.Debug

import sast.*
import sast.Sast.*
import sast.Symbols.*
import sast.Types.*

import native.Backend

import native.Assembly
import native.Assembly.{ Type => _, * }

import native.arch.X86
import native.os.Linux
import native.runtime.NativeRuntime
import native.runtime.BumpAllocator

import PreAssembly.*
import CallConvention.*
import RegisterMachine.*

import scala.collection.mutable

/** Fast implementation with register allocation
  *
  * The class is arch- and OS-agnostic.
  */
class RegisterMachine(
  registerConfig: RegisterConfig,
  callConvention: CallConvention,
  runtime: NativeRuntime,
  rules: GraphColoring.PlatformRules)
extends Backend(runtime):

  import registerConfig.{ FP_REG, SP_REG }

  type Context = FunctionContext

  val String_fromByteString = runtime.Core_String_fromByteString

  /** A dummy parameter representing the return address
    *
    * Its type does not matter.
    */
  val returnAddrSym = Symbol.createSymbol("return", AnyType, Flags.Synthetic, owner = runtime.Core, pos = runtime.Core.sourcePos)

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

  def compile(block: Block)(using Context): Unit =
    for word <- block.words do compile(word)

  def compile(word: Word)(using ctx: Context): Unit = Debug.trace("Compiling " + word.show, enable = false):
    word match
      case Literal(c) =>
        c match
          case Constant.Bool(b) =>
            ctx.vs.push(Int32(if b then 1 else 0))

          case Constant.String(s) =>
            val label = addString(s)
            val reg = freshVirtualReg()
            gen(Instr.Move(label, reg))
            ctx.vs.push(Reg(reg))

            // Context parameter runtime expects raw string as input
            if !word.tpe.isAnyType then
              call(String_fromByteString)

          case Constant.Int(n) =>
            ctx.vs.push(Int32(n))

      case block: Block => compile(block)

      case encoded: Encoded => compile(encoded)

      case app: Apply => compile(app)

      case TypeApply(fun, _) => compile(fun)

      case assign: Assign => compile(assign)

      case ifElse: If => compile(ifElse)

      case whileDo: While => compile(whileDo)

      case id: Ident => compile(id)

      case _: TypeDef =>

      case _: ValDef | _: FunDef | _: With | _: Allow | _: Select |
           _: FieldAssign | _: RecordLit | _: Object | _: Match |
           _: TaggedLit | _: PatDef =>
        throw new Exception("Unexpected " + word)

  def load(loc: Location, dest: Int, base: Rel)(using Context): Unit =
    loc match
      case Location.Reg(reg) =>
        gen(Instr.Move(Reg(reg), dest))

      case loc: Location.Stack =>
        val addr = Location.map(loc, base)
        // TODO: pass in size
        gen(Instr.Load(addr, dest, Size.B32))
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

  def compileFunDef(fdef: FunDef)(using cb: CodeBuffer): Unit = try
    val sym = fdef.symbol
    val ctx = freshFunctionContext(sym)

    val proto = compile(fdef)(using ctx)

    // println(ctx.buffer.show)

    // perform register allocation
    assert(ctx.vs.size == 0, sym.name + ", ctx.vs.size = " + ctx.vs.size)
    val label = getFunAddress(sym)
    doGraphColoring(
      label, ctx.buffer.getResult(), registerConfig, proto.savedRegs, cb,
      ctx.generator, rules)
  catch
    case e: Throwable =>
      println(fdef.show)
      throw e

  /** Compile a function */
  def compile(fdef: FunDef)(using ctx: Context): Protocol =
    val sym = fdef.symbol
    val funType = sym.info.asProcType

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
    val funType = fun.info.asProcType
    val target = getFunAddress(fun)
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
      else throw new Exception("assigning to non-local " + sym + ", owner = " + sym.owner)
    gen(instr)

  /** Compile a reference to a name that produces a runtime value */
  def compile(id: Ident)(using ctx: Context): Unit =
    val sym = id.symbol
    if sym.is(Flags.Fun) then
      val target = getFunAddress(id.symbol)
      val targetReg = freshVirtualReg()
      gen(Instr.Move(target, targetReg))
      ctx.vs.push(Reg(targetReg))
    else
      if sym.isLocal then
        val reg = ctx.getRegForLocal(sym)
        ctx.vs.push(Reg(reg))
      else
        throw new Exception("accessing non-local variable " + sym)
        // val reg = freshVirtualReg()
        // val addr = getAddress(sym)
        // gen(Instr.Load(addr, reg))

  /** Compile function call */
  def compile(app: Apply)(using ctx: Context): Unit =
    app.funSymbol match
      case Some(sym) =>
        if sym.owner == Definitions.instance.Predef then
          for arg <- app.args do compile(arg)
          callPredef(sym)
        else if sym.owner == runtime.Core then
          if sym == runtime.Core_data then
            // TODO: error instead of crash -- in early phases
            val Literal(Constant.String(qualid)) :: Nil = app.args: @unchecked
            val label = runtime.locate(qualid) match
              case Some(label) => label
              case None => throw new Exception("Runtime data not defined: " + qualid)

            val targetReg = freshVirtualReg()
            gen(Instr.Move(label, targetReg))
            ctx.vs.push(Reg(targetReg))
          else
            for arg <- app.args do compile(arg)
            callCore(sym)
        else
          for arg <- app.args do compile(arg)
          call(sym)

      case _ =>
        compile(app.fun)
        val fun = ctx.vs.pop().asInstanceOf[Reg]
        val funType = app.fun.tpe.asProcType

        for arg <- app.args do compile(arg)
        this.call(fun, funType.paramTypes, funType.resultType)

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
      case defn.Predef_both   =>   int2(Instr.And)
      case defn.Predef_either =>   int2(Instr.Or)
      case defn.Predef_not    =>   bnot()
      case defn.Predef_eql    =>   eql()
      case _                  =>   call(sym)
  end callPredef

  def callCore(sym: Symbol)(using ctx: Context): Unit =
    sym match
      case runtime.Core_addAddr   => int2(Instr.Add)

      case runtime.Core_writeInt  =>
        val v = ctx.vs.pop()
        val Reg(addr) = ctx.vs.pop(): @unchecked
        gen(Instr.Store(v, Reg(addr)))
        // push dummy value to conform to signature
        ctx.vs.push(Int32(0))

      case runtime.Core_readInt   =>
        val Reg(reg) = ctx.vs.pop(): @unchecked
        val regResult = freshVirtualReg()
        gen(Instr.Load(Reg(reg), regResult, Size.B32))
        ctx.vs.push(Reg(regResult))

      case runtime.Core_writeByte =>
        val v = ctx.vs.pop()
        val Reg(addr) = ctx.vs.pop(): @unchecked

        val reg8 =
          v match
            case Int32(n) =>
              assert(n >= 0 && n < 256, "overflow for writeByte: " + n)
              val reg = freshVirtualReg()
              gen(Instr.Move(v, reg))
              Reg8(reg)

            case Reg(reg) =>
              Reg8(reg)
          end match

        gen(Instr.Store(reg8, Reg(addr)))
        // push dummy value to conform to signature
        ctx.vs.push(Int32(0))

      case runtime.Core_readByte  =>
        val Reg(reg) = ctx.vs.pop(): @unchecked
        val regResult = freshVirtualReg()
        gen(Instr.Load(Reg(reg), regResult, Size.B8))
        ctx.vs.push(Reg(regResult))

      case _ => call(sym)

  /** Load a value relative to the stack pointer.
    *
    * The index begins from 0.
    */
  def loadValue(destReg: Int, index: Int)(using Context): Unit =
    val addr = Rel(SP_REG, index << 2)
    // TODO: pass in size
    gen(Instr.Load(addr, destReg, Size.B32))

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
      assert(!localsToReg.contains(local), "duplicate symbol " + local + " in " + fun)
      localsToReg(local) = reg

  def freshFunctionContext(fun: Symbol): FunctionContext =
    val localsMap = mutable.Map.empty[Symbol, Int]
    val vs = new ValueStack
    val generator = new VirtualRegGenerator
    val buffer = new PreAssembly.ItemBuffer
    FunctionContext(fun, vs, generator, buffer, localsMap)

  /**
    * Create a new x86 register machine
    */
  def createLinux86(runtimeRootNameTable: NameTable, main: Symbol): Backend =
    val bumpAllocator = new BumpAllocator(runtimeRootNameTable)
    val syscalls = Linux.createSyscallRegister(runtimeRootNameTable)
    val linkers = List(bumpAllocator, syscalls)
    val runtime = new NativeRuntime(runtimeRootNameTable, linkers, main)

    val paramRegs: List[Int] = List(X86.EAX, X86.EBX, X86.ECX, X86.EDX)
    val callConv =
      new CallConvention.RegisterCallConvention(Linux.x86RegConfig, paramRegs)

    val x86rules = new GraphColoring.PlatformRules:
      def conflicts(info: Liveness.Info): List[(Int, Int)] =
        // The only available r8 registers on x86 are AL, CL, DL, BL, AH, CH, DH, BH
        for
          reg2 <- List(X86.ESP, X86.EBP, X86.ESI, X86.EDI)
          reg1 <- info.bit8Regs
        yield
          reg1 -> reg2

    new RegisterMachine(Linux.x86RegConfig, callConv, runtime, x86rules)

  def main(args: Array[String]): Unit = native.Compiler.compile(createLinux86, args)

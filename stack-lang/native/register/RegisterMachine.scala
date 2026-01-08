package native.register

import common.Debug
import reporting.Reporter

import sast.*
import sast.Trees.*
import sast.Symbols.*
import sast.Types.*

import native.Backend

import native.Assembly.{ Type => _, * }

import native.arch.X86
import native.os.Linux
import native.runtime.NativeRuntime

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
  (using defn: Definitions, rp: Reporter)
extends Backend(runtime):

  // suppress not used warning
  val _ = rp.hasErrors

  import registerConfig.{ FP_REG, SP_REG }

  type Context = FunctionContext

  val String_fromByteString = runtime.Core_String_fromByteString

  /** A dummy parameter representing the return address
    *
    * Its type does not matter.
    */
  val returnAddrSym = TermSymbol.create("return", AnyType, Flags.Synthetic,
      visibility = Visibility.Default,
      owner = runtime.Core,
      pos = runtime.Core.sourcePos)

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

          case Constant.Float(_) =>
            throw new Exception("Floating point not supported for native backend")

      case block: Block => compile(block)

      case encoded: Encoded => compile(encoded)

      case app: Apply => compile(app)

      case TypeApply(fun, _) => compile(fun)

      case assign: Assign => compile(assign)

      case ifElse: If => compile(ifElse)

      case whileDo: While => compile(whileDo)

      case id: Ident => compile(id)

      case _: TypeDef =>

      case _: Def         | _: With      | _: Allow  | _: Select  |
           _: FieldAssign | _: RecordLit | _: Match  | _: CaseDef |
           _: New         | _: IsExpr    | _: Lambda
      =>
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

    val proto = compile(fdef)(using ctx) // <| (sym.name + " preassembly")

    // println(ctx.buffer.show)

    // perform register allocation
    assert(ctx.vs.size == 0, sym.name + ", ctx.vs.size = " + ctx.vs.size)
    val label = getFunAddress(sym)
    doGraphColoring(
      label, ctx.buffer.getResult(), registerConfig, proto.savedRegs, cb,
      ctx.generator, rules)  // <| (sym.name + " register alloc")
  catch
    case e: Throwable =>
      println(fdef.show)
      throw e

  /** Compile a function */
  def compile(fdef: FunDef)(using ctx: Context): Protocol =
    val sym = fdef.symbol
    val funType = sym.info.asProcType

    val proto @ Protocol(inProto, outProto, savedRegs) =
      callConvention.callee(funType.allParamTypes, funType.resultType)

    // Compile function to a temporary buffer for register allocation
    gen(PlaceHolder.InitStackPointer)

    // callee-saved registers
    gen(PlaceHolder.CalleeSaveRegisters)

    val base = Rel(FP_REG, (inProto.stackItemCount - 1) << 2)

    // bind param to virtual registers and load data
    for (param, loc) <- fdef.allParams.zip(inProto.paramLocs) do
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
          assert(!ifword.elsep.isEmpty, "else empty")
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
    call(target, funType.allParamTypes, funType.resultType)

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

    if sym.isLocal then
      val addr = ctx.getRegForLocal(sym)
      gen(Instr.Move(rhsValue, addr))

    else if sym.is(Flags.Object) then
      val targetReg = freshVirtualReg()
      val addr = runtime.getObjectHolderByDataSymbol(sym)
      gen(Instr.Move(rhsValue, targetReg))
      gen(Instr.Store(Reg(targetReg), addr))

    else
      throw new Exception("assigning to non-local " + sym + ", owner = " + sym.owner)

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
        if sym.isMutable then
          val targetReg = freshVirtualReg()
          gen(Instr.Move(Reg(reg), targetReg))
          ctx.vs.push(Reg(targetReg))

        else
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
        if sym.owner == runtime.Core then
          if sym == runtime.Core_state then
            val label = runtime.runtimeStateLabel
            val targetReg = freshVirtualReg()
            gen(Instr.Move(label, targetReg))
            ctx.vs.push(Reg(targetReg))

          else
            for arg <- app.allArgs do compile(arg)
            callCore(sym)

        else if sym.owner == defn.Bool then
          // Bool operators (&&, ||, !) are still top-level in Bool namespace
          for arg <- app.allArgs do compile(arg)
          callBoolPrimitive(sym)

        else if sym.owner == runtime.Core_IntOps then
          for arg <- app.allArgs do compile(arg)
          callIntPrimitive(sym)

        else if sym.owner == runtime.Core_ByteOps then
          for arg <- app.allArgs do compile(arg)
          callBytePrimitive(sym)

        else if sym.owner == runtime.Core_CharOps then
          for arg <- app.allArgs do compile(arg)
          callCharPrimitive(sym)

        else if sym.owner == runtime.Core_FloatOps then
          for arg <- app.allArgs do compile(arg)
          callFloatPrimitive(sym)

        else if sym.is(Flags.Object) && !this.isLoweringObjectInitProc then
          // make the accessor reachable
          getFunAddress(sym)

          // skip the call and access directly the object
          val objAddr = runtime.getObjectHolder(sym)
          val reg = freshVirtualReg()
          gen(Instr.Load(objAddr, reg, Size.B32))
          ctx.vs.push(Reg(reg))

        else
          for arg <- app.allArgs do compile(arg)
          call(sym)

      case _ =>
        compile(app.fun)
        val fun = ctx.vs.pop().asInstanceOf[Reg]
        val funType = app.fun.tpe.asProcType

        for arg <- app.allArgs do compile(arg)
        this.call(fun, funType.allParamTypes, funType.resultType)

  def callBoolPrimitive(sym: Symbol)(using Context): Unit =
    sym match
      case defn.Bool_both   => int2(Instr.And)
      case defn.Bool_either => int2(Instr.Or)
      case defn.Bool_not    => bnot()
      case _                => call(sym)
  end callBoolPrimitive

  def callIntPrimitive(sym: Symbol)(using ctx: Context): Unit =
    sym match
      case runtime.Core_Int_add  => int2(Instr.Add)
      case runtime.Core_Int_sub  => int2(Instr.Sub)
      case runtime.Core_Int_mul  => int2(Instr.Mul)
      case runtime.Core_Int_div  => int2(Instr.Div)
      case runtime.Core_Int_mod  => int2(Instr.Mod)
      case runtime.Core_Int_gt   => int2(Instr.Gt)
      case runtime.Core_Int_lt   => int2(Instr.Lt)
      case runtime.Core_Int_ge   => int2(Instr.Ge)
      case runtime.Core_Int_le   => int2(Instr.Le)
      case runtime.Core_Int_eq   => eql()
      case runtime.Core_Int_ne   =>
        eql()  // Compare for equality
        bnot() // Negate the result
      case runtime.Core_Int_srl  => int2(Instr.Srl)
      case runtime.Core_Int_sll  => int2(Instr.Sll)
      case runtime.Core_Int_land => int2(Instr.And)
      case runtime.Core_Int_lor  => int2(Instr.Or)
      case runtime.Core_Int_lxor => int2(Instr.Xor)
      case runtime.Core_Int_toChar =>
        // No-op: Char is represented by Int
        // No handling of surrogate code points
      case runtime.Core_Int_toByte =>
        val v = ctx.vs.pop()
        val r = freshVirtualReg()
        gen(Instr.And(v, Int32(0xFF), r))
        ctx.vs.push(Reg(r))
      case _                     => call(sym)
  end callIntPrimitive

  def callBytePrimitive(sym: Symbol)(using ctx: Context): Unit =
    sym match
      case runtime.Core_Byte_eq => eql()
      case runtime.Core_Byte_ne =>
        eql()
        bnot()
      case runtime.Core_Byte_gt => int2(Instr.Gt)
      case runtime.Core_Byte_lt => int2(Instr.Lt)
      case runtime.Core_Byte_ge => int2(Instr.Ge)
      case runtime.Core_Byte_le => int2(Instr.Le)
      case runtime.Core_Byte_toInt => () // No-op: Byte is already represented as Int
      case runtime.Core_Byte_toChar => () // No-op: Byte (0-255) fits in Char
      case _                    => call(sym)
  end callBytePrimitive

  def callCharPrimitive(sym: Symbol)(using ctx: Context): Unit =
    sym match
      case runtime.Core_Char_eq => eql()
      case runtime.Core_Char_ne =>
        eql()
        bnot()
      case runtime.Core_Char_gt => int2(Instr.Gt)
      case runtime.Core_Char_lt => int2(Instr.Lt)
      case runtime.Core_Char_ge => int2(Instr.Ge)
      case runtime.Core_Char_le => int2(Instr.Le)
      case runtime.Core_Char_toByte =>
        val v = ctx.vs.pop()
        val r = freshVirtualReg()
        gen(Instr.And(v, Int32(0xFF), r))
        ctx.vs.push(Reg(r))
      case runtime.Core_Char_toInt => () // No-op: Char is already represented as Int
      case _                    => call(sym)
  end callCharPrimitive

  def callFloatPrimitive(sym: Symbol)(using ctx: Context): Unit =
    throw new Exception("Float primitive operations not yet implemented in native backend: " + sym)
  end callFloatPrimitive

  def callCore(sym: Symbol)(using ctx: Context): Unit =
    sym match
      case runtime.Core_addAddr => int2(Instr.Add)

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

object RegisterMachine extends native.Compiler.BackendBuilder:
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
  def createLinux86(rewire: Map[Symbol, Symbol])(using Reporter, Definitions): Backend =
    val syscalls = Linux.createSyscallRegister()
    val linkers = List(syscalls)
    val runtime = new NativeRuntime(linkers, rewire)

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

  def main(args: Array[String]): Unit =
    native.Compiler.compile(this, args)

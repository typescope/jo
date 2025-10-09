package native.stack

import common.Debug
import reporting.Reporter

import sast.*
import sast.Trees.*
import sast.Symbols.*

import native.Backend

import native.Assembly
import native.Assembly.*

import native.os.Linux

import native.runtime.NativeRuntime
import native.runtime.BumpAllocator

import StackMachine.RegisterAllocator

import scala.collection.mutable

/**
  * Implementation based on a stack machine.
  *
  * The class is arch- and OS-agnostic.
  */
class StackMachine(
  registerConfig: RegisterConfig, runtime: NativeRuntime)
  (using defn: Definitions, rp: Reporter)
extends Backend(runtime):

  // suppress not used warning
  val _ = rp.hasErrors

  import registerConfig.{ FP_REG, SP_REG, FREE_REGS }

  type LocalAddr = Map[Symbol, Addr]

  val String_fromByteString = runtime.Core_String_fromByteString

  /** Program entry pointer */
  val entry = Label("_entry")

  /** A simple register allocator */
  val regAlloc = new RegisterAllocator(FREE_REGS)

  export regAlloc.{ useReg, useTwoReg }

  def compile(block: Block)(using LocalAddr, CodeBuffer): Unit =
    for word <- block.words do compile(word)

  def compile(word: Word)(using addr: LocalAddr, cb: CodeBuffer): Unit = Debug.trace("Compiling " + word.show, enable = false):
    word match
      case Literal(c) =>
        c match
          case Constant.Bool(b) =>
            push(Int32(if b then 1 else 0))

          case Constant.String(s) =>
            val label = addString(s)

            useReg: r =>
              cb.add(Instr.Move(label, r))
              push(Reg(r))

            // Context parameter runtime expects raw string as input
            if !word.tpe.isAnyType then
              call(String_fromByteString)

          case Constant.Int(n) =>
            push(Int32(n))

      case block: Block => compile(block)

      case encoded: Encoded => compile(encoded)

      case app: Apply => compile(app)

      case TypeApply(fun, _) => compile(fun)

      case assign: Assign => compile(assign)

      case ifElse: If => compile(ifElse)

      case whileDo: While => compile(whileDo)

      case id: Ident => compile(id)

      case _: TypeDef =>

      case _: Def         | _: With      | _: Allow | _: Select |
           _: FieldAssign | _: RecordLit | _: Object | _: Match |
           _: TaggedLit   | _: New =>
        throw new Exception("Unexpected " + word)

  /** Compile a function */
  def compileFunDef(fdef: FunDef)(using cb: CodeBuffer): Unit = try
    val sym = fdef.symbol
    val funType = sym.info.asProcType

    val label = getFunAddress(sym)

    val paramCount = funType.allParamCount
    val resCount = funType.resCount

    cb.mark(label)

    val symAddrMap = mutable.Map.empty[Symbol, Addr]

    // bind param address relative to FP_REG
    for (param, index) <- fdef.allParams.zipWithIndex do
      val offset = (paramCount + 1 - index) << 2
      symAddrMap(param) = Rel(FP_REG, offset)

    // the ordering does not matter
    for (local, index) <- fdef.locals.zipWithIndex do
      val offset = -(index + 1) << 2
      assert(!symAddrMap.contains(local), "Double binding " + local + " in " + sym)
      symAddrMap(local) = Rel(FP_REG, offset)

    val sizeLocals = fdef.locals.size << 2
    cb.add(Instr.Move(Reg(SP_REG), FP_REG))
    cb.add(Instr.Sub(Reg(SP_REG), Int32(sizeLocals), SP_REG))

    compile(fdef.body)(using symAddrMap.toMap, cb)
    ret(resCount)
  catch
    case e: Throwable =>
      println("locals = " + fdef.locals)
      println(fdef.show)
      throw e

  def compile(ifword: If)(using addr: LocalAddr, cb: CodeBuffer): Unit =
    val labelFalse = Label("_false")
    val labelEnd = Label("_ifEnd")

    compile(ifword.cond)

    useReg: r =>
      pop(r, Size.B32)
      val target = if ifword.elsep.isEmpty then labelEnd else labelFalse
      cb.add(Instr.JZero(Reg(r), target))

    compile(ifword.thenp)

    if !ifword.elsep.isEmpty then
      cb.add(Instr.Jump(labelEnd))
      cb.mark(labelFalse)
      compile(ifword.elsep)

    cb.mark(labelEnd)

  def compile(whileDo: While)(using addr: LocalAddr, cb: CodeBuffer): Unit =
    val labelBegin = Label("_whileBegin")
    val labelEnd = Label("_whileEnd")

    cb.mark(labelBegin)
    compile(whileDo.cond)
    useReg: r =>
      pop(r, Size.B32)
      cb.add(Instr.JZero(Reg(r), labelEnd))

      compile(whileDo.body)

      cb.add(Instr.Jump(labelBegin))
      cb.mark(labelEnd)

  def compile(encoded: Encoded)(using LocalAddr, CodeBuffer): Unit =
    compile(encoded.repr)
    if encoded.isValueDrop then
      pop(Size.B32)

  /** Return from a procedure or function.
    *
    * Stack goes from high address to low address.
    */
  def ret(resCount: Int)(using cb: CodeBuffer) =
    var i = resCount - 1
    while i >= 0 do
      val src = Rel(SP_REG, i << 2)
      val dest = Rel(FP_REG, (i - resCount) << 2)
      useReg: r =>
        cb.add(Instr.Load(src, r, Size.B32))
        cb.add(Instr.Store(Reg(r), dest))
      i -= 1

    useReg: r =>
      cb.add(Instr.Load(Reg(FP_REG), r, Size.B32))
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
  def call(fun: Symbol)(using cb: CodeBuffer): Unit =
    val addr = getFunAddress(fun)
    val funType = fun.info.asProcType
    val argCount = funType.allParamCount
    val resCount = funType.resCount
    call(addr, argCount, resCount)

  def call(addr: Addr, argCount: Int, resCount: Int, funAddrOnStack: Boolean = false)(using cb: CodeBuffer): Unit =
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
      cb.add(Instr.Load(fpAddr, FP_REG, Size.B32))

      // 7. copy result -- after restoring FP to avoid overwriting
      var i = 0
      while i < resCount do
        val src = Rel(SP_REG, (-spOffset - i - 1) << 2)
        val dest = Rel(SP_REG, (resCount - i - 1) << 2)
        cb.add(Instr.Load(src, r, Size.B32))
        cb.add(Instr.Store(Reg(r), dest))
        i += 1

  /** Compile x = e */
  def compile(assign: Assign)(using addr: LocalAddr, cb: CodeBuffer): Unit =
    val loc = addr(assign.symbol)
    compile(assign.rhs)
    useReg: r =>
      pop(r, Size.B32)
      cb.add(Instr.Store(Reg(r), loc))

  /** Compile a reference */
  def compile(ref: Ident)(using addr: LocalAddr, cb: CodeBuffer): Unit =
    val sym = ref.symbol
    if sym.is(Flags.Fun) then
      val label = getFunAddress(sym)
      push(label)
    else
      val loc =
        if sym.isLocal then
          addr.get(sym) match
            case Some(loc) => loc
            case None => throw new Exception("Not found local symbol: " + sym)
        else
          throw new Exception("accessing non-local variable " + sym + ", owner = " + sym.owner)

      useReg: r =>
        cb.add(Instr.Load(loc, r, Size.B32))
        push(Reg(r))

  /** Compile function call */
  def compile(app: Apply)(using LocalAddr, CodeBuffer): Unit =
    app.funSymbol match
      case Some(sym) =>
        if sym.owner == defn.Int || sym.owner == defn.Bool then
          for arg <- app.allArgs do compile(arg)
          callPrimitive(sym)

        else if sym.owner == runtime.Core then
          if sym == runtime.Core_data then
            // TODO: error instead of crash -- in early phases
            val Literal(Constant.String(qualid)) :: Nil = app.args: @unchecked
            val Some(label) = runtime.locate(qualid): @unchecked
            push(label)

          else
            for arg <- app.allArgs do compile(arg)
            callCore(sym)

        else
          for arg <- app.allArgs do compile(arg)
          call(sym)

      case _ =>
        compile(app.fun)
        for arg <- app.allArgs do compile(arg)

        useReg: r =>
          val resCount = if app.tpe.isValueType then 1 else 0
          loadValue(r, app.allArgs.size.toByte, Size.B32)
          this.call(Reg(r), app.args.size, resCount, funAddrOnStack = true)

  /** Pop the value on the top of the stack to the given register */
  def pop(destReg: Int, size: Size)(using cb: CodeBuffer) =
    assert(size == Size.B32)
    cb.add(Instr.Load(Reg(SP_REG), destReg, size))
    cb.add(Instr.Add(Reg(SP_REG), Int32(4), SP_REG))

  /** Pop the value on the top of the stack without using it */
  def pop(size: Size)(using cb: CodeBuffer) =
    assert(size == Size.B32)
    cb.add(Instr.Add(Reg(SP_REG), Int32(4), SP_REG))

  /** Push value or address on the stack */
  def push(v: Value)(using cb: CodeBuffer) =
    cb.add(Instr.Sub(Reg(SP_REG), Int32(4), SP_REG))
    cb.add(Instr.Store(v, Reg(SP_REG)))

  def callPrimitive(sym: Symbol)(using cb: CodeBuffer): Unit =

    sym match
      case defn.Int_add    =>   int2(Instr.Add)
      case defn.Int_sub    =>   int2(Instr.Sub)
      case defn.Int_mul    =>   int2(Instr.Mul)
      case defn.Int_div    =>   int2(Instr.Div)
      case defn.Int_mod    =>   int2(Instr.Mod)
      case defn.Int_gt     =>   int2(Instr.Gt)
      case defn.Int_lt     =>   int2(Instr.Lt)
      case defn.Int_ge     =>   int2(Instr.Ge)
      case defn.Int_le     =>   int2(Instr.Le)
      case defn.Int_srl    =>   int2(Instr.Srl)
      case defn.Int_sll    =>   int2(Instr.Sll)
      case defn.Int_land   =>   int2(Instr.And)
      case defn.Int_lor    =>   int2(Instr.Or)
      case defn.Int_lxor   =>   int2(Instr.Xor)
      case defn.Int_eql    =>   eql()

      case defn.Bool_both   =>   int2(Instr.And)
      case defn.Bool_either =>   int2(Instr.Or)
      case defn.Bool_not    =>   bnot()

      case _                 =>   call(sym)
  end callPrimitive

  def callCore(sym: Symbol)(using cb: CodeBuffer): Unit =
    sym match
      case runtime.Core_addAddr   => int2(Instr.Add)

      case runtime.Core_writeInt  =>
        useTwoReg: (r1, r2) =>
          pop(r1, Size.B32)
          pop(r2, Size.B32)
          cb.add(Instr.Store(Reg(r1), Reg(r2)))
          // push dummy value to conform to signature
          push(Int32(0))

      case runtime.Core_readInt   =>
        useReg: r =>
          pop(r, Size.B32)
          cb.add(Instr.Load(Reg(r), r, Size.B32))
          push(Reg(r))

      case runtime.Core_writeByte =>
        useTwoReg: (r1, r2) =>
          pop(r1, Size.B32)
          pop(r2, Size.B32)
          cb.add(Instr.Store(Reg8(r1), Reg(r2)))
          // push dummy value to conform to signature
          push(Int32(0))

      case runtime.Core_readByte  =>
        useTwoReg: (r1, r2) =>
          pop(r1, Size.B32)
          cb.add(Instr.Load(Reg(r1), r2, Size.B8))
          push(Reg(r2))

      case _ => call(sym)

  /** Duplicate the value on the top of stack. */
  def dup(size: Size)(using CodeBuffer) =
    useReg: r =>
      loadValue(r, 0, size)
      push(Reg(r))

  /** Load a value on stack relative to the stack pointer.
    *
    * The index begins from 0.
    */
  def loadValue(destReg: Int, index: Byte, size: Size)(using cb: CodeBuffer): Unit =
    val addr = Rel(SP_REG, index << 2)
    cb.add(Instr.Load(addr, destReg, size))

  /** Store a value to stack relative to the stack pointer.
    *
    * The index begins from 0.
    */
  def storeValue(value: Value, index: Byte)(using cb: CodeBuffer): Unit =
    val addr = Rel(SP_REG, index << 2)
    // TODO: pass in size
    cb.add(Instr.Store(value, addr))

  def int2(fn: (Operand, Operand, Int) => Instr)(using cb: CodeBuffer) =
    useTwoReg: (r1, r2) =>
      // Reduce arithmetic on stack pointer to 1
      loadValue(r1, 1, Size.B32)
      loadValue(r2, 0, Size.B32)
      cb.add(fn(Reg(r1), Reg(r2), r1))
      storeValue(Reg(r1), 1)
      pop(Size.B32)

  def bnot()(using cb: CodeBuffer) =
    useReg: r =>
      loadValue(r, 0, Size.B32)
      cb.add(Instr.Nor(Reg(r), Reg(r), r))
      cb.add(Instr.And(Reg(r), Int32(1), r))
      storeValue(Reg(r), 0)

  def eql()(using cb: CodeBuffer) =
    useTwoReg: (r1, r2) =>
      loadValue(r1, 0, Size.B32)
      loadValue(r2, 1, Size.B32)
      cb.add(Instr.Eq(Reg(r1), Reg(r2), r2))
      storeValue(Reg(r2), 1)
      pop(Size.B32)

end StackMachine

object StackMachine extends native.Compiler.BackendBuilder:
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
  end RegisterAllocator

  def createLinux86(rewire: Map[Symbol, Symbol])(using Reporter, Definitions): Backend =
    val bumpAllocator = new BumpAllocator
    val syscalls = Linux.createSyscallStack()
    val linkers = List(bumpAllocator, syscalls)
    val runtime = new NativeRuntime(linkers, rewire)

    new StackMachine(Linux.x86RegConfig, runtime)

  def main(args: Array[String]): Unit =
    native.Compiler.compile(this, args)

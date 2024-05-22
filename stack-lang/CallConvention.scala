import scala.collection.mutable

import Sast.StackInfo
import Assembly.RegisterConfig

import CallConvention.*

/**
  * Calling convention is a protocol between caller and callee:
  *
  * - How function arguments/returns are passed
  * - How registers are saved/restored
  * - How to return from a function call
  *
  * Calling conventions are OS-independent and agnostic to register allocation
  * algorithms.
  */
trait CallConvention:
  def caller(funInfo: StackInfo): CallerProtocol
  def callee(funInfo: StackInfo): CalleeProtocol

object CallConvention:
  enum Location:
    case Mem(baseReg: Int, offset: Int)
    case Reg(index: Int)

  /** Argument and return address must be at fixed locations known to callee */
  enum Fixed:
    case Argument(index: Int)
    case ReturnAddress

  /** Reserved and caller saved registers can be placed on stack positions
    * unknown to the calee.
    */
  enum Flex:
    val reg: Int
    case Reserved(reg: Int)
    case CallerSaved(reg: Int)

  case class CallerProtocol(
    inRegs: Map[Fixed, Int],
    onStack: List[Fixed | Flex],
    resLocs: List[Location]):

    val argRegs: List[Int] = inRegs.values.toList

    val resRegs: List[Int] =
      resLocs.flatMap:
        case Location.Reg(index) => index :: Nil
        case _ => Nil

  case class CalleeProtocol(
    paramLocs: List[Location],
    retLoc: Location,
    resLocs: List[Location],
    savedRegs: List[Int])

  /**
    * A calling convention that passes first 4 arguments via registers and all
    * registers are callee-saved.
    *
    *
    * Call stack goes from high address to low address.
    *
    * Call stack
    *
    *  ┌─────────────┐
    *  │    ...      │
    *  ├─────────────┤
    *  │    arg 4    │
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
    *
    */
  class RegisterCallConvention(
    registerConfig: RegisterConfig, PARAM_REGS: List[Int])
  extends CallConvention:
    import registerConfig.{ FP_REG, SP_REG, FREE_REGS }

    /** Parameter locations in a function call */
    private def paramLocations(paramCount: Int): List[Location] =
      val regNum = regArgNum(paramCount)
      val stackNum = paramCount - regNum
      // 2 for FP and return address
      val firstStackItem = (stackNum + 2 - 1) << 2
      computeLocations(paramCount, regNum, FP_REG, firstStackItem)

    private def regArgNum(argCount: Int): Int =
      if argCount <= PARAM_REGS.size then argCount
      else PARAM_REGS.size

    private def regResNum(resCount: Int): Int =
      if resCount <= PARAM_REGS.size then resCount
      else PARAM_REGS.size

    /** Argument locations in a function call */
    private def resLocations(resCount: Int): List[Location] =
      computeLocations(resCount, regResNum(resCount), FP_REG, -4)

    private def computeLocations(
      valueCount: Int,
      regValueNum: Int,
      baseReg: Int,
      startOffset: Int): List[Location] =

      val buffer = new mutable.ArrayBuffer[Location]

      var i = 0
      while i < valueCount do
        // ordering of args
        if i < regValueNum then
          buffer += Location.Reg(PARAM_REGS(i))
        else
          val delta = (i - regValueNum) << 2
          val offset = startOffset - delta
          val loc = Location.Mem(baseReg, offset)
          buffer += loc
        i += 1
      end while
      buffer.toList

    private def callerSaved(argCount: Int, resCount: Int): List[Int] =
      val delta = if resCount > argCount then resCount - argCount else 0
      PARAM_REGS.drop(argCount).take(delta)

    private def calleeSaved(argCount: Int, resCount: Int): List[Int] =
      val callerHandled = if resCount > argCount then resCount else argCount
      FREE_REGS.diff(PARAM_REGS.take(callerHandled))

    def caller(funInfo: StackInfo): CallerProtocol =
      val StackInfo(argCount, resCount) = funInfo

      val onStack = mutable.ArrayBuffer.empty[Fixed | Flex]
      val inRegs = mutable.Map.empty[Fixed, Int]
      val argInRegsNum = regArgNum(argCount)

      for i <- 0 until argInRegsNum do
        inRegs(Fixed.Argument(i)) = PARAM_REGS(i)

      for reg <- callerSaved(argCount, resCount) do
        onStack += Flex.CallerSaved(reg)

      for i <- argInRegsNum until argCount do
        onStack += Fixed.Argument(i)

      onStack += Flex.Reserved(FP_REG)
      onStack += Fixed.ReturnAddress

      val resLocs = resLocations(resCount)

      CallerProtocol(inRegs.toMap, onStack.toList, resLocs)

    def callee(funInfo: StackInfo): CalleeProtocol =
      val StackInfo(argCount, resCount) = funInfo

      val paramLocs = paramLocations(argCount)
      val retLoc = Location.Mem(FP_REG, 0)
      val resLocs = resLocations(resCount)
      val savedRegs = calleeSaved(argCount, resCount)
      CalleeProtocol(paramLocs, retLoc, resLocs, savedRegs)

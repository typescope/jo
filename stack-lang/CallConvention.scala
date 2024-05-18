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
    case Stack(baseReg: Int, offset: Int)
    case Reg(index: Int)

  case class CallerProtocol(
    argLocs: List[Location],
    resLocs: List[Location],
    savedRegs: List[Int]):

    val stackArgNum: Int = argLocs.filter(_.isInstanceOf[Location.Stack]).size

    val argRegs: List[Int] =
      argLocs.flatMap:
        case Location.Reg(index) => index :: Nil
        case _ => Nil

    val resRegs: List[Int] =
      resLocs.flatMap:
        case Location.Reg(index) => index :: Nil
        case _ => Nil

  case class CalleeProtocol(
    paramLocs: List[Location],
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
      val regArgNum = paramCount - stackArgNum(paramCount)
      // 2 for FP and return address
      val firstStackItem = (stackArgNum(paramCount) + 2 - 1) << 2
      computeLocations(paramCount, regArgNum, FP_REG, firstStackItem)

    private def argLocations(argCount: Int): List[Location] =
      val regArgNum = argCount - stackArgNum(argCount)
      computeLocations(argCount, regArgNum, SP_REG, -4)

    private def stackArgNum(argCount: Int): Int =
      if argCount <= PARAM_REGS.size then 0
      else argCount - PARAM_REGS.size

    private def stackResNum(resCount: Int): Int =
      if resCount <= PARAM_REGS.size then 0
      else resCount - PARAM_REGS.size

    /** Argument locations in a function call */
    private def resLocations(resCount: Int): List[Location] =
      val regResNum = resCount - stackResNum(resCount)
      computeLocations(resCount, regResNum, FP_REG, -4)

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
          val loc = Location.Stack(baseReg, offset)
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

      val argLocs = argLocations(argCount)
      val resLocs = resLocations(resCount)
      val savedRegs = callerSaved(argCount, resCount)
      CallerProtocol(argLocs, resLocs, savedRegs)

    def callee(funInfo: StackInfo): CalleeProtocol =
      val StackInfo(argCount, resCount) = funInfo

      val paramLocs = paramLocations(argCount)
      val resLocs = resLocations(resCount)
      val savedRegs = calleeSaved(argCount, resCount)
      CalleeProtocol(paramLocs, resLocs, savedRegs)

import Sast.StackInfo

import scala.collection.mutable

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
    argLocs: List[Location], resLocs: List[Location], stackArgNum: Int):

    val argRegs: List[Int] =
      argLocs.flatMap:
        case Location.Reg(index) => index :: Nil
        case _ => Nil

    val resRegs: List[Int] =
      resLocs.flatMap:
        case Location.Reg(index) => index :: Nil
        case _ => Nil

  case class CalleeProtocol(paramLocs: List[Location], resLocs: List[Location])

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
  class RegisterCallConvention(paramRegs: List[Int], FP_REG: Int, SP_REG: Int)
  extends CallConvention:
    /** Parameter locations in a function call */
    def paramLocations(paramCount: Int): List[Location] =
      val regArgNum = paramCount - stackArgNum(paramCount)
      // 2 for FP and return address
      val firstStackItem = (stackArgNum(paramCount) + 2 - 1) << 2
      computeLocations(paramCount, regArgNum, paramRegs, FP_REG, firstStackItem)

    def argLocations(argCount: Int): List[Location] =
      val regArgNum = argCount - stackArgNum(argCount)
      computeLocations(argCount, regArgNum, paramRegs, SP_REG, -4)

    def stackArgNum(argCount: Int): Int =
      if argCount <= paramRegs.size then 0
      else argCount - paramRegs.size

    def stackResNum(resCount: Int): Int =
      if resCount <= paramRegs.size then 0
      else resCount - paramRegs.size

    /** Argument locations in a function call */
    def resLocations(resCount: Int): List[Location] =
      val regResNum = resCount - stackResNum(resCount)
      computeLocations(resCount, regResNum, paramRegs, FP_REG, -4)

    def computeLocations(
      valueCount: Int,
      regValueNum: Int,
      contractRegs: List[Int],
      baseReg: Int,
      startOffset: Int): List[Location] =

      val buffer = new mutable.ArrayBuffer[Location]

      var i = 0
      while i < valueCount do
        // ordering of args
        if i < regValueNum then
          buffer += Location.Reg(contractRegs(i))
        else
          val delta = (i - regValueNum) << 2
          val offset = startOffset - delta
          val loc = Location.Stack(baseReg, offset)
          buffer += loc
        i += 1
      end while
      buffer.toList

    def caller(funInfo: StackInfo): CallerProtocol =
      val StackInfo(argCount, resCount) = funInfo

      val argLocs = argLocations(argCount)
      val resLocs = resLocations(resCount)
      CallerProtocol(argLocs, resLocs, stackArgNum(argCount))

    def callee(funInfo: StackInfo): CalleeProtocol =
      val StackInfo(argCount, resCount) = funInfo

      val paramLocs = paramLocations(argCount)
      val resLocs = resLocations(resCount)
      CalleeProtocol(paramLocs, resLocs)

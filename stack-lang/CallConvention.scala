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
  def call(funInfo: StackInfo): CallerProtocol

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

  class RegisterCallConvention(paramRegs: List[Int], FP_REG: Int, SP_REG: Int)
  extends CallConvention:
    /** Parameter locations in a function call */
    def parameterLocations(argCount: Int): List[Location] =
      val regArgNum = argCount - stackArgNum(argCount)
      // 2 for FP and return address
      val stackDelta = argCount - regArgNum + 2
      computeLocations(argCount, regArgNum, paramRegs, FP_REG, stackDelta)

    def argumentLocations(argCount: Int): List[Location] =
      val regArgNum = argCount - stackArgNum(argCount)
      computeLocations(argCount, regArgNum, paramRegs, SP_REG, 0)

    def stackArgNum(argCount: Int): Int =
      if argCount <= paramRegs.size then 0
      else argCount - paramRegs.size

    def stackResNum(resCount: Int): Int =
      if resCount <= paramRegs.size then 0
      else resCount - paramRegs.size

    /** Argument locations in a function call */
    def resultLocations(resCount: Int): List[Location] =
      val regResNum = resCount - stackResNum(resCount)
      computeLocations(resCount, regResNum, paramRegs, FP_REG, 0)

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
          val loc = Location.Stack(baseReg, offset = offset)
          buffer += loc
        i += 1
      end while
      buffer.toList

    def call(funInfo: StackInfo): CallerProtocol =
      val StackInfo(argCount, resCount) = funInfo

      val stackArgNum =
        if argCount <= paramRegs.size then 0
        else argCount - paramRegs.size

      val argLocs = argumentLocations(argCount)
      val resLocs = resultLocations(resCount)
      CallerProtocol(argLocs, resLocs, stackArgNum)

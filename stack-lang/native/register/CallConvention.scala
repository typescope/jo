package native
package register

import sast.Types.*
import sast.Definitions
import CallConvention.*
import Assembly.{ RegisterConfig, Rel }

import scala.collection.mutable
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
  def caller(paramTypes: List[Type], resType: Type)(using Definitions): Protocol
  def callee(paramTypes: List[Type], resType: Type)(using Definitions): Protocol

object CallConvention:
  enum Location:
    /** A location relative to the first stack element */
    case Stack(offset: Int)
    case Reg(index: Int)

    def isStack: Boolean = this.isInstanceOf[Location.Stack]

  object Location:
    /** Map a relative stack position to memory address */
    def map(loc: Stack, base: Rel): Assembly.Addr =
      Assembly.Rel(base.reg, loc.offset + base.offset)

  case class InProtocol(paramLocs: List[Location], retLoc: Location):
    val stackItemCount: Int =
      val size = paramLocs.filter(_.isStack).size
      if retLoc.isStack then size + 1 else size

    def regs: List[Int] =
      (retLoc :: paramLocs).collect:
        case Location.Reg(reg) => reg

  case class Protocol(
      in: InProtocol,
      out: List[Location],
      savedRegs: List[Int]):

    val inRegs: List[Int] = in.regs

    val outRegs: List[Int] = out.collect { case Location.Reg(reg) => reg }

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
    import registerConfig.{ FP_REG, FREE_REGS }

    private def inProtocol(paramCount: Int): InProtocol =
      val buffer = new mutable.ArrayBuffer[Location]

      val regValueNum =
        if paramCount <= PARAM_REGS.size then paramCount
        else PARAM_REGS.size

      var i = 0
      var stackOffset = 0
      while i < paramCount do
        // ordering of args
        if i < regValueNum then
          buffer += Location.Reg(PARAM_REGS(i))
        else
          val loc = Location.Stack(stackOffset)
          stackOffset -= 4
          buffer += loc
        i += 1
      end while

      val retLoc = Location.Stack(stackOffset)
      InProtocol(buffer.toList, retLoc)

    private def outProtocol(resCount: Int): List[Location] =
      val buffer = new mutable.ArrayBuffer[Location]

      val regValueNum =
        if resCount <= PARAM_REGS.size then resCount
        else PARAM_REGS.size

      var i = 0
      var stackOffset = 0
      while i < resCount do
        // ordering of args
        if i < regValueNum then
          buffer += Location.Reg(PARAM_REGS(i))
        else
          val loc = Location.Stack(stackOffset)
          stackOffset -= 4
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

    def caller(paramTypes: List[Type], resType: Type)(using Definitions): Protocol =
      val argCount = paramTypes.size
      val resCount = if resType.isVoidType then 0 else 1

      val savedRegs = FP_REG :: callerSaved(argCount, resCount)
      Protocol(inProtocol(argCount), outProtocol(resCount), savedRegs)

    def callee(paramTypes: List[Type], resType: Type)(using Definitions): Protocol =
      val argCount = paramTypes.size
      val resCount = if resType.isVoidType then 0 else 1

      val savedRegs = calleeSaved(argCount, resCount)
      Protocol(inProtocol(argCount), outProtocol(resCount), savedRegs)

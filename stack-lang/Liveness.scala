import scala.collection.mutable

import Assembly.*
import Instr.*

/** Liveness analysis for assembly code of a function
  *
  * Liveness analysis is a backward dataflow analysis based on fixed-point
  * computation.
  */
object Liveness:
  type LiveSet   = Set[Int]
  type Result    = mutable.Map[Int, LiveSet]
  type Instrs    = Seq[Instr | Label]

  case class InstrInfo(defs: List[Int], uses: List[Int])

  class CodeInfo(
    predecessorMap: Map[Int, Set[Int]],
    instrInfoMap: Map[Int, InstrInfo]):

    def predecessors(index: Int): Set[Int] =
      predecessorMap.get(index) match
        case Some(preds) => preds
        case None        =>
          if index > 0 then Set(index - 1) else Set.empty

    def instrInfo(index: Int): InstrInfo = instrInfoMap(index)


  type WorkList = mutable.ArrayDeque[WorkItem]
  case class WorkItem(index: Int, succLiveSet: LiveSet)

  def analyze(instrs: Instrs): Result =
    val workList = mutable.ArrayDeque.empty[WorkItem]
    val codeInfo = collectCodeInfo(instrs, workList)
    val result = mutable.Map.empty[Int, LiveSet]

    while workList.nonEmpty do
      val WorkItem(index, succLiveSet) = workList.removeLast()
      val oldLiveSet = result.getOrElseUpdate(index, Set.empty)
      val InstrInfo(defs, uses) = codeInfo.instrInfo(index)

      val newLiveSet = oldLiveSet.union(succLiveSet) ++ uses
      // outLiveSet cannot change if newLiveSet is the same
      if newLiveSet != oldLiveSet then
        result(index) = newLiveSet
        val outLiveSet = newLiveSet -- defs
        for pred <- codeInfo.predecessors(index) do
          workList += WorkItem(pred, outLiveSet)
    end while
    result

  /** Collect code info and initialize work list for each instruction. */
  def collectCodeInfo(instrs: Instrs, workList: WorkList): CodeInfo =
    val labelInfo = mutable.Map.empty[Label, Int]
    val predecessorMap = mutable.Map.empty[Int, Set[Int]]
    val instrInfoMap = mutable.Map.empty[Int, InstrInfo]
    val jumpTargets = mutable.Map.empty[Int, Label]

    var index = 0
    val size = instrs.size
    while index < size do
      val instr = instrs(index)
      instr match
        case l: Label =>
          labelInfo(l) = index + 1
          val nextPreds = predecessorMap.getOrElseUpdate(index + 1, Set.empty)
          if index > 0 then
            predecessorMap(index + 1) = nextPreds + (index - 1)

        case instr: Instr =>
          instrInfoMap(index) = analyzeInstrInfo(instr)

          workList += WorkItem(index, Set.empty)

          instr match
            case Move(Reg(srcReg), destReg) =>
              val moveTargets = moves.getOrElseUpdate(srcReg, Set.empty)
              moves(srcReg) = moveTargets + destReg

            case Jump(_: Reg | _: Rel) =>
              // indirect function call or function return

            case Jump(label: Label) =>
              // Could be a non-local function target, handled by checking labels
              jumpTargets(index) = label

            case JZero(_, label: Label) =>
              jumpTargets(index) = label

            case _ =>
          end match
      end match
      index += 1
    end while

    for (fromIndex, toLabel) <- jumpTargets do
      labelInfo.get(toLabel) match
        case Some(idx) =>
          val preds = predecessorMap.getOrElseUpdate(idx, Set.empty)
          predecessorMap(idx) = preds + fromIndex

        case None =>
          // function call, ignore
    end for

    CodeInfo(predecessorMap.toMap, instrInfoMap.toMap)


  def analyzeInstrInfo(instr: Instr): InstrInfo =
    val useRegs = mutable.ArrayBuffer.empty[Int]
    val defRegs = mutable.ArrayBuffer.empty[Int]

    instr match
      case Binary(op: BiOp, v1: Operand, v2: Operand, destReg) =>
        defRegs += destReg

        v1 match
          case Reg(r) => useRegs += r
          case _: Int32 =>

        v2 match
          case Reg(r) => useRegs += r
          case _: Int32 =>

      case Move(v, destReg) =>
        v match
          case Reg(srcReg) =>
            defRegs += destReg
            useRegs += srcReg
          case _ =>

      case Store(v: Value, addr: Addr) =>
        v match
          case Reg(r) => useRegs += r
          case _: Label =>
          case _: Int32 =>

        addr match
          case Reg(r)    => useRegs += r
          case Rel(r, _) => useRegs += r
          case _: Label =>

      case Load(addr: Addr, destReg: Byte) =>
        defRegs += destReg
        addr match
          case Reg(r)    => useRegs += r
          case Rel(r, _) => useRegs += r
          case _: Label =>

      case Jump(addr: Addr) =>
        addr match
          case Reg(r)    => useRegs += r
          case Rel(r, _) => useRegs += r
          case _: Label =>

      case JZero(reg: Reg, label: Label) =>
        useRegs += reg.index

      case _: Special[?] =>
        // TODO
    end match

    InstrInfo(defRegs.toList, useRegs.toList)
  end analyzeInstrInfo

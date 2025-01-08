package native
package register

import Assembly.*
import PreAssembly.*
import Instr.*

import scala.collection.mutable


/** Liveness analysis for assembly code of a function
  *
  * Liveness analysis is a backward dataflow analysis based on fixed-point
  * computation.
  */
object Liveness:
  // TODO: use bitset for fast union and subtraction
  type LiveSet   = Set[Int]

  class CodeInfo(
    predecessorMap: Map[Int, Set[Int]],
    val instrs: Seq[PreInstr],
    val moves: Map[Int, Set[Int]],
    val bit8Regs: Set[Int]):

    def predecessors(index: Int): Set[Int] =
      predecessorMap.get(index) match
        case Some(preds) => preds
        case None        =>
          if index > 0 then Set(index - 1) else Set.empty

    override def toString() =
      predecessorMap.keys.toSeq.sorted.map(k => s"$k -> " + predecessorMap(k)).mkString("\n")


  case class WorkItem(index: Int, succInLiveSet: LiveSet)

  case class Info(
    liveSets: Map[Int, LiveSet],
    moves: Map[Int, Set[Int]],
    instrs: Seq[PreInstr],
    bit8Regs: Set[Int]):

    override def toString() =
      liveSets.keys.toSeq.sorted.map(k => s"$k: " + liveSets(k)).mkString("\n")

  def analyze(items: Seq[PreAssembly.Item]): Info =
    val workList = mutable.ArrayDeque.empty[WorkItem]
    val codeInfo = collectCodeInfo(items)
    val result = mutable.Map.empty[Int, LiveSet]

    // println(codeInfo)

    assert(codeInfo.instrs.last.isInstanceOf[PreInstr.Return], "last instr is not return")
    workList += WorkItem(codeInfo.instrs.size - 1, Set.empty)

    while workList.nonEmpty do
      val WorkItem(loc, succInLiveSet) = workList.removeLast()
      val RegInfo(defs, uses) = codeInfo.instrs(loc).regInfo
      // println(s"$index info: defs = $defs, uses = $uses")

      val oldOutLiveSet = result.getOrElse(loc, Set.empty)
      val newOutLiveSet = oldOutLiveSet.union(succInLiveSet)
      // predLiveSet cannot change if newPosLiveSet is the same
      if !result.contains(loc) || newOutLiveSet != oldOutLiveSet then
        result(loc) = newOutLiveSet
        val curInLiveSet = (newOutLiveSet -- defs) ++ uses
        for pred <- codeInfo.predecessors(loc) do
          // println(s"$index -> $pred: $predLiveSet")
          workList += WorkItem(pred, curInLiveSet)
    end while

    Info(result.toMap, codeInfo.moves, codeInfo.instrs, codeInfo.bit8Regs)

  /** Collect code info and initialize work list for each instruction. */
  def collectCodeInfo(items: Seq[PreAssembly.Item]): CodeInfo =
    val labelAddr = mutable.Map.empty[Label, Int]

    val instrs = new mutable.ArrayBuffer[PreInstr]
    for item <- items do
      item match
        case label: Label =>
          labelAddr(label) = instrs.size

        case instr: PreInstr =>
          instrs += instr

        case holder: PlaceHolder =>
    end for

    val predecessorMap = mutable.Map.empty[Int, Set[Int]]
    val jumpTargets = mutable.Map.empty[Int, Int]
    val moves = mutable.Map.empty[Int, Set[Int]]
    val bit8Regs = mutable.Set.empty[Int]

    var index = 0
    val size = instrs.size
    while index < size do
      val preInstr = instrs(index)

      preInstr match
        case PreInstr.Call(_, _, _) | _: PreInstr.Return =>
          // non-local jumps

        case PreInstr.Instr(instr) =>
          instr match
            case Move(Reg(srcReg), destReg) =>
              val moveTargets = moves.getOrElse(srcReg, Set.empty)
              moves(srcReg) = moveTargets + destReg

            case Move(Reg8(srcReg), _) =>
              bit8Regs += srcReg

            case Store(Reg8(srcReg), _) =>
              bit8Regs += srcReg

            case Load(_, destReg, Size.B8) =>
              bit8Regs += destReg

            case Jump(_: Reg | _: Rel) =>
              throw new Exception("Unexpected instruction " + instr)

            case Jump(label: Label) =>
              jumpTargets(index) = labelAddr(label)

            case JZero(_, label: Label) =>
              jumpTargets(index) = labelAddr(label)

            case _ =>
      end match
      index += 1
    end while

    // Find the sequential predecessor for the given instruction
    def sequentialPredecessor(i: Int): Set[Int] =
      if i == 0 then
        Set.empty
      else
        instrs(i - 1) match
          case _: PreInstr.Call =>
            // function call will follow the next
            Set(i - 1)

          case PreInstr.Return | PreInstr.Instr(Jump(_: Label)) =>
            // unconditional jump
            Set.empty

          case _ => Set(i - 1)
      end if

    for (fromIndex, toIndex) <- jumpTargets do
      val preds = predecessorMap.getOrElse(toIndex, sequentialPredecessor(toIndex))
      predecessorMap(toIndex) = preds + fromIndex
    end for

    CodeInfo(predecessorMap.toMap, instrs.toList, moves.toMap, bit8Regs.toSet)

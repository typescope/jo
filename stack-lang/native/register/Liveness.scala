package native.register

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
    val instrs: Seq[PreInstr],
    predecessorMap: Map[Int, Set[Int]],
    val moves: Map[Int, Set[Int]]):

    def predecessors(index: Int): Set[Int] =
      predecessorMap.get(index) match
        case Some(preds) => preds
        case None        =>
          if index > 0 then Set(index - 1) else Set.empty

    override def toString() =
      predecessorMap.keys.toSeq.sorted.map(k => s"$k -> " + predecessorMap(k)).mkString("\n")


  type WorkList = mutable.ArrayDeque[WorkItem]
  case class WorkItem(index: Int, succLiveSet: LiveSet)

  case class Result(liveSets: Map[Int, LiveSet], moves: Map[Int, Set[Int]], instrs: Seq[PreInstr]):
    override def toString() =
      liveSets.keys.toSeq.sorted.map(k => s"$k: " + liveSets(k)).mkString("\n")

  def analyze(items: Seq[PreAssembly.Item]): Result =
    val workList = mutable.ArrayDeque.empty[WorkItem]
    val codeInfo = collectCodeInfo(items, workList)
    val result = mutable.Map.empty[Int, LiveSet]

    // println(codeInfo)

    while workList.nonEmpty do
      val WorkItem(loc, succLiveSet) = workList.removeLast()
      val RegInfo(defs, uses) = codeInfo.instrs(loc).regInfo
      // println(s"$index info: defs = $defs, uses = $uses")

      val oldPosLiveSet = result.getOrElse(loc, Set.empty)
      val newPosLiveSet = oldPosLiveSet.union(succLiveSet)
      // predLiveSet cannot change if newPosLiveSet is the same
      if !result.contains(loc) || newPosLiveSet != oldPosLiveSet then
        result(loc) = newPosLiveSet
        val predLiveSet = (newPosLiveSet -- defs) ++ uses
        for pred <- codeInfo.predecessors(loc) do
          // println(s"$index -> $pred: $predLiveSet")
          workList += WorkItem(pred, predLiveSet)
    end while

    Result(result.toMap, codeInfo.moves, codeInfo.instrs)

  /** Collect code info and initialize work list for each instruction. */
  def collectCodeInfo(items: Seq[PreAssembly.Item], workList: WorkList): CodeInfo =
    val labelInfo = mutable.Map.empty[Label, Int]

    val instrs = new mutable.ArrayBuffer[PreInstr]
    for item <- items do
      item match
        case label: Label =>
          labelInfo(label) = instrs.size

        case instr: PreInstr =>
          instrs += instr

        case holder: PlaceHolder =>
    end for

    val predecessorMap = mutable.Map.empty[Int, Set[Int]]
    val jumpTargets = mutable.Map.empty[Int, Int]
    val moves = mutable.Map.empty[Int, Set[Int]]

    var index = 0
    val size = instrs.size
    while index < size do
      val preInstr = instrs(index)
      workList += WorkItem(index, Set.empty)

      preInstr match
        case PreInstr.Call(_, _, _) | _: PreInstr.Return =>
          // non-local jumps

        case PreInstr.Instr(instr) =>
          instr match
            case Move(Reg(srcReg), destReg) =>
              val moveTargets = moves.getOrElse(srcReg, Set.empty)
              moves(srcReg) = moveTargets + destReg

            case Jump(_: Reg | _: Rel) =>
              throw new Exception("Unexpected instruction " + instr)

            case Jump(label: Label) =>
              jumpTargets(index) = labelInfo(label)

            case JZero(_, label: Label) =>
              jumpTargets(index) = labelInfo(label)

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

    CodeInfo(instrs.toList, predecessorMap.toMap, moves.toMap)

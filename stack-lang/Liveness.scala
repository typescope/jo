import scala.collection.mutable

import Assembly.*
import Instr.*

/** Liveness analysis for assembly code of a function
  *
  * Liveness analysis is a backward dataflow analysis based on fixed-point
  * computation.
  */
object Liveness:
  // TODO: use bitset for fast union and subtraction
  type LiveSet   = Set[Int]
  type Instrs    = Seq[Instr]

  case class InstrInfo(defs: List[Int], uses: List[Int])

  class CodeInfo(
    instrs: Seq[Instr],
    predecessorMap: Map[Int, Set[Int]],
    instrInfoMap: Map[Int, InstrInfo],
    val moves: Map[Int, Set[Int]]):

    def predecessors(index: Int): Set[Int] =
      predecessorMap.get(index) match
        case Some(preds) => preds
        case None        =>
          if index > 0 then Set(index - 1) else Set.empty

    def instrInfo(index: Int): InstrInfo = instrInfoMap(index)

    override def toString() =
      predecessorMap.keys.toSeq.sorted.map(k => k + " -> " + predecessorMap(k)).mkString("\n")


  type WorkList = mutable.ArrayDeque[WorkItem]
  case class WorkItem(index: Int, succLiveSet: LiveSet)

  case class Result(liveSets: Map[Int, LiveSet], moves: Map[Int, Set[Int]]):
    override def toString() =
      liveSets.keys.toSeq.sorted.map(k => k + ": " + liveSets(k)).mkString("\n")

  def analyze(rawInstrs: Seq[Instr | Label]): Result =
    val workList = mutable.ArrayDeque.empty[WorkItem]
    val codeInfo = collectCodeInfo(rawInstrs, workList)
    val result = mutable.Map.empty[Int, LiveSet]

    println(codeInfo)

    while workList.nonEmpty do
      val WorkItem(index, succLiveSet) = workList.removeLast()
      val InstrInfo(defs, uses) = codeInfo.instrInfo(index)
      // println(s"$index info: defs = $defs, uses = $uses")

      val oldPosLiveSet = result.getOrElse(index, Set.empty)
      val newPosLiveSet = oldPosLiveSet.union(succLiveSet)
      // predLiveSet cannot change if newPosLiveSet is the same
      if !result.contains(index) || newPosLiveSet != oldPosLiveSet then
        result(index) = newPosLiveSet
        val predLiveSet = (newPosLiveSet -- defs) ++ uses
        for pred <- codeInfo.predecessors(index) do
          // println(s"$index -> $pred: $predLiveSet")
          workList += WorkItem(pred, predLiveSet)
    end while

    Result(result.toMap, codeInfo.moves)

  /** Collect code info and initialize work list for each instruction. */
  def collectCodeInfo(rawInstrs: Seq[Instr | Label], workList: WorkList): CodeInfo =
    val labelInfo = mutable.Map.empty[Label, Int]

    val instrs = new mutable.ArrayBuffer[Instr]
    for rawInstr <- rawInstrs do
      rawInstr match
        case label: Label =>
          labelInfo(label) = instrs.size

        case instr: Instr =>
          instrs += instr
    end for

    val predecessorMap = mutable.Map.empty[Int, Set[Int]]
    val instrInfoMap = mutable.Map.empty[Int, InstrInfo]
    val jumpTargets = mutable.Map.empty[Int, Int]
    val moves = mutable.Map.empty[Int, Set[Int]]

    var index = 0
    val size = instrs.size
    while index < size do
      val instr = instrs(index)
      instrInfoMap(index) = analyzeInstrInfo(instr)
      workList += WorkItem(index, Set.empty)

      instr match
        case Move(Reg(srcReg), destReg) =>
          val moveTargets = moves.getOrElse(srcReg, Set.empty)
          moves(srcReg) = moveTargets + destReg

        case Jump(_: Reg | _: Rel) =>
          // indirect function call or function return

        case Jump(label: Label) if !label.isInstanceOf[FunLabel] =>
          jumpTargets(index) = labelInfo(label)

        case JZero(_, label: Label) =>
          jumpTargets(index) = labelInfo(label)

        case _ =>
      end match
      index += 1
    end while

    /** Find the sequential predecessor for the given instruction  */
    def sequentialPredecessor(i: Int): Set[Int] =
      if i == 0 then
        Set.empty
      else
        instrs(i - 1) match
          case Jump(_: Reg | _: Rel) =>
            // indirect function call or function return
            Set(i - 1)

          case Jump(label: Label) =>
            // function call will follow the next
            if label.isInstanceOf[FunLabel] then Set(i - 1)
            else Set.empty

          case _ => Set(i - 1)
      end if

    for (fromIndex, toIndex) <- jumpTargets do
      val preds = predecessorMap.getOrElse(toIndex, sequentialPredecessor(toIndex))
      predecessorMap(toIndex) = preds + fromIndex
    end for

    CodeInfo(instrs.toList, predecessorMap.toMap, instrInfoMap.toMap, moves.toMap)


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
        defRegs += destReg
        v match
          case Reg(srcReg) =>
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

      case Load(addr: Addr, destReg) =>
        defRegs += destReg
        addr match
          case Reg(r)    => useRegs += r
          case Rel(r, _) => useRegs += r
          case _: Label =>

      case Jump(addr: Addr) =>
        addr match
          case Reg(r)    => useRegs += r
          case Rel(r, _) => useRegs += r

          case fun: FunLabel =>
            useRegs ++= fun.paramRegs
            defRegs ++= fun.returnRegs

          case l: Label => // local jump

      case JZero(reg: Reg, label: Label) =>
        useRegs += reg.index

      case _: Special[?] =>
        // TODO
    end match

    InstrInfo(defRegs.toList, useRegs.toList)
  end analyzeInstrInfo

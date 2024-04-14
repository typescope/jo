import scala.colleciton.mutable

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
    instrs: Instrs,
    predecessorMap: Map[Int, Set[Int]],
    instrInfoMap: Map[Int, InstrInfo],
    returns: List[Int]):

    def precessors(index: Int): Set[Int] =
      predecessorMap.get(index) match
        case Some(preds) => preds
        case None        =>
          if index > 0 then index - 1 else Set.empty

    def exits: List[Int] = returns

    def instruction(index: Int): Instr =
      instrs(index).asInstanceOf

    def instrInfo(index: Int): InstrInfo = instrInfoMap(index)


  def analyze(instrs: Instrs): Result = ???


  def collectCodeInfo(instrs: Instrs): CodeInfo =
    val labelInfo = mutable.Map.empty[Label, Int]
    val predecessorMap = mutable.Map.empty[Int, Set[Int]]
    val instrInfoMap = mutable.Map.empty[Int, Set[Int]]
    val jumpTargets = mutable.Map[Int, Label]
    val returns = mutble.ArrayBuffer.empty[Int]

    var index = 0
    val size = instrs.size
    while index < size do
      val instr = instrs(index)
      instr match
        case l: Label       =>
          labelInfo(l) = index + 1
          val nextPreds = predecessorMap.getOrElseUpdate(index + 1, Set.empty)
          if index > 0 then
            predecessorMap(index + 1) = nextPreds + (index - 1)

        case instr: Instr =>
          instrInfoMap += analyzeInstrInfo(instr)

          instr match
            case Jump(_: Reg | _: Rel) =>
              // Assume no indirect call, except function return
              returns += index

            case Jump(l: Label) =>
              // Could be a non-local function target, handled by checking labels
              jumpTargets(index) = l

            case JZero(_, label: Label) =>
              jumpTargets(index) = l

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

    CodeInfo(instrs, predecessorMap.toMap, instrInfoMap.toMap, returns.toList)

  def work(info: CodeInfo): Result =
    val result = mutable.Map.empty
    for returnIndex <- info.returns do
      propagate(i, Set.empty, result)(using info)

  def propagate(current: Int, successor: LiveSet, result: Result)(using info: CodeInfo) =
    val oldLiveSet = result.getOrElseUpdate(index, Set.empty)
    val InstrInfo(defs, uses) = info.instrInfo(current)

    // It's not obvious whether the out set has changed.
    // If the in set does not change, out set cannot change.
    //
    // Still needs to ensure that each node is propagated at least once.
    val newLiveSet = oldLiveSet.union(uses)
    if newLiveSet != oldLiveSet then
      val outLiveSet = newLiveSet -- defs
      for pred <- info.predecessors(index) do
        propagate(pred, outLiveSet, result)

  def analyzeInstrInfo(instr: Instr): InstrInfo =
    val useRegs = mutable.ArrayBuffer.empty[Int]
    val defRegs = mutable.ArrayBuffer.empty[Int]

    instr match
      case Not(v: Operand, destReg: Byte) =>
        defRegs += destReg
        v match
          case Reg(r) => useRegs += r
          case _: Int32 =>

      case Const(_: Constant, destReg: Byte) =>
        defRegs += destReg

      case Binary(op: BiOp, v1: Operand, v2: Operand, destReg: Byte) =>
        defRegs += destReg

        v1 match
          case Reg(r) => useRegs += r
          case _: Int32 =>

        v2 match
          case Reg(r) => useRegs += r
          case _: Int32 =>

      case Move(srcReg: Byte, destReg: Byte) =>
        defRegs += destReg
        useRegs += srcReg

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
    end match

    InstrInfo(defRegs.toList, useRegs.toList)
  end analyzeInstrInfo

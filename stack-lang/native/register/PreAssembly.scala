package native
package register

import Assembly.*

import scala.collection.mutable

/** Code representation of a function before register allocation.
  *
  * The code is supposed to be ignostic to register allocation algorithms.
  */
object PreAssembly:
  val VIRTUAL_REG_START_INDEX = 256

  /** Placeholders in the generated code before register allocation.
    *
    * At the time of compilation, we don't know how many registers are actually
    * used and how many locals are stored on the stack.
    *
    * Placeholders are replaced by actual assembly code after register
    * allocation.
    *
    * Callee-saved registers could be handled by the graph coloring algorithm.
    * However, it complicates the graph a lot and the elimination of useless
    * save/restore is highly dependent on the heuristics. Therefore, it is
    * better to be handled with placeholders.
    */
  enum PlaceHolder:
    case InitStackPointer
    case CalleeSaveRegisters

  /** Register usage information of an instruction */
  case class RegInfo(defs: List[Int], uses: List[Int])
  /** A pre-instruction contains information required for liveness analysis
    *
    * In particular, liveness analysis needs to (1) distinguish local jumps from
    * non-local jumps; (2) get information about register read/write.
    */
  enum PreInstr:
    case Instr(instr: Assembly.Instr)
    case Call(addr: Addr, argRegs: List[Int], retRegs: List[Int])
    case Return(addrReg: Int, resRegs: List[Int])

    lazy val regInfo = this match
      case Instr(instr)                  => analyzeRegInfo(instr)
      case Return(addrReg, resRegs)      => RegInfo(Nil, addrReg :: resRegs)
      case Call(addr, argRegs, retRegs)  =>
        addr match
          case Reg(index)    => RegInfo(retRegs, index :: argRegs)
          case Rel(index, _) => RegInfo(retRegs, index :: argRegs)
          case _: Label      => RegInfo(retRegs, argRegs)

  type Item = PreInstr | Label | PlaceHolder

  /**
    * Hold generated assembly data and code.
    */
  class ItemBuffer:
    private val code: mutable.ArrayBuffer[Item] = new mutable.ArrayBuffer

    def gen(instr : Instr): Unit = code.addOne(PreInstr.Instr(instr))
    def gen(instr : PreInstr): Unit = code.addOne(instr)
    def gen(holder: PlaceHolder): Unit = code.addOne(holder)
    def gen(label: Label): Unit = code.addOne(label)
    def getResult(): List[Item] = code.toList
    def clear(): Unit = code.clear()
    def show: String = code.map("\t" + _.toString).mkString("\n")

  /**
    * A virtual register generator.
    *
    * A new instance must be used for each function.
    */
  class VirtualRegGenerator:
    /** To generate unique IDs of virtual registers */
    var index = VIRTUAL_REG_START_INDEX

    /** Allocate a virtual register */
    def fresh(): Int =
      index += 1
      index


  /** Analyze the assigned and used registers of an instruction */
  def analyzeRegInfo(instr: Instr): RegInfo =
    val useRegs = mutable.ArrayBuffer.empty[Int]
    val defRegs = mutable.ArrayBuffer.empty[Int]

    instr match
      case Instr.Binary(op: BiOp, v1: Operand, v2: Operand, destReg) =>
        defRegs += destReg

        v1 match
          case Reg(r) => useRegs += r
          case _: Int32 =>

        v2 match
          case Reg(r) => useRegs += r
          case _: Int32 =>

      case Instr.Move(v, destReg) =>
        defRegs += destReg
        v match
          case Reg(srcReg) =>
            useRegs += srcReg
          case _ =>

      case Instr.Store(v: Value, addr: Addr) =>
        v match
          case Reg(r)   => useRegs += r
          case Reg8(r)  => useRegs += r
          case _: Label =>
          case _: Int32 =>

        addr match
          case Reg(r)    => useRegs += r
          case Rel(r, _) => useRegs += r
          case _: Label =>

      case Instr.Load(addr: Addr, destReg, size) =>
        defRegs += destReg
        addr match
          case Reg(r)    => useRegs += r
          case Rel(r, _) => useRegs += r
          case _: Label =>

      case Instr.Jump(addr: Addr) =>
        addr match
          case Reg(r)    => useRegs += r
          case Rel(r, _) => useRegs += r
          case l: Label  =>

      case Instr.JZero(reg: Reg, label: Label) =>
        useRegs += reg.index

    end match

    RegInfo(defRegs.toList, useRegs.toList)
  end analyzeRegInfo

  def subst(instr: Instr, regAlloc: Map[Int, Int]): List[Instr] =
    def substReg(reg: Int): Int = regAlloc.getOrElse(reg, reg)

    def substValue(value: Value): Value =
      subst(value).asInstanceOf[Value]

    def substOperand(operand: Operand): Operand =
      subst(operand).asInstanceOf[Operand]

    def substAddr(addr: Addr): Addr =
      subst(addr).asInstanceOf[Addr]

    def subst(value: Addr | Value): Addr | Value =
      value match
        case Reg(r) => Reg(substReg(r))
        case Reg8(r) => Reg8(substReg(r))
        case Rel(r, offset) => Rel(substReg(r), offset)
        case _ =>  value

    instr match
      case Instr.Binary(op: BiOp, v1: Operand, v2: Operand, destReg) =>
        Instr.Binary(op, substOperand(v1), substOperand(v2), substReg(destReg)) :: Nil

      case Instr.Move(v, destReg) =>
        val src = substValue(v)
        val dest = substReg(destReg)
        src match
          case Reg(`dest`) => Nil
          case _           => Instr.Move(src, dest) :: Nil

      case Instr.Store(v: Value, addr: Addr) =>
        Instr.Store(substValue(v), substAddr(addr)) :: Nil

      case Instr.Load(addr: Addr, destReg, size) =>
        Instr.Load(substAddr(addr), substReg(destReg), size) :: Nil

      case Instr.Jump(addr: Addr) =>
        Instr.Jump(substAddr(addr)) :: Nil

      case Instr.JZero(Reg(index), label: Label) =>
        Instr.JZero(Reg(substReg(index)), label) :: Nil
    end match

  def substDest(instr: Instr, regAlloc: Map[Int, Int]): Instr =
    def substReg(reg: Int): Int = regAlloc.getOrElse(reg, reg)

    instr match
      case Instr.Binary(op: BiOp, v1: Operand, v2: Operand, destReg) =>
        Instr.Binary(op, v1, v2, substReg(destReg))

      case Instr.Move(v, destReg) =>
        Instr.Move(v, substReg(destReg))

      case Instr.Store(v: Value, addr: Addr) =>
        instr

      case Instr.Load(addr: Addr, destReg, size) =>
        Instr.Load(addr, substReg(destReg), size)

      case Instr.Jump(addr: Addr) =>
        instr

      case Instr.JZero(reg: Reg, label: Label) =>
        instr

    end match

  def substSource(instr: Instr, regAlloc: Map[Int, Int]): Instr =
    def substReg(reg: Int): Int = regAlloc.getOrElse(reg, reg)

    def substValue(value: Value): Value =
      subst(value).asInstanceOf[Value]

    def substOperand(operand: Operand): Operand =
      subst(operand).asInstanceOf[Operand]

    def substAddr(addr: Addr): Addr =
      subst(addr).asInstanceOf[Addr]

    def subst(value: Addr | Value): Addr | Value =
      value match
        case Reg(r) => Reg(substReg(r))
        case Reg8(r) => Reg8(substReg(r))
        case Rel(r, offset) => Rel(substReg(r), offset)
        case _ =>  value

    instr match
      case Instr.Binary(op: BiOp, v1: Operand, v2: Operand, destReg) =>
        Instr.Binary(op, substOperand(v1), substOperand(v2), destReg)

      case Instr.Move(v, destReg) =>
        Instr.Move(substValue(v), destReg)

      case Instr.Store(v: Value, addr: Addr) =>
        Instr.Store(substValue(v), substAddr(addr))

      case Instr.Load(addr: Addr, destReg, size) =>
        Instr.Load(substAddr(addr), destReg, size)

      case Instr.Jump(addr: Addr) =>
        Instr.Jump(substAddr(addr))

      case Instr.JZero(Reg(index), label: Label) =>
        Instr.JZero(Reg(substReg(index)), label)

    end match

  /** Spill registers
    *
    * - append Store after assign to a spilled register
    * - insert Load before read of a spilled register
    *
    * In both cases, we need to replace the read/assign respectively with a
    * fresh virtual registers.
    */
  def spill(
    instr: Instr,
    regInfo: RegInfo,
    stackAlloc: Map[Int, Int],
    generator: VirtualRegGenerator,
    addr: Int => Addr): List[Instr] =

    val RegInfo(defs, uses) = regInfo
    val before = mutable.ArrayBuffer.empty[Instr]
    val after = mutable.ArrayBuffer.empty[Instr]

    var currentInstr = instr
    for use <- uses do
      stackAlloc.get(use) match
        case Some(i) =>
          val virtualReg = generator.fresh()
          before += Instr.Load(addr(i), virtualReg, Size.B32)
          currentInstr = substSource(currentInstr, Map(use -> virtualReg))
        case None =>

    for destReg <- defs do
      stackAlloc.get(destReg) match
        case Some(i) =>
          val virtualReg = generator.fresh()
          after += Instr.Store(Reg(virtualReg), addr(i))
          currentInstr = substDest(currentInstr, Map(destReg -> virtualReg))
        case None =>

    before += currentInstr
    before ++= after
    before.toList

  def rewrite(
    instrs: List[PreAssembly.Item],
    stackAlloc: Map[Int, Int],
    generator: VirtualRegGenerator,
    addr: Int => Addr): List[PreAssembly.Item] =

    def spillReg(reg: Int, current: PreInstr)(rewrite: Int => PreInstr): List[PreInstr] =
      stackAlloc.get(reg) match
        case Some(i) =>
          val virtualReg = generator.fresh()
          val loadInstr = PreInstr.Instr(Instr.Load(addr(i), virtualReg, Size.B32))
          val newInstr = rewrite(virtualReg)
          loadInstr :: newInstr :: Nil

        case None =>
          current :: Nil

    instrs.flatMap:
      case label: Label        => label :: Nil

      case holder: PlaceHolder => holder :: Nil

      case preInstr: PreInstr  =>
        preInstr match
          case PreInstr.Call(addr, argRegs, resRegs) =>
            addr match
              case Reg(index) =>
                spillReg(index, preInstr): virtualReg =>
                  PreInstr.Call(Reg(virtualReg), argRegs, resRegs)

              case Rel(index, offset) =>
                spillReg(index, preInstr): virtualReg =>
                  PreInstr.Call(Rel(virtualReg, offset), argRegs, resRegs)

              case _: Label      =>
                preInstr :: Nil

          case PreInstr.Return(addrReg, resRegs) =>
            spillReg(addrReg, preInstr): virtualReg =>
              PreInstr.Return(virtualReg, resRegs)

          case PreInstr.Instr(instr) =>
            val instrs =
              spill(instr, preInstr.regInfo,  stackAlloc, generator, addr)
            for instr2 <- instrs
            yield PreInstr.Instr(instr2)

  /** Commit register allocation result and emit assembly from pre-assembly */
  def commitAlloc(
    funLabel: Label, calleeSavedRegs: List[Int], instrs: List[PreAssembly.Item],
    regAlloc: Map[Int, Int], usedRegs: Set[Int], spillCount: Int,
    cb: CodeBuffer, regConfig: RegisterConfig): Unit =
    import regConfig.{ SP_REG, FP_REG }

    // mark beginning of function
    cb.mark(funLabel)

    val actualSavedRegs = calleeSavedRegs.filter(usedRegs.contains)
    // println(s"$funLabel, calleeSavedRegs = $calleeSavedRegs, usedRegs = $usedRegs, actual = $actualSavedRegs")

    for item <- instrs do
      item match
        case l: Label =>
          cb.mark(l)

        case PlaceHolder.InitStackPointer =>
          // Update SP at the beginning of function
          val frameSize = spillCount + actualSavedRegs.size
          cb.add(Instr.Move(Reg(SP_REG), FP_REG))
          cb.add(Instr.Sub(Reg(SP_REG), Int32(frameSize << 2), SP_REG))

        case PlaceHolder.CalleeSaveRegisters =>
          for (savedReg, i) <- actualSavedRegs.zipWithIndex do
            val addr = Rel(FP_REG, -((spillCount + i + 1) << 2))
            cb.add(Instr.Store(Reg(savedReg), addr))

        case preInstr: PreInstr =>
          preInstr match
            case PreInstr.Instr(instr) =>
              for instr2 <- subst(instr, regAlloc) do
                val RegInfo(defs, uses) = PreAssembly.analyzeRegInfo(instr2)
                for dest <- defs do assert(dest < VIRTUAL_REG_START_INDEX, dest)
                for use <- uses do assert(use < VIRTUAL_REG_START_INDEX, use)
                cb.add(instr2)

            case PreInstr.Call(addr, _, _) =>
              cb.add(subst(Instr.Jump(addr), regAlloc))

            case PreInstr.Return(reg, _) =>
              var regRet = regAlloc.getOrElse(reg, reg)

              if actualSavedRegs.contains(regRet) then
                cb.add(Instr.Move(Reg(regRet), SP_REG))
                regRet = SP_REG

              for (savedReg, i) <- actualSavedRegs.zipWithIndex do
                val addr = Rel(FP_REG, -((spillCount + i + 1) << 2))
                cb.add(Instr.Load(addr, savedReg, Size.B32))

              cb.add(Instr.Jump(Reg(regRet)))

  def doGraphColoring(
    label: Label,
    preAsm: List[Item],
    regConfig: RegisterConfig,
    calleeSavedRegs: List[Int],
    cb: CodeBuffer,
    generator: VirtualRegGenerator,
    rules: GraphColoring.PlatformRules): Unit =

    // Register allocation
    var continue = true
    var spillCount = 0
    var instrs = preAsm
    while continue do

      val liveness = Liveness.analyze(instrs)
      // println(liveness)

      val reservedRegisters: List[Int] =
        List(regConfig.FP_REG, regConfig.SP_REG)

      val extraConflicts = rules.conflicts(liveness)

      val GraphColoring.Result(regAlloc, stackAlloc, usedRegs) =
          GraphColoring.alloc(
            label.name,
            liveness,
            regConfig.FREE_REGS,
            reservedRegisters,
            VIRTUAL_REG_START_INDEX,
            extraConflicts
          )

      // println(regAlloc)
      // println(stackAlloc)

      def addr(i: Int): Addr =
        Rel(regConfig.FP_REG, -(i + 1 + spillCount) << 2)

      if stackAlloc.isEmpty then
        // println(label)
        // for instr <- instrs do println("\t" + instr)

        commitAlloc(
          label, calleeSavedRegs, instrs, regAlloc,
          usedRegs, spillCount, cb, regConfig)
        continue = false
      else
        // rewrite program with spill and perform allocation again
        continue = true
        instrs = rewrite(instrs, stackAlloc, generator, addr)
        spillCount += stackAlloc.size
    end while

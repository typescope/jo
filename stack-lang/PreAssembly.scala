import scala.collection.mutable

import Assembly.*


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
    case InitStackPointer, SaveRegisters, RestoreRegisters

  /** Register usage information of an instruction */
  case class RegInfo(defs: List[Int], uses: List[Int])

  /** A pre-instruction contains information required for liveness analysis
    *
    * In particular, liveness analysis needs to (1) distinguish local jumps from
    * non-local jumps; (2) get information about register read/write.
    */
  enum PreInstr:
    case Instr(instr: Assembly.Instr)
    case Call(label: Label, argRegs: List[Int], retRegs: List[Int])
    case Return

    lazy val regInfo = this match
      case Instr(instr)                  => analyzeRegInfo(instr)
      case Call(label, argRegs, retRegs) => RegInfo(argRegs, retRegs)
      case Return                        => RegInfo(Nil, Nil)

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
          case Reg(r) => useRegs += r
          case _: Label =>
          case _: Int32 =>

        addr match
          case Reg(r)    => useRegs += r
          case Rel(r, _) => useRegs += r
          case _: Label =>

      case Instr.Load(addr: Addr, destReg) =>
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

      case _: Instr.Special[?] =>
        // TODO
    end match

    RegInfo(defRegs.toList, useRegs.toList)
  end analyzeRegInfo

  def subst(instr: Instr, regAlloc: Map[Int, Int]): List[Instr] =
    def substReg(reg: Int): Int = regAlloc.getOrElse(reg, reg)

    def substPart[T](value: T | Reg): T | Reg =
      value match
        case Reg(r) => Reg(substReg(r))
        case _ =>  value

    instr match
      case Instr.Binary(op: BiOp, v1: Operand, v2: Operand, destReg) =>
        Instr.Binary(op, substPart(v1), substPart(v2), substReg(destReg)) :: Nil

      case Instr.Move(v, destReg) =>
        val src = substPart(v)
        val dest = substReg(destReg)
        src match
          case Reg(`dest`) => Nil
          case _           => Instr.Move(src, dest) :: Nil

      case Instr.Store(v: Value, addr: Addr) =>
        Instr.Store(substPart(v), substPart(addr)) :: Nil

      case Instr.Load(addr: Addr, destReg) =>
        Instr.Load(substPart(addr), substReg(destReg)) :: Nil

      case Instr.Jump(addr: Addr) =>
        Instr.Jump(substPart(addr)) :: Nil

      case Instr.JZero(reg: Reg, label: Label) =>
        Instr.JZero(substPart(reg), label) :: Nil

      case _: Instr.Special[?] =>
        // TODO
        instr :: Nil
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

      case Instr.Load(addr: Addr, destReg) =>
        Instr.Load(addr, substReg(destReg))

      case Instr.Jump(addr: Addr) =>
        instr

      case Instr.JZero(reg: Reg, label: Label) =>
        instr

      case _: Instr.Special[?] =>
        // TODO
        instr
    end match

  def substSource(instr: Instr, regAlloc: Map[Int, Int]): Instr =
    def substReg(reg: Int): Int = regAlloc.getOrElse(reg, reg)

    def substPart[T](value: T | Reg): T | Reg =
      value match
        case Reg(r) => Reg(substReg(r))
        case _ =>  value

    instr match
      case Instr.Binary(op: BiOp, v1: Operand, v2: Operand, destReg) =>
        Instr.Binary(op, substPart(v1), substPart(v2), destReg)

      case Instr.Move(v, destReg) =>
        Instr.Move(substPart(v), destReg)

      case Instr.Store(v: Value, addr: Addr) =>
        Instr.Store(substPart(v), substPart(addr))

      case Instr.Load(addr: Addr, destReg) =>
        Instr.Load(substPart(addr), destReg)

      case Instr.Jump(addr: Addr) =>
        Instr.Jump(substPart(addr))

      case Instr.JZero(reg: Reg, label: Label) =>
        Instr.JZero(substPart(reg), label)

      case _: Instr.Special[?] =>
        // TODO
        instr
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
          before += Instr.Load(addr(i), virtualReg)
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

    instrs.flatMap:
      case label: Label        => label :: Nil

      case holder: PlaceHolder => holder :: Nil

      case preInstr: PreInstr  =>
        preInstr match
          case PreInstr.Call(_, _, _) | PreInstr.Return =>
            // spill should never concern call/return
            preInstr :: Nil

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
          cb.add(Instr.Sub(Reg(FP_REG), Int32(frameSize << 2), SP_REG))

        case PlaceHolder.SaveRegisters =>
          for (savedReg, i) <- actualSavedRegs.zipWithIndex do
            val addr = Rel(FP_REG, (-((spillCount + i + 1) << 2)).toByte)
            cb.add(Instr.Store(Reg(savedReg), addr))

        case PlaceHolder.RestoreRegisters =>
          for (savedReg, i) <- actualSavedRegs.zipWithIndex do
            val addr = Rel(FP_REG, (-((spillCount + i + 1) << 2)).toByte)
            cb.add(Instr.Load(addr, savedReg))

        case preInstr: PreInstr =>
          preInstr match
            case PreInstr.Instr(instr) =>
              for instr2 <- subst(instr, regAlloc) do
                val RegInfo(defs, uses) = PreAssembly.analyzeRegInfo(instr2)
                for dest <- defs do assert(dest < VIRTUAL_REG_START_INDEX, dest)
                for use <- uses do assert(use < VIRTUAL_REG_START_INDEX, use)
                cb.add(instr2)

            case PreInstr.Call(addr, _, _) =>
              cb.add(Instr.Jump(addr))

            case PreInstr.Return =>
              // Use SP_REG for simplicity
              cb.add(Instr.Load(Reg(FP_REG), SP_REG))
              cb.add(Instr.Jump(Reg(SP_REG)))

  def doGraphColoring(
    label: Label, preAsm: List[Item],
    regConfig: RegisterConfig, calleeSavedRegs: List[Int],
    cb: CodeBuffer, generator: VirtualRegGenerator): Unit =

    // Register allocation
    var continue = true
    var spillCount = 0
    var instrs = preAsm
    while continue do
      // println(s"<${label.name}>:")
      // println(Assembly.Prog(Nil, instrs, label).show())

      val liveness = Liveness.analyze(instrs)
      // println(liveness)

      val reservedRegisters: List[Int] =
        List(regConfig.FP_REG, regConfig.SP_REG)

      val GraphColoring.Result(regAlloc, stackAlloc, usedRegs) =
          GraphColoring.alloc(
            label.name,
            liveness,
            regConfig.FREE_REGS,
            reservedRegisters,
            VIRTUAL_REG_START_INDEX
          )

      // println(regAlloc)
      // println(stackAlloc)

      def addr(i: Int): Addr =
        Rel(regConfig.FP_REG, (-(i + 1 + spillCount) << 2).toByte)

      if stackAlloc.isEmpty then
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

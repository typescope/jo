import scala.collection.mutable

import Assembly.*



/** Code representation of a function before register allocation.
  *
  * The code is supposed to be ignostic to register allocation algorithms.
  */
object PreAssembly:
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

  type Item = Instr | Label | PlaceHolder

  // TODO: Refactor representation for register allocation
  /** A label corresponds to a function definition */
  class FunLabel(name: String, val paramRegs: List[Int], val returnRegs: List[Int])
  extends Label(name)

  /**
    * Hold generated assembly data and code.
    */
  class ItemBuffer:
    private val code: mutable.ArrayBuffer[Item] = new mutable.ArrayBuffer

    def add(instrs: List[Instr]): Unit = code.addAll(instrs)
    def add(instr : Instr): Unit = code.addOne(instr)
    def place(holder: PlaceHolder): Unit = code.addOne(holder)
    def mark(label: Label): Unit = code.addOne(label)
    def getResult(): List[Item] = code.toList
    def clear(): Unit = code.clear()

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
    stackAlloc: Map[Int, Int],
    allocVirtualReg: () => Int,
    addr: Int => Addr
  ): List[Instr] =

    val RegInfo(defs, uses) = instr.regInfo
    val before = mutable.ArrayBuffer.empty[Instr]
    val after = mutable.ArrayBuffer.empty[Instr]

    var currentInstr = instr
    for use <- uses do
      stackAlloc.get(use) match
        case Some(i) =>
          val virtualReg = allocVirtualReg()
          before += Instr.Load(addr(i), virtualReg)
          currentInstr = substSource(currentInstr, Map(use -> virtualReg))
        case None =>

    for destReg <- defs do
      stackAlloc.get(destReg) match
        case Some(i) =>
          val virtualReg = allocVirtualReg()
          after += Instr.Store(Reg(virtualReg), addr(i))
          currentInstr = substDest(currentInstr, Map(destReg -> virtualReg))
        case None =>

    before += currentInstr
    before ++= after
    before.toList

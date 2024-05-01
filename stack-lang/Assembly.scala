import scala.collection.mutable


/***********************************************************************
 *
 * Assembly Language Definition
 *
 ***********************************************************************/
object Assembly:
  /** A constant 32-bit integer */
  case class Int32(value: Int)

  /** A register */
  case class Reg(index: Int)

  /** A normal class uses referential equality for better performance. */
  class Label(val name: String):
    override def toString() = "Label(" + name + ")"

  /** A dynamic address represented by an offset relative to a register value */
  case class Rel(reg: Byte, offset: Byte)

  type Operand  = Int32 | Reg
  type Addr     = Label | Reg | Rel
  type Constant = Int32 | Label
  type Value    = Int32 | Label | Reg

  enum Arith:
    case Add, Sub, Mul, Div, Mod

  enum Bit:
    case And, Or, Nor, Xor, Sll, Srl

  enum Ord:
    case Gt, Lt, Ge, Le, Eq

  type BiOp = Arith | Bit | Ord

  object Instr:
    def Add(v1: Operand, v2: Operand, destReg: Int) = Binary(Arith.Add, v1, v2, destReg)
    def Sub(v1: Operand, v2: Operand, destReg: Int) = Binary(Arith.Sub, v1, v2, destReg)
    def Mul(v1: Operand, v2: Operand, destReg: Int) = Binary(Arith.Mul, v1, v2, destReg)
    def Div(v1: Operand, v2: Operand, destReg: Int) = Binary(Arith.Div, v1, v2, destReg)
    def Mod(v1: Operand, v2: Operand, destReg: Int) = Binary(Arith.Mod, v1, v2, destReg)

    def And(v1: Operand, v2: Operand, destReg: Int) = Binary(Bit.And, v1, v2, destReg)
    def Or (v1: Operand, v2: Operand, destReg: Int) = Binary(Bit.Or,  v1, v2, destReg)
    def Nor(v1: Operand, v2: Operand, destReg: Int) = Binary(Bit.Nor, v1, v2, destReg)
    def Xor(v1: Operand, v2: Operand, destReg: Int) = Binary(Bit.Xor, v1, v2, destReg)
    def Sll(v1: Operand, v2: Operand, destReg: Int) = Binary(Bit.Sll, v1, v2, destReg)
    def Srl(v1: Operand, v2: Operand, destReg: Int) = Binary(Bit.Srl, v1, v2, destReg)

    def Gt(v1: Operand, v2: Operand, destReg: Int) = Binary(Ord.Gt, v1, v2, destReg)
    def Lt(v1: Operand, v2: Operand, destReg: Int) = Binary(Ord.Lt, v1, v2, destReg)
    def Ge(v1: Operand, v2: Operand, destReg: Int) = Binary(Ord.Ge, v1, v2, destReg)
    def Le(v1: Operand, v2: Operand, destReg: Int) = Binary(Ord.Le, v1, v2, destReg)
    def Eq(v1: Operand, v2: Operand, destReg: Int) = Binary(Ord.Eq, v1, v2, destReg)

  enum Instr:
    case Binary(op: BiOp, v1: Operand, v2: Operand, destReg: Int)

    case Move(v: Value, destReg: Int)
    case Store(v: Value, addr: Addr)
    case Load(addr: Addr, destReg: Int)

    case Jump(addr: Addr)
    case JZero(reg: Reg, label: Label)

    case Special[T](instr: T)

    lazy val regInfo = analyzeRegInfo(this)

  enum Type:
    case Int8, Int32

  enum Data:
    val label: Label
    case Int8(label: Label, v: Byte)
    case Int32(label: Label, v: Int)
    case Uninit(label: Label, tp: Type)

  /** Register usage information of an instruction */
  case class RegInfo(defs: List[Int], uses: List[Int])

  case class Prog(data: List[Data], instrs: List[Instr | Label], entry: Label):
    def show() =
      val sb = new StringBuilder

      for item <- data do
        item match
          case Data.Int8(l, v)     => sb.append(l.name + " = " + v  + "\n")
          case Data.Int32(l, v)    => sb.append(l.name + " = " + v + "\n")
          case Data.Uninit(l, tp)  => sb.append(l.name + " : " + tp + "\n")

      var i = 0

      for instr <- instrs do
        instr match
          case l: Label =>
            sb.append(s"${l.name}:\n")

          case _ =>
            sb.append(s"$i\t$instr\n")
            i += 1
        end match
      end for

      sb.toString()
  end Prog

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

          case fun: FunLabel =>
            useRegs ++= fun.paramRegs
            defRegs ++= fun.returnRegs

          case l: Label => // local jump

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
          case Reg(`destReg`) => Nil
          case _              => Instr.Move(src, dest) :: Nil

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

  /**
    * Hold generated assembly data and code.
    */
  class CodeBuffer(val entry: Label):
    private val dataSection: mutable.ArrayBuffer[Data] = new mutable.ArrayBuffer
    private val codeSection: mutable.ArrayBuffer[Instr | Label] = new mutable.ArrayBuffer

    def add(data  : Data       ): Unit = dataSection.addOne(data)
    def add(instrs: List[Instr]): Unit = codeSection.addAll(instrs)
    def add(instr : Instr      ): Unit = codeSection.addOne(instr)

    def mark(label: Label): Unit = codeSection.addOne(label)

    def getResult(): Prog = Prog(dataSection.toList, codeSection.toList, entry)

  /**
    * A simple register allocator.
    *
    * @param freeRegs All registers for temporary usage in a processor.
    *
    * The registers reserved for call stack pointer and value stack pointer are excluded.
    */
  class RegisterAllocator(freeRegs: List[Byte]):
    var freeIndex = 0

    /**
      * Allocate a temp register for usage.
      *
      * The allocated register will be released after the function return.
      *
      * TODO: spilling if no temp registers are available?
      */
    def useReg(fn: Byte => Unit): Unit =
      if freeIndex >= freeRegs.size then
        throw new Exception("No register available")
      else
        val freeReg = freeIndex
        freeIndex += 1
        fn(freeRegs(freeReg))
        freeIndex -= 1


    /**
      * Allocate two temporary registers for usage.
      *
      * @see useReg
      */
    def useTwoReg(fn: (Byte, Byte) => Unit): Unit =
      useReg: r1 =>
        useReg: r2 =>
          fn(r1, r2)

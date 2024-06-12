import scala.collection.mutable


/***********************************************************************
 *
 * Assembly Language Definition
 *
 * TODO remove abstraction
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
  case class Rel(reg: Int, offset: Int)

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

    // TODO: Change to JCond --- conditional jump
    case JZero(reg: Reg, label: Label)

    case Special[T](instr: T)

  enum Type:
    case Int8, Int32

  enum Data:
    val label: Label
    case Int8(label: Label, v: Byte)
    case Int32(label: Label, v: Int)
    case Uninit(label: Label, tp: Type)

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

  /** Register configuration */
  trait RegisterConfig:
    /** Registers available for free usage  */
    val FREE_REGS: List[Int]

    /** Reserved call stack register */
    val SP_REG: Int

    /** Reserved frame pointer register */
    val FP_REG: Int

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

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

  /** An alignment requirement */
  case class Align(n: Int)

  type Operand  = Int32 | Reg
  type Addr     = Label | Reg
  type Constant = Int32 | Label
  type Value    = Int32 | Label | Reg
  type Attr     = Label | Align

  enum BiOp:
    case Add, Sub, Mul, Div, Mod, And, Or, Xor, Sll, Srl, Gt, Lt, Ge, Le, Eq

  object Instr:
    def Add(v1: Operand, v2: Operand, destReg: Int) = Binary(BiOp.Add, v1, v2, destReg)
    def Sub(v1: Operand, v2: Operand, destReg: Int) = Binary(BiOp.Sub, v1, v2, destReg)
    def Mul(v1: Operand, v2: Operand, destReg: Int) = Binary(BiOp.Mul, v1, v2, destReg)
    def Div(v1: Operand, v2: Operand, destReg: Int) = Binary(BiOp.Div, v1, v2, destReg)
    def Mod(v1: Operand, v2: Operand, destReg: Int) = Binary(BiOp.Mod, v1, v2, destReg)

    def And(v1: Operand, v2: Operand, destReg: Int) = Binary(BiOp.And, v1, v2, destReg)
    def Or (v1: Operand, v2: Operand, destReg: Int) = Binary(BiOp.Or,  v1, v2, destReg)
    def Xor(v1: Operand, v2: Operand, destReg: Int) = Binary(BiOp.Xor, v1, v2, destReg)
    def Sll(v1: Operand, v2: Operand, destReg: Int) = Binary(BiOp.Sll, v1, v2, destReg)
    def Srl(v1: Operand, v2: Operand, destReg: Int) = Binary(BiOp.Srl, v1, v2, destReg)

    def Gt(v1: Operand, v2: Operand, destReg: Int) = Binary(BiOp.Gt, v1, v2, destReg)
    def Lt(v1: Operand, v2: Operand, destReg: Int) = Binary(BiOp.Lt, v1, v2, destReg)
    def Ge(v1: Operand, v2: Operand, destReg: Int) = Binary(BiOp.Ge, v1, v2, destReg)
    def Le(v1: Operand, v2: Operand, destReg: Int) = Binary(BiOp.Le, v1, v2, destReg)
    def Eq(v1: Operand, v2: Operand, destReg: Int) = Binary(BiOp.Eq, v1, v2, destReg)

  enum Instr:
    case Not(v: Operand, destReg: Int)
    case Const(v: Constant, destReg: Int)
    case Binary(op: BiOp, v1: Operand, v2: Operand, destReg: Int)

    case Store(v: Value, addr: Addr)
    case Load(addr: Addr, destReg: Int)

    case Jump(addr: Addr)
    case JZero(reg: Reg, label: Label)

    case Special[T](instr: T)

  enum Type:
    case Int8, Int32

  enum Data:
    case Int8(v: Byte)
    case Int32(v: Int)
    case Uninit(tp: Type)

  case class Prog(data: List[Data | Attr], instrs: List[Instr | Label], entry: Label):
    def show() =
      val sb = new StringBuilder

      for item <- data do
        item match
          case Data.Int8(v)     => sb.append("  " + v  + "\n")
          case Data.Int32(v)    => sb.append("  " + v + "\n")
          case Data.Uninit(tp)  => sb.append("  " + tp + "\n")
          case Align(n)         => sb.append(".align " + n + "\n")
          case l: Label         => sb.append(l.name + ":" + "\n")

      for instr <- instrs do
        instr match
          case l: Label =>
            sb.append(l.name + ":" + "\n")

          case _ =>
            sb.append("  " + instr + "\n")
      end for

      sb.toString()

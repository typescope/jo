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

  type Operand  = Int32 | Reg
  type Addr     = Label | Reg
  type Constant = Int32 | Label
  type Value    = Int32 | Label | Reg

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

      for instr <- instrs do
        instr match
          case l: Label =>
            sb.append(l.name + ":" + "\n")

          case _ =>
            sb.append("  " + instr + "\n")
      end for

      sb.toString()
  end Prog

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
  class RegisterAllocator(freeRegs: List[Int]):
    var freeIndex = 0

    /**
      * Allocate a temp register for usage.
      *
      * The allocated register will be released after the function return.
      *
      * TODO: spilling if no temp registers are available?
      */
    def useReg(fn: Int => Unit): Unit =
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
    def useTwoReg(fn: (Int, Int) => Unit): Unit =
      useReg: r1 =>
        useReg: r2 =>
          fn(r1, r2)

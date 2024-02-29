/************************************************************************
 *                                                                      *
 * Assembler for x86 architecture.                                      *
 *                                                                      *
 * Reference:                                                           *
 *                                                                      *
 *   [1] Intel® 64 and IA-32 Architectures Software Developer’s Manual *
 *                                                                      *
 *   [2] Table 2-2. 32-Bit Addressing Forms with the ModR/M Byte in [1] *
 *                                                                      *
 ************************************************************************/

import scala.collection.mutable

import Assembly.*
import IO.{ ByteBuffer, Patch, PatchableBuffer, withPatch }

object X86:
  /**
    * 0 - EAX
    * 1 - ECX
    * 2 - EDX
    * 3 - EBX
    * 4 - ESP
    * 5 - EBP
    * 6 - ESI
    * 7 - EDI
    *
    * See Table 2-2. 32-Bit Addressing Forms with the ModR/M Byte in
    *
    *    Intel® 64 and IA-32 Architectures Software Developer’s Manual
    */
  final val EAX = 0
  final val ECX = 1
  final val EDX = 2
  final val EBX = 3
  final val ESP = 4
  final val EBP = 5
  final val ESI = 6
  final val EDI = 7

  /**
    * Special x86 instructions for performance optimization
    */
  sealed abstract class Extension
  case class LoadRel(addr: Rel, destReg: Int) extends Extension
  case class StoreRel(value: Value, addr: Rel) extends Extension
  case object Syscall extends Extension


  /** Relative address with offset */
  case class Rel(baseReg: Int, offset: Byte)

  def lower(data: Data)(using pb: PatchableBuffer): Unit =
    data match
      case Data.Int8(v)     => pb.addByte(v)

      case Data.Int32(v)    => pb.addInt(v)

      case Data.Uninit(tp)  =>
        tp match
          case Type.Int8  =>  pb.addByte(0)
          case Type.Int32 =>  pb.addInt(0)

  def lower(instr: Instr)(using pb: PatchableBuffer): Unit =
    instr match
      case binOp: Instr.Binary =>
        lower(binOp)

      case Instr.Store(v, addr) =>
        store(v, addr)

      case Instr.Load(addr, destReg) =>
        load(addr, destReg)

      case Instr.Jump(addr) =>
        jump(addr)

      case Instr.JZero(r, label) =>
        jzero(r, label)

      case Instr.Const(v, destReg) =>
        const(v, Reg(destReg))

      case Instr.Not(v, destReg) =>
        not(Reg(destReg), v)

      case special: Instr.Special[Extension @unchecked] =>
        lower(special.instr)

  def lower(instr: Extension)(using pb: PatchableBuffer) =
    instr match
      case LoadRel(rel, dest)  => load(rel, dest)
      case StoreRel(rel, dest) => store(rel, dest)

      case Syscall =>
        pb.addBytes(0xcd.toByte, 0x80.toByte)

  def lower(binOp: Instr.Binary)(using pb: PatchableBuffer) =
    binOp match
      case Instr.Binary(op, r1: Reg, r2: Reg, destReg) =>
        if destReg == r1.index then
          binaryOperation(op, r1, r2)
        else if destReg == r2.index then
          op match
            case BiOp.Add | BiOp.Mul | BiOp.And | BiOp.Or | BiOp.Xor | BiOp.Eq =>
              // commutative operations
              binaryOperation(op, r2, r1)

            case BiOp.Sub | BiOp.Div | BiOp.Mod | BiOp.Srl | BiOp.Sll  =>
              push(r1.index)
              binaryOperation(op, r1, r2)
              move(r1, r2)
              pop(r1.index)

            case BiOp.Gt  =>
              binaryOperation(BiOp.Le, r2, r1)

            case BiOp.Lt  =>
              binaryOperation(BiOp.Ge, r2, r1)

            case BiOp.Ge  =>
              binaryOperation(BiOp.Lt, r2, r1)

            case BiOp.Le  =>
              binaryOperation(BiOp.Gt, r2, r1)

        else
          val rDest = Reg(destReg)
          move(r1, rDest)
          binaryOperation(op, rDest, r2)

      case Instr.Binary(op, r: Reg, v, destReg) =>
        if destReg == r.index then
          binaryOperation(op, r, v)
        else
          val rDest = Reg(destReg)
          move(r, rDest)
          binaryOperation(op, rDest, v)

      case Instr.Binary(op, v, r: Reg, destReg) =>
        if destReg == r.index then
          binaryOperation(op, r, v)
        else
          val rDest = Reg(destReg)
          move(r, rDest)
          binaryOperation(op, rDest, v)

      case Instr.Binary(op, v1: Int32, v2: Int32, destReg) =>
        val rDest = Reg(destReg)
        move(v1, rDest)
        binaryOperation(op, rDest, v2)

  /** System call */
  def syscall()(using pb: PatchableBuffer) =
    // 0F 34    SYSENTER
    pb.addByte(0x0F)
    pb.addByte(0x34)

  def push(reg: Int)(using pb: PatchableBuffer) =
    // 50+rd    PUSH r32
    pb.addByte((0x50 | reg).toByte)

  def pop(reg: Int)(using pb: PatchableBuffer) =
    // 58+ rd    POP r32
    pb.addByte((0x58 | reg).toByte)

  /** Add the value to the register */
  def add(reg: Reg, v: Operand)(using pb: PatchableBuffer) =
    v match
      case Reg(rv) =>
        // 03 /r      ADD r32, r/m32
        pb.addByte(0x03)
        pb.addByte((0xC0 | (reg.index << 3) | rv).toByte)

      case Int32(v) =>
        // 81 /0 id   ADD r/m32, imm32
        pb.addByte(0x81.toByte)
        pb.addByte((0xC0 | reg.index).toByte)
        pb.addInt(v)

  /** Subtract the value from the register */
  def sub(reg: Reg, v: Operand)(using pb: PatchableBuffer) =
    v match
      case Reg(rv) =>
        // 2B /r   SUB r32, r/m32
        pb.addByte(0x2B)
        pb.addByte((0xC0 | (reg.index << 3) | rv).toByte)

      case Int32(v) =>
        // 81 /5 id    SUB r/m32, imm32
        //
        // See A.4.2 Opcode Extension Tables in [1]
        //
        pb.addByte(0x81.toByte)
        pb.addByte((0xC0 | (5 << 3) | reg.index).toByte)
        pb.addInt(v)

  /** Multiply the register with the value */
  def mul(reg: Reg, v: Operand)(using pb: PatchableBuffer) =
    v match
      case Reg(rv) =>
        // 0F AF /r     IMUL r32, r/m32
        pb.addByte(0x0F)
        pb.addByte(0xAF.toByte)
        pb.addByte((0xC0 | (reg.index << 3) | rv).toByte)

      case Int32(v) =>
        // 69 /r id     IMUL r32, r/m32, imm32
        pb.addByte(0x69)
        pb.addByte((0xC0 | (reg.index << 3) | reg.index).toByte)
        pb.addInt(v)

  /** Divide the register with the value */
  def div(reg: Reg, v: Operand)(using pb: PatchableBuffer) =
    // TODO: reminder sign does not always agree with divident
    v match
      case Reg(rv) =>
        // F7 /7	IDIV r/m32
        // division uses dedicated registers EDX:EAX

        if reg.index == EAX && rv != EDX then // fast track
          push(EDX)
          move(Int32(0), Reg(EDX))
          pb.addByte(0xF7.toByte)
          pb.addByte((0xC0 | (7 << 3) | rv).toByte)
          pop(EDX)

        else if reg.index == rv then // it's implied that reg and rv are not EAX
          push(EAX)
          move(reg, Reg(EAX))        // divisor and divident are EAX
          push(EDX)
          move(Int32(0), Reg(EDX))
          pb.addByte(0xF7.toByte)
          pb.addByte((0xC0 | (7 << 3) | EAX).toByte)
          move(Reg(EAX), reg)
          pop(EDX)
          pop(EAX)

        else                        // reg and rv are not EAX, not equal
          push(EAX)
          move(reg, Reg(EAX))
          move(Reg(rv), reg)        // divisor in reg
          push(EDX)
          move(Int32(0), Reg(EDX))
          pb.addByte(0xF7.toByte)
          pb.addByte((0xC0 | (7 << 3) | reg.index).toByte)
          move(Reg(EAX), reg)
          pop(EDX)
          pop(EAX)

      case _: Int32 =>
        // F7 /7	IDIV r/m32
        // division uses dedicated registers EDX:EAX
        if reg.index != EAX then
          push(EAX)
          move(reg, Reg(EAX))

        push(EDX)
        move(Int32(0), Reg(EDX))
        push(ECX) // to store divisor
        move(v, Reg(ECX))

        pb.addByte(0xF7.toByte)
        pb.addByte((0xC0 | (7 << 3) | ECX).toByte)

        pop(ECX)
        pop(EDX)

        if reg.index != EAX then
          move(Reg(EAX), reg)
          pop(EAX)


  /** Modulo the register with the value */
  def mod(reg: Reg, v: Operand)(using pb: PatchableBuffer) =
    // TODO: reminder sign does not always agree with divident
    v match
      case Reg(rv) =>
        // F7 /7	IDIV r/m32
        // division uses dedicated registers EDX:EAX

        if reg.index == EAX && rv != EDX then // fast track
          push(EDX)
          move(Int32(0), Reg(EDX))
          pb.addByte(0xF7.toByte)
          pb.addByte((0xC0 | (7 << 3) | rv).toByte)
          move(Reg(EDX), reg)
          pop(EDX)

        else if reg.index == rv then // it's implied that reg and rv are not EAX
          push(EAX)
          move(reg, Reg(EAX))        // divisor and divident are EAX
          if rv != EDX then push(EDX)
          move(Int32(0), Reg(EDX))
          pb.addByte(0xF7.toByte)
          pb.addByte((0xC0 | (7 << 3) | EAX).toByte)
          move(Reg(EDX), reg)
          if rv != EDX then pop(EDX)
          pop(EAX)

        else                        // reg and rv are not EAX, not equal
          push(EAX)
          move(reg, Reg(EAX))
          move(Reg(rv), reg)        // divisor in reg
          if rv != EDX then push(EDX)
          move(Int32(0), Reg(EDX))
          pb.addByte(0xF7.toByte)
          pb.addByte((0xC0 | (7 << 3) | reg.index).toByte)
          move(Reg(EDX), reg)
          if rv != EDX then pop(EDX)
          pop(EAX)

      case _: Int32 =>
        // F7 /7	IDIV r/m32
        // division uses dedicated registers EDX:EAX
        if reg.index != EAX then
          push(EAX)
          move(reg, Reg(EAX))

        push(EDX)
        move(Int32(0), Reg(EDX))
        push(ECX) // to store divisor
        move(v, Reg(ECX))

        pb.addByte(0xF7.toByte)
        pb.addByte((0xC0 | (7 << 3) | ECX).toByte)

        move(Reg(EDX), reg)
        pop(ECX)
        pop(EDX)

        if reg.index != EAX then
          pop(EAX)

  /** Logical AND the value to the register */
  def and(reg: Reg, v: Operand)(using pb: PatchableBuffer) =
    v match
      case Reg(rv) =>
        // 23 /r   AND r32, r/m32
        pb.addByte(0x23)
        pb.addByte((0xC0 | (reg.index << 3) | rv).toByte)

      case Int32(v) =>
        // 81 /4 id     AND r/m32, imm32
        pb.addByte(0x81.toByte)
        pb.addByte((0xC0 | (4 << 3) | reg.index).toByte)
        pb.addInt(v)

  /** Logical OR the value to the register */
  def or(reg: Reg, v: Operand)(using pb: PatchableBuffer) =
    v match
      case Reg(rv) =>
        // 0B /r	OR r32, r/m32
        pb.addByte(0x0B)
        pb.addByte((0xC0 | (reg.index << 3) | rv).toByte)

      case Int32(v) =>
        // 81 /1 id	OR r/m32, imm32
        pb.addByte(0x81.toByte)
        pb.addByte((0xC0 | (1 << 3) | reg.index).toByte)
        pb.addInt(v)

  /** Shift left logically */
  def sll(reg: Reg, v: Operand)(using pb: PatchableBuffer) =
    v match
      case r2: Reg =>
        // D3 /4	SAL r/m32, CL
        if r2.index != ECX then
          push(ECX)
          move(r2, Reg(ECX))
          pb.addByte(0xD3.toByte)
          pb.addByte((0xC0 | (4 << 3) | reg.index).toByte)
          pop(ECX)
        else
          pb.addByte(0xD3.toByte)
          pb.addByte((0xC0 | (4 << 3) | reg.index).toByte)

      case Int32(v) =>
        // C1 /4 ib	SAL r/m32, imm8
        assert(v >= 0 && v < 256, "Shift too big, expect < 256, found = " + v)
        pb.addByte(0xC1.toByte)
        pb.addByte((0xC0 | (4 << 3) | reg.index).toByte)
        pb.addByte(v.toByte)

  /** Shift right logically */
  def srl(reg: Reg, v: Operand)(using pb: PatchableBuffer) =
    v match
      case r2: Reg =>
        // D3 /5	SHR r/m32, CL
        if r2.index != ECX then
          push(ECX)
          move(r2, Reg(ECX))
          pb.addByte(0xD3.toByte)
          pb.addByte((0xC0 | (5 << 3) | reg.index).toByte)
          pop(ECX)
        else
          pb.addByte(0xD3.toByte)
          pb.addByte((0xC0 | (5 << 3) | reg.index).toByte)

      case Int32(v) =>
        // C1 /5 ib	SHR r/m32, imm8
        assert(v >= 0 && v < 256, "Shift too big, expect < 256, found = " + v)
        pb.addByte(0xC1.toByte)
        pb.addByte((0xC0 | (1 << 5) | reg.index).toByte)
        pb.addByte(v.toByte)

  def not(reg: Reg, v: Operand)(using pb: PatchableBuffer) =
    // F7 /2    NOT r/m32
    pb.addByte(0xF7.toByte)
    pb.addByte((0xC0 | (2 << 3) | reg.index).toByte)

  /** Logical XOR the value to the register */
  def xor(reg: Reg, v: Operand)(using pb: PatchableBuffer) =
    v match
      case Reg(rv) =>
        // 33 /r	XOR r32, r/m32
        pb.addByte(0x33)
        pb.addByte((0xC0 | (reg.index << 3) | rv).toByte)

      case Int32(v) =>
        // 81 /6 id	XOR r/m32, imm32
        pb.addByte(0x81.toByte)
        pb.addByte((0xC0 | (6 << 3) | reg.index).toByte)
        pb.addInt(v)

  /** Move the value to the register */
  def move(v: Operand, reg: Reg)(using pb: PatchableBuffer) =
    v match
      case Reg(rv) =>
        // 8B /r       MOV r32, r/m32
        pb.addByte(0x8B.toByte)
        pb.addByte((0xC0 | (reg.index << 3) | rv).toByte)

      case Int32(v) =>
        // B8+ rd id   MOV r32, imm32
        pb.addByte((0xB8 | reg.index).toByte)
        pb.addInt(v)

  /** Move the value to the register */
  def const(c: Constant, reg: Reg)(using pb: PatchableBuffer) =
    c match
      case v: Int32 =>
        move(v, reg)

      case l: Label =>
        withPatch(l, 5): (bb, loc) =>
          // B8+ rd id   MOV r32, imm32
          bb.addByte((0xB8 | reg.index).toByte)
          bb.addInt(loc)

  def load(addr: Addr | Rel, destReg: Int)(using pb: PatchableBuffer) =
    addr match
      case Reg(r) =>
        // See Table 2-2. 32-Bit Addressing Forms with the ModR/M Byte in [1]

        // 8B /r MOV r32, r/m32
        if r == 4 then // ESP
          pb.addByte(0x8B.toByte)
          pb.addByte(((destReg << 3) | r).toByte)
          pb.addByte(0x24)
        else if r == 5 then // EBP
          pb.addByte(0x8B.toByte)
          pb.addByte((0x40 | (destReg << 3) | r).toByte)
          pb.addByte(0)
        else
          pb.addByte(0x8B.toByte)
          pb.addByte(((destReg << 3) | r).toByte)

      case Rel(r, offset) =>
        // See Table 2-2. 32-Bit Addressing Forms with the ModR/M Byte in [1]

        if r == 4 then // ESP
          pb.addByte(0x8B.toByte)
          pb.addByte((0x40 | (destReg << 3) | r).toByte)
          pb.addByte(0x24)
          pb.addByte(offset)
        else
          pb.addByte(0x8B.toByte)
          pb.addByte((0x40 | (destReg << 3) | r).toByte)
          pb.addByte(offset)

      case l: Label =>
        withPatch(l, 6): (bb, loc) =>
          // 8B /r  MOV r32, r/m32
          bb.addByte(0x8B.toByte)
          bb.addByte(((destReg << 3) | 5).toByte)
          bb.addInt(loc)

  def store(v: Value, addr: Addr | Rel)(using pb: PatchableBuffer) =
    addr match
      case Reg(rd) =>
        v match
          case l: Label =>
            // C7 /0 id    MOV r/m32, imm32
            if rd == 4 then // ESP
              withPatch(l, 7): (bb, loc) =>
                bb.addByte(0xC7.toByte)
                bb.addByte(rd.toByte)
                bb.addByte(0x24)
                bb.addInt(loc)
            else if rd == 5 then
              withPatch(l, 7): (bb, loc) =>
                bb.addByte(0xC7.toByte)
                bb.addByte((0x40 | rd).toByte)
                bb.addByte(0)
                bb.addInt(loc)
            else
              withPatch(l, 7): (bb, loc) =>
                bb.addByte(0xC7.toByte)
                bb.addByte(rd.toByte)
                bb.addInt(loc)

          case Int32(v) =>
            // C7 /0 id    MOV r/m32, imm32
            if rd == 4 then // ESP
              pb.addByte(0xC7.toByte)
              pb.addByte(rd.toByte)
              pb.addByte(0x24)
              pb.addInt(v)
            else if rd == 5 then
              pb.addByte(0xC7.toByte)
              pb.addByte((0x40 | rd).toByte)
              pb.addByte(0)
              pb.addInt(v)
            else
              pb.addByte(0xC7.toByte)
              pb.addByte(rd.toByte)
              pb.addInt(v)

          case Reg(rv) =>
            // 89 /r     MOV r/m32, r32
            if rd == 4 then // esp
              pb.addByte(0x89.toByte)
              pb.addByte(((rv << 3) | 4).toByte)
              pb.addByte(0x24)
            else if rd == 5 then // ebp
              pb.addByte(0x89.toByte)
              pb.addByte((0x40 | (rv << 3) | rd).toByte)
              pb.addByte(0)
            else
              pb.addByte(0x89.toByte)
              pb.addByte(((rv << 3) | rd).toByte)

      case Rel(rd, offset) =>

        v match
          case l: Label =>
            // C7 /0 id    MOV r/m32, imm32
            withPatch(l, 7): (bb, loc) =>
              if rd == 4 then // ESP
                bb.addByte(0xC7.toByte)
                bb.addByte(0x44)
                bb.addByte(0x24)
                bb.addByte(offset)
                bb.addInt(loc)
              else
                bb.addByte(0xC7.toByte)
                bb.addByte((0x40 | rd).toByte)
                bb.addByte(offset)
                bb.addInt(loc)

          case Int32(v) =>
            // C7 /0 id    MOV r/m32, imm32
            if rd == 4 then // ESP
              pb.addByte(0xC7.toByte)
              pb.addByte(0x44)
              pb.addByte(0x24)
              pb.addByte(offset)
              pb.addInt(v)
            else
              pb.addByte(0xC7.toByte)
              pb.addByte((0x40 | rd).toByte)
              pb.addByte(offset)
              pb.addInt(v)

          case Reg(rv) =>
            // 89 /r     MOV r/m32, r32
            if rd == 4 then // ESP
              pb.addByte(0x89.toByte)
              pb.addByte((0x40 | (rv << 3) | 4).toByte)
              pb.addByte(0x24)
              pb.addByte(offset)
            else
              pb.addByte(0x89.toByte)
              pb.addByte((0x40 | (rv << 3) | rd).toByte)
              pb.addByte(offset)

      case l: Label =>
        v match
          case lv: Label =>
            // C7 /0 id    MOV r/m32, imm32
            pb.resolve(l) match
              case Some(locDest) =>
                withPatch(lv, 10): (bb, loc) =>
                  bb.addByte(0xC7.toByte)
                  bb.addByte(5)
                  bb.addInt(locDest)
                  bb.addInt(loc)

              case None =>
                pb.resolve(lv) match
                  case Some(locVal) =>
                    withPatch(l, 10): (bb, locDest) =>
                      bb.addByte(0xC7.toByte)
                      bb.addByte(5)
                      bb.addInt(locDest)
                      bb.addInt(locVal)

                  case None =>
                    val patchFn: () => List[Byte] = () =>
                      val Some(dest) = pb.resolve(l): @unchecked
                      val Some(value) = pb.resolve(lv): @unchecked
                      List(
                          0xC7.toByte,
                          5,
                          (dest & 0xFF).toByte,
                          ((dest >> 8) & 0xFF).toByte,
                          ((dest >> 16) & 0xFF).toByte,
                          ((dest >> 24) & 0xFF).toByte,
                          (value & 0xFF).toByte,
                          ((value >> 8) & 0xFF).toByte,
                          ((value >> 16) & 0xFF).toByte,
                          ((value >> 24) & 0xFF).toByte,
                      )

                    pb.addPatch(Patch(pb.currentOffset(), 10, patchFn))

          case Int32(v) =>
            // C7 /0 id    MOV r/m32, imm32
            withPatch(l, 10): (bb, loc) =>
              bb.addByte(0xC7.toByte)
              bb.addByte(5)
              bb.addInt(loc)
              bb.addInt(v)

          case Reg(rv) =>
            // 89 /r     MOV r/m32, r32
            withPatch(l, 6): (bb, loc) =>
              bb.addByte(0x89.toByte)
              bb.addByte(((rv << 3) | 5).toByte)
              bb.addInt(loc)


  def jump(addr: Addr)(using pb: PatchableBuffer) =
    addr match
      case Reg(r) =>
        // TODO: The instructin is invalid under 64-bit mode.
        // FF /4 JMP r/m32
        pb.addByte(0xFF.toByte)
        pb.addByte((0xC0 | (4 << 3) | r).toByte)

      case l: Label =>
        val currentAddr = pb.currentAddr()
        // E9 cd   JMP rel32
        withPatch(l, 5): (bb, loc) =>
          val relativeAddr = loc - currentAddr - 5
          bb.addByte(0xE9.toByte)
          bb.addInt(relativeAddr)

  def eql(reg: Reg, v: Operand)(using pb: PatchableBuffer) =
    cmp(reg, v, 0x94.toByte)

  def gt(reg: Reg, v: Operand)(using pb: PatchableBuffer) =
    cmp(reg, v, 0x9F.toByte)

  def ge(reg: Reg, v: Operand)(using pb: PatchableBuffer) =
    cmp(reg, v, 0x9D.toByte)

  def lt(reg: Reg, v: Operand)(using pb: PatchableBuffer) =
    cmp(reg, v, 0x9C.toByte)

  def le(reg: Reg, v: Operand)(using pb: PatchableBuffer) =
    cmp(reg, v, 0x9E.toByte)

  def cmp(reg: Reg, v: Operand, setcc: Byte)(using pb: PatchableBuffer) =
    v match
      case Reg(rv) =>
        // 3B /r    CMP r32, r/m32
        pb.addByte(0x3B)
        pb.addByte((0xC0 | (reg.index << 3) | rv).toByte)

      case Int32(v) =>
        // 81 /7 id   CMP r/m32, imm32
        pb.addByte(0x81.toByte)
        pb.addByte((0xC0 | (7 << 3) | reg.index).toByte)
        pb.addInt(v)
    end match

    // 0F 94    SETE r/m8
    pb.addByte(0x0F)
    pb.addByte(setcc)
    pb.addByte((0xC0 | reg.index).toByte)

    // Clear the high bytes of the register is important as SETE only set the low byte.
    // 81 /4 id    AND r/m32, imm32
    pb.addByte(0x81.toByte)
    pb.addByte((0xC0 | (4 << 3) | reg.index).toByte)
    pb.addInt(0x000F)

  def jzero(reg: Reg, label: Label)(using pb: PatchableBuffer) =
    // TODO: Handle the pattern [Eq(o1, o2, r), JZero(r, l)] to generate one fewer instruction.

    // 81 /7 id   CMP r/m32, imm32
    pb.addByte(0x81.toByte)
    pb.addByte((0xC0 | (7 << 3) | reg.index).toByte)
    pb.addInt(0)

    val offset = pb.currentAddr()
    withPatch(label, 6): (bb, loc) =>
      // 0F 84 cd   JZ rel32  (Jump near if 0 (ZF=1))
      val relativeAddr = loc - offset - 6
      bb.addByte(0x0F)
      bb.addByte(0x84.toByte)
      bb.addInt(relativeAddr)

  /** Perform binary operation on the register with the given operand */
  def binaryOperation(op: BiOp, reg: Reg, v: Operand)(using pb: PatchableBuffer) =
    op match
      case BiOp.Add => add(reg, v)
      case BiOp.Sub => sub(reg, v)
      case BiOp.Mul => mul(reg, v)
      case BiOp.Div => div(reg, v)
      case BiOp.Mod => mod(reg, v)

      case BiOp.And => and(reg, v)
      case BiOp.Or  => or(reg, v)
      case BiOp.Xor => xor(reg, v)

      case BiOp.Srl => srl(reg, v)
      case BiOp.Sll => sll(reg, v)

      case BiOp.Eq  => eql(reg, v)
      case BiOp.Gt  => gt(reg, v)
      case BiOp.Lt  => lt(reg, v)
      case BiOp.Ge  => ge(reg, v)
      case BiOp.Le  => le(reg, v)

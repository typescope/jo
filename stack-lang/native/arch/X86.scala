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
package native
package arch

import Assembly.*
import Assembler.{ Patch, PatchableBuffer, withPatch }

/**
  * Translate assembly code to x86 machine code.
  *
  * It assumes that the ESP register is used as the stack pointer so that it may
  * use push/pop to spill registers for temporary usage.
  *
  * The code is OS-agnostic.
  */
object X86 extends Assembler:
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
  final val EAX: Byte = 0
  final val ECX: Byte = 1
  final val EDX: Byte = 2
  final val EBX: Byte = 3
  final val ESP: Byte = 4
  final val EBP: Byte = 5
  final val ESI: Byte = 6
  final val EDI: Byte = 7


  def lowerData(data: List[Data])(using pb: PatchableBuffer): Unit =
    for item <- data do X86.lower(item)

  def lowerCode(instrs: List[Instr | Label])(using pb: PatchableBuffer): Unit =
    for instr <- instrs do
      instr match
        case label: Label => pb.defineLabel(label)
        case instr: Instr => X86.lower(instr)

  def lower(data: Data)(using pb: PatchableBuffer): Unit =
    data match
      case Data.Int8(l, v)     =>
        pb.defineLabel(l)
        pb.addByte(v)

      case Data.Int32(l, v)    =>
        pb.align(4)
        pb.defineLabel(l)
        pb.addInt(v)

      case Data.StringLit(l, v)    =>
        pb.align(4)
        pb.defineLabel(l)
        val bytes = v.getBytes("UTF-8")
        pb.addInt(bytes.size)
        for b <- bytes do pb.addByte(b)

      case Data.Uninit(l, tp)  =>
        tp match
          case Type.Int8  =>
            pb.defineLabel(l)
            pb.addByte(0)

          case Type.Int32 =>
            pb.defineLabel(l)
            pb.align(4)
            pb.addInt(0)

  def lower(instr: Instr)(using pb: PatchableBuffer): Unit =
    instr match
      case binOp: Instr.Binary =>
        lower(binOp)

      case Instr.Store(v, addr) =>
        store(v, addr)

      case Instr.Load(addr, destReg, size) =>
        load(addr, destReg, size)

      case Instr.Move(v, destReg) =>
        move(v, destReg)

      case Instr.Jump(addr) =>
        jump(addr)

      case Instr.JZero(r, label) =>
        jzero(r.index, label)

  def lower(binOp: Instr.Binary)(using pb: PatchableBuffer): Unit =
    binOp match
      case Instr.Binary(op, r1: Reg, r2: Reg, destReg) =>
        if destReg == r1.index then
          binaryOperation(op, r1.index, r2)
        else if destReg == r2.index then
          op match
            case Arith.Add | Arith.Mul | Bit.And | Bit.Or | Bit.Nor | Bit.Xor | Ord.Eq =>
              // commutative operations
              binaryOperation(op, r2.index, r1)

            case Arith.Sub | Arith.Div | Arith.Mod | Bit.Srl | Bit.Sll  =>
              push(r1.index)
              binaryOperation(op, r1.index, r2)
              move(r1, r2.index)
              pop(r1.index)

            case Ord.Gt  =>
              binaryOperation(Ord.Lt, r2.index, r1)

            case Ord.Lt  =>
              binaryOperation(Ord.Gt, r2.index, r1)

            case Ord.Ge  =>
              binaryOperation(Ord.Le, r2.index, r1)

            case Ord.Le  =>
              binaryOperation(Ord.Ge, r2.index, r1)

        else
          move(r1, destReg)
          binaryOperation(op, destReg, r2)

      case Instr.Binary(op, r: Reg, v, destReg) =>
        if destReg == r.index then
          binaryOperation(op, r.index, v)
        else
          move(r, destReg)
          binaryOperation(op, destReg, v)

      case Instr.Binary(op, v, r: Reg, destReg) =>
        if destReg == r.index then
          op match
            case Arith.Add | Arith.Mul | Bit.And | Bit.Or | Bit.Nor | Bit.Xor | Ord.Eq =>
              // commutative operations
              binaryOperation(op, r.index, v)

            case Arith.Sub | Arith.Div | Arith.Mod | Bit.Srl | Bit.Sll  =>
              // Spill a register for temporary usage
              val rTemp = if destReg == EAX then EBX else EAX
              push(rTemp)
              lower(Instr.Binary(op, v, r, rTemp))
              move(Reg(rTemp), r.index)
              pop(rTemp)

            case Ord.Gt  =>
              binaryOperation(Ord.Le, r.index, v)

            case Ord.Lt  =>
              binaryOperation(Ord.Ge, r.index, v)

            case Ord.Ge  =>
              binaryOperation(Ord.Lt, r.index, v)

            case Ord.Le  =>
              binaryOperation(Ord.Gt, r.index, v)

        else
          move(v, destReg)
          binaryOperation(op, destReg, r)

      case Instr.Binary(op, v1: Int32, v2: Int32, destReg) =>
        move(v1, destReg)
        binaryOperation(op, destReg, v2)

  /** System call */
  def syscall()(using pb: PatchableBuffer) =
    // 0F 34    SYSENTER
    pb.addByte(0x0F)
    pb.addByte(0x34)

  def int80()(using pb: PatchableBuffer) =
    pb.addBytes(0xcd.toByte, 0x80.toByte) // int    $0x80

  def push(reg: Int)(using pb: PatchableBuffer) =
    // 50+rd    PUSH r32
    pb.addByte((0x50 | reg).toByte)

  def pop(reg: Int)(using pb: PatchableBuffer) =
    // 58+ rd    POP r32
    pb.addByte((0x58 | reg).toByte)

  /** Add the value to the register */
  def add(reg: Int, v: Operand)(using pb: PatchableBuffer) =
    v match
      case Reg(rv) =>
        // 03 /r      ADD r32, r/m32
        pb.addByte(0x03)
        pb.addByte((0xC0 | (reg << 3) | rv).toByte)

      case Int32(v) =>
        // 81 /0 id   ADD r/m32, imm32
        pb.addByte(0x81.toByte)
        pb.addByte((0xC0 | reg).toByte)
        pb.addInt(v)

  /** Subtract the value from the register */
  def sub(reg: Int, v: Operand)(using pb: PatchableBuffer) =
    v match
      case Reg(rv) =>
        // 2B /r   SUB r32, r/m32
        pb.addByte(0x2B)
        pb.addByte((0xC0 | (reg << 3) | rv).toByte)

      case Int32(v) =>
        // 81 /5 id    SUB r/m32, imm32
        //
        // See A.4.2 Opcode Extension Tables in [1]
        //
        pb.addByte(0x81.toByte)
        pb.addByte((0xC0 | (5 << 3) | reg).toByte)
        pb.addInt(v)

  /** Multiply the register with the value */
  def mul(reg: Int, v: Operand)(using pb: PatchableBuffer) =
    v match
      case Reg(rv) =>
        // 0F AF /r     IMUL r32, r/m32
        pb.addByte(0x0F)
        pb.addByte(0xAF.toByte)
        pb.addByte((0xC0 | (reg << 3) | rv).toByte)

      case Int32(v) =>
        // 69 /r id     IMUL r32, r/m32, imm32
        pb.addByte(0x69)
        pb.addByte((0xC0 | (reg << 3) | reg).toByte)
        pb.addInt(v)

  /** Divide the register with the value */
  def div(reg: Int, v: Operand)(using pb: PatchableBuffer) =
    // TODO: reminder sign does not always agree with divident
    v match
      case Reg(rv) =>
        // F7 /7	IDIV r/m32
        // division uses dedicated registers EDX:EAX

        if reg == EAX && rv != EDX then // fast track
          push(EDX)
          move(Int32(0), EDX)
          pb.addByte(0xF7.toByte)
          pb.addByte((0xC0 | (7 << 3) | rv).toByte)
          pop(EDX)

        else if reg == rv then // it's implied that reg and rv are not EAX
          push(EAX)
          move(Reg(reg), EAX)  // divisor and divident are EAX
          push(EDX)
          move(Int32(0), EDX)
          pb.addByte(0xF7.toByte)
          pb.addByte((0xC0 | (7 << 3) | EAX).toByte)
          move(Reg(EAX), reg)
          pop(EDX)
          pop(EAX)

        else                        // reg and rv are not EAX, not equal
          val divisorReg =
            if rv != EAX && rv != EDX then
              rv.toByte
            else
              val freeReg = if reg == EBX then ECX else EBX
              push(freeReg)
              move(Reg(rv), freeReg)
              freeReg

          // put divident in EDX:EAX where EDX should be 0
          // reg can be EDX
          if reg != EAX then
            push(EAX)
            move(Reg(reg), EAX)

          if reg != EDX then
            push(EDX)
          // import to set high bits to 0
          move(Int32(0), EDX)

          pb.addByte(0xF7.toByte)
          pb.addByte((0xC0 | (7 << 3) | divisorReg).toByte)

          if reg != EDX then
            pop(EDX)

          if reg != EAX then
            move(Reg(EAX), reg)
            pop(EAX)

          if divisorReg != rv then
            pop(divisorReg)

      case _: Int32 =>
        // F7 /7	IDIV r/m32
        // division uses dedicated registers EDX:EAX
        if reg != EAX then
          push(EAX)
          move(Reg(reg), EAX)

        if reg != EDX then
          push(EDX)

        move(Int32(0), EDX)

        push(ECX) // to store divisor
        move(v, ECX)

        pb.addByte(0xF7.toByte)
        pb.addByte((0xC0 | (7 << 3) | ECX).toByte)

        pop(ECX)

        if reg != EDX then
          pop(EDX)

        if reg != EAX then
          move(Reg(EAX), reg)
          pop(EAX)


  /** Modulo the register with the value */
  def mod(reg: Int, v: Operand)(using pb: PatchableBuffer) =
    // https://www.felixcloutier.com/x86/idiv
    // TODO: reminder sign does not always agree with divident
    v match
      case Reg(rv) =>
        // F7 /7	IDIV r/m32
        // division uses dedicated registers EDX:EAX

        if reg == EAX && rv != EDX then // fast track
          push(EDX)
          move(Int32(0), EDX)
          pb.addByte(0xF7.toByte)
          pb.addByte((0xC0 | (7 << 3) | rv).toByte)
          move(Reg(EDX), reg)
          pop(EDX)

        else if reg == rv then       // it's implied that reg and rv are not EAX
          // TODO: const fold
          push(EAX)
          move(Reg(reg), EAX)        // divisor and divident are EAX
          if reg != EDX then
            push(EDX)

          // import to set high bits to 0
          move(Int32(0), EDX)

          pb.addByte(0xF7.toByte)
          pb.addByte((0xC0 | (7 << 3) | EAX).toByte)

          if reg != EDX then
            move(Reg(EDX), reg)
            pop(EDX)
          pop(EAX)

        else                        // reg and rv are not EAX, not equal
          val divisorReg =
            if rv != EAX && rv != EDX then
              rv.toByte
            else
              val freeReg = if reg == EBX then ECX else EBX
              push(freeReg)
              move(Reg(rv), freeReg)
              freeReg

          // put divident in EDX:EAX where EDX should be 0
          // reg can be EDX
          if reg != EAX then
            push(EAX)
            move(Reg(reg), EAX)

          if reg != EDX then
            push(EDX)
          // import to set high bits to 0
          move(Int32(0), EDX)

          pb.addByte(0xF7.toByte)
          pb.addByte((0xC0 | (7 << 3) | divisorReg).toByte)

          if reg != EDX then
            move(Reg(EDX), reg)
            pop(EDX)

          if reg != EAX then
            pop(EAX)

          if divisorReg != rv then
            pop(divisorReg)

      case _: Int32 =>
        // F7 /7	IDIV r/m32
        // division uses dedicated registers EDX:EAX
        if reg != EAX then
          push(EAX)
          move(Reg(reg), EAX)

        if reg != EDX then
          push(EDX)

        move(Int32(0), EDX)

        push(ECX) // to store divisor
        move(v, ECX)

        pb.addByte(0xF7.toByte)
        pb.addByte((0xC0 | (7 << 3) | ECX).toByte)

        pop(ECX)

        if reg != EDX then
          move(Reg(EDX), reg)
          pop(EDX)

        if reg != EAX then
          pop(EAX)

  /** Logical AND the value to the register */
  def and(reg: Int, v: Operand)(using pb: PatchableBuffer) =
    v match
      case Reg(rv) =>
        // 23 /r   AND r32, r/m32
        pb.addByte(0x23)
        pb.addByte((0xC0 | (reg << 3) | rv).toByte)

      case Int32(v) =>
        // 81 /4 id     AND r/m32, imm32
        pb.addByte(0x81.toByte)
        pb.addByte((0xC0 | (4 << 3) | reg).toByte)
        pb.addInt(v)

  /** Logical OR the value to the register */
  def or(reg: Int, v: Operand)(using pb: PatchableBuffer) =
    v match
      case Reg(rv) =>
        // 0B /r	OR r32, r/m32
        pb.addByte(0x0B)
        pb.addByte((0xC0 | (reg << 3) | rv).toByte)

      case Int32(v) =>
        // 81 /1 id	OR r/m32, imm32
        pb.addByte(0x81.toByte)
        pb.addByte((0xC0 | (1 << 3) | reg).toByte)
        pb.addInt(v)

  /** Logical NOR the value to the register */
  def nor(reg: Int, v: Operand)(using pb: PatchableBuffer) =
    v match
      case Reg(rv) if rv == reg =>
        not(reg)

      case _ =>
       or(reg, v)
       not(reg)

  /** Shift left logically */
  def sll(reg: Int, v: Operand)(using pb: PatchableBuffer) =
    v match
      case Reg(r2) =>
        // D3 /4	SAL r/m32, CL
        if r2 != ECX then
          push(ECX)
          move(Reg(r2), ECX)
          pb.addByte(0xD3.toByte)
          pb.addByte((0xC0 | (4 << 3) | reg).toByte)
          pop(ECX)
        else
          pb.addByte(0xD3.toByte)
          pb.addByte((0xC0 | (4 << 3) | reg).toByte)

      case Int32(v) =>
        // C1 /4 ib	SAL r/m32, imm8
        assert(v >= 0 && v < 256, "Shift too big, expect < 256, found = " + v)
        pb.addByte(0xC1.toByte)
        pb.addByte((0xC0 | (4 << 3) | reg).toByte)
        pb.addByte(v.toByte)

  /** Shift right logically */
  def srl(reg: Int, v: Operand)(using pb: PatchableBuffer) =
    v match
      case Reg(r2) =>
        // D3 /5	SHR r/m32, CL
        if reg != ECX then
          push(ECX)
          move(Reg(r2), ECX)
          pb.addByte(0xD3.toByte)
          pb.addByte((0xC0 | (5 << 3) | reg).toByte)
          pop(ECX)
        else
          pb.addByte(0xD3.toByte)
          pb.addByte((0xC0 | (5 << 3) | reg).toByte)

      case Int32(v) =>
        // C1 /5 ib	SHR r/m32, imm8
        assert(v >= 0 && v < 256, "Shift too big, expect < 256, found = " + v)
        pb.addByte(0xC1.toByte)
        pb.addByte((0xC0 | (5 << 3) | reg).toByte)
        pb.addByte(v.toByte)

  def not(reg: Int)(using pb: PatchableBuffer) =
    // F7 /2    NOT r/m32
    pb.addByte(0xF7.toByte)
    pb.addByte((0xC0 | (2 << 3) | reg).toByte)

  /** Logical XOR the value to the register */
  def xor(reg: Int, v: Operand)(using pb: PatchableBuffer) =
    v match
      case Reg(rv) =>
        // 33 /r	XOR r32, r/m32
        pb.addByte(0x33)
        pb.addByte((0xC0 | (reg << 3) | rv).toByte)

      case Int32(v) =>
        // 81 /6 id	XOR r/m32, imm32
        pb.addByte(0x81.toByte)
        pb.addByte((0xC0 | (6 << 3) | reg).toByte)
        pb.addInt(v)

  /** Move the value to the register */
  def move(v: Value, reg: Int)(using pb: PatchableBuffer) =
    v match
      case Reg(rv) =>
        // 8B /r       MOV r32, r/m32
        pb.addByte(0x8B.toByte)
        pb.addByte((0xC0 | (reg << 3) | rv).toByte)

      case Reg8(rv) =>
        // 8A /r       MOV r8, r/m8
        assert(rv < 4, "Only support ah, ch, dh, bh")

        pb.addByte(0x8B.toByte)
        pb.addByte((0xC0 | (reg << 3) | rv).toByte)

      case Int32(v) =>
        // B8+ rd id   MOV r32, imm32
        pb.addByte((0xB8 | reg).toByte)
        pb.addInt(v)

      case l: Label =>
        // B8+ rd id   MOV r32, imm32
        withPatch(l, 5): (bb, loc) =>
          bb.addByte((0xB8 | reg).toByte)
          bb.addInt(loc)

  def load(addr: Addr, destReg: Int, size: Size)(using pb: PatchableBuffer): Unit =
    if size == Size.B8 then
      assert(destReg < 4, "Only support ah, ch, dh, bh")

    addr match
      case Reg(r) =>
        // See Table 2-2. 32-Bit Addressing Forms with the ModR/M Byte in [1]

        size match
          case Size.B8  =>
            // 8A /r MOV r8, r/m8
            pb.addByte(0x8A.toByte)

          case Size.B32 =>
            // 8B /r MOV r32, r/m32
            pb.addByte(0x8B.toByte)

        if r == 4 then // ESP
          pb.addByte(((destReg << 3) | r).toByte)
          pb.addByte(0x24)
        else if r == 5 then // EBP
          pb.addByte((0x40 | (destReg << 3) | r).toByte)
          pb.addByte(0)
        else
          pb.addByte(((destReg << 3) | r).toByte)

      case Rel(r, 0) =>
        load(Reg(r), destReg, size)

      case Rel(r, offset) =>
        // See Table 2-2. 32-Bit Addressing Forms with the ModR/M Byte in [1]

        size match
          case Size.B8  =>
            // 8A /r MOV r8, r/m8
            pb.addByte(0x8A.toByte)

          case Size.B32 =>
            // 8B /r MOV r32, r/m32
            pb.addByte(0x8B.toByte)

        if r == 4 then // ESP
          pb.addByte((0x80 | (destReg << 3) | r).toByte)
          pb.addByte(0x24)
          pb.addInt(offset)
        else
          pb.addByte((0x80 | (destReg << 3) | r).toByte)
          pb.addInt(offset)

      case l: Label =>
        withPatch(l, 6): (bb, loc) =>
          // 8A /r  MOV r8, r/m8
          // 8B /r  MOV r32, r/m32

          size match
            case Size.B8  =>
              // 8A /r MOV r8, r/m8
              bb.addByte(0x8A.toByte)

            case Size.B32 =>
              // 8B /r MOV r32, r/m32
              bb.addByte(0x8B.toByte)

          bb.addByte(((destReg << 3) | 5).toByte)
          bb.addInt(loc)

  def store(v: Value, addr: Addr)(using pb: PatchableBuffer): Unit =
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
              withPatch(l, 6): (bb, loc) =>
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

          case Reg8(rv) =>
            // 88 /r     MOV r/m8, r8

            assert(rv < 4, "Only support ah, ch, dh, bh")

            if rd == 4 then // esp
              pb.addByte(0x88.toByte)
              pb.addByte(((rv << 3) | 4).toByte)
              pb.addByte(0x24)
            else if rd == 5 then // ebp
              pb.addByte(0x88.toByte)
              pb.addByte((0x40 | (rv << 3) | rd).toByte)
              pb.addByte(0)
            else
              pb.addByte(0x88.toByte)
              pb.addByte(((rv << 3) | rd).toByte)

      case Rel(rd, 0) =>
        store(v, Reg(rd))

      case Rel(rd, offset) =>
        v match
          case l: Label =>

            // C7 /0 id    MOV r/m32, imm32
            if rd == 4 then // ESP
              withPatch(l, 11): (bb, loc) =>
                bb.addByte(0xC7.toByte)
                bb.addByte(0x84.toByte) // disp32
                bb.addByte(0x24.toByte) // [EBP]
                bb.addInt(offset)
                bb.addInt(loc)
            else
              withPatch(l, 10): (bb, loc) =>
                bb.addByte(0xC7.toByte)
                bb.addByte((0x80 | rd).toByte)
                bb.addInt(offset)
                bb.addInt(loc)

          case Int32(v) =>
            // C7 /0 id    MOV r/m32, imm32
            if rd == 4 then // ESP
              pb.addByte(0xC7.toByte)
              pb.addByte(0x84.toByte) // disp32
              pb.addByte(0x24) // [EBP]
              pb.addInt(offset)
              pb.addInt(v)
            else
              pb.addByte(0xC7.toByte)
              pb.addByte((0x80 | rd).toByte)
              pb.addInt(offset)
              pb.addInt(v)

          case Reg(rv) =>
            // 89 /r     MOV r/m32, r32
            if rd == 4 then // ESP
              pb.addByte(0x89.toByte)
              pb.addByte((0x80 | (rv << 3) | 4).toByte) // disp32
              pb.addByte(0x24) // [EBP]
              pb.addInt(offset)
            else
              pb.addByte(0x89.toByte)
              pb.addByte((0x80 | (rv << 3) | rd).toByte)  // disp32
              pb.addInt(offset)

          case Reg8(rv) =>
            // 88 /r     MOV r/m8, r8

            assert(rv < 4, "Only support ah, ch, dh, bh")

            if rd == 4 then // ESP
              pb.addByte(0x88.toByte)
              pb.addByte((0x80 | (rv << 3) | 4).toByte) // disp32
              pb.addByte(0x24) // [EBP]
              pb.addInt(offset)
            else
              pb.addByte(0x88.toByte)
              pb.addByte((0x80 | (rv << 3) | rd).toByte)  // disp32
              pb.addInt(offset)

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
              bb.addByte(((rv << 3) | 5).toByte) // disp32
              bb.addInt(loc)

          case Reg8(rv) =>
            // 88 /r     MOV r/m8, r8
            withPatch(l, 6): (bb, loc) =>
              bb.addByte(0x88.toByte)
              bb.addByte(((rv << 3) | 5).toByte)  // disp32
              bb.addInt(loc)

  def jump(addr: Addr)(using pb: PatchableBuffer): Unit =
    addr match
      case Reg(r) =>
        // TODO: The instructin is invalid under 64-bit mode.
        // FF /4 JMP r/m32
        pb.addByte(0xFF.toByte)
        pb.addByte((0xC0 | (4 << 3) | r).toByte)

      case Rel(r, offset) =>
        if offset != 0 then
          // TODO: handl offset
          throw new Exception("Relative address in jump not supported")
        jump(Reg(r))

      case l: Label =>
        val currentAddr = pb.currentAddr()
        // E9 cd   JMP rel32
        withPatch(l, 5): (bb, loc) =>
          val relativeAddr = loc - currentAddr - 5
          bb.addByte(0xE9.toByte)
          bb.addInt(relativeAddr)

  def eql(reg: Int, v: Operand)(using pb: PatchableBuffer) =
    cmp(reg, v, 0x94.toByte)

  def gt(reg: Int, v: Operand)(using pb: PatchableBuffer) =
    cmp(reg, v, 0x9F.toByte)

  def ge(reg: Int, v: Operand)(using pb: PatchableBuffer) =
    cmp(reg, v, 0x9D.toByte)

  def lt(reg: Int, v: Operand)(using pb: PatchableBuffer) =
    cmp(reg, v, 0x9C.toByte)

  def le(reg: Int, v: Operand)(using pb: PatchableBuffer) =
    cmp(reg, v, 0x9E.toByte)

  def cmp(reg: Int, v: Operand, setcc: Byte)(using pb: PatchableBuffer) =
    v match
      case Reg(rv) =>
        // 3B /r    CMP r32, r/m32
        pb.addByte(0x3B)
        pb.addByte((0xC0 | (reg << 3) | rv).toByte)

      case Int32(v) =>
        // 81 /7 id   CMP r/m32, imm32
        pb.addByte(0x81.toByte)
        pb.addByte((0xC0 | (7 << 3) | reg).toByte)
        pb.addInt(v)
    end match

    // r/m8 has different encoding
    //
    // https://www.cs.uaf.edu/2002/fall/cs301/Encoding%20instructions.htm
    //
    // use EAX to do SETE for simplicity
    if reg != EAX then push(EAX)

    // 0F 94    SETE r/m8
    pb.addByte(0x0F)
    pb.addByte(setcc)
    pb.addByte((0xC0 | EAX).toByte)

    // Clear the high bytes of the register is important as SETE only set the low byte.
    // 81 /4 id    AND r/m32, imm32
    pb.addByte(0x81.toByte)
    pb.addByte((0xC0 | (4 << 3) | EAX).toByte)
    pb.addInt(0x000F)

    if reg != EAX then
      move(Reg(EAX), reg)
      pop(EAX)

  def jzero(reg: Int, label: Label)(using pb: PatchableBuffer) =
    // TODO: Handle the pattern [Eq(o1, o2, r), JZero(r, l)] to generate one fewer instruction.

    // 81 /7 id   CMP r/m32, imm32
    pb.addByte(0x81.toByte)
    pb.addByte((0xC0 | (7 << 3) | reg).toByte)
    pb.addInt(0)

    val offset = pb.currentAddr()
    withPatch(label, 6): (bb, loc) =>
      // 0F 84 cd   JZ rel32  (Jump near if 0 (ZF=1))
      val relativeAddr = loc - offset - 6
      bb.addByte(0x0F)
      bb.addByte(0x84.toByte)
      bb.addInt(relativeAddr)

  /** Perform binary operation on the register with the given operand */
  def binaryOperation(op: BiOp, reg: Int, v: Operand)(using pb: PatchableBuffer) =
    op match
      case Arith.Add => add(reg, v)
      case Arith.Sub => sub(reg, v)
      case Arith.Mul => mul(reg, v)
      case Arith.Div => div(reg, v)
      case Arith.Mod => mod(reg, v)

      case Bit.And => and(reg, v)
      case Bit.Or  => or(reg, v)
      case Bit.Nor => nor(reg, v)
      case Bit.Xor => xor(reg, v)
      case Bit.Srl => srl(reg, v)
      case Bit.Sll => sll(reg, v)

      case Ord.Eq  => eql(reg, v)
      case Ord.Gt  => gt(reg, v)
      case Ord.Lt  => lt(reg, v)
      case Ord.Ge  => ge(reg, v)
      case Ord.Le  => le(reg, v)

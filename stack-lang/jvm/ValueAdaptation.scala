package jvm

import jvm.ClassFile.CodeWriter
import jvm.JVMTypes.*
import jvm.JVMTypes.JType.*

/** Emits conversions between already-decided JVM representations.
  *
  * Boxing is a representation concern, not an AST traversal concern. Keeping
  * it here makes that distinction explicit and gives future lowering passes a
  * single conversion contract to target.
  */
object ValueAdaptation:
  enum Conversion:
    case Identity, Drop, DropWide, UnitValue
    case Box(primitive: JType)
    case Unbox(primitive: JType)
    case CheckCast(internalName: String)
    // Between an int-category value and a Jo `Byte`'s signed 8-bit pattern.
    case NarrowToByte, WidenFromByte

  import Conversion.*

  /** Decide a conversion without emitting bytecode. Lowering and tests can
    * reason about representation policy independently of the class writer.
    */
  def conversion(actual: JType, expected: JType): Conversion =
    if actual == expected then Identity
    else
      (actual, expected) match
        case (a, V) if a != V => if isCategory2(a) then DropWide else Drop
        case (V, Ref(_)) => UnitValue
        case (V, _) => Identity
        case (a, Ref(ObjectDesc)) if isPrimitive(a) => Box(a)
        case (Ref(d), b) if isPrimitive(b) && d == ObjectDesc => Unbox(b)
        // A Jo `Byte` is unsigned but is stored in a signed JVM `byte`, so
        // moving between the two representations is a real conversion, not
        // the identity the other int-category pairs enjoy.
        case (a, B) if isIntCat(a) => NarrowToByte
        case (B, b) if isIntCat(b) => WidenFromByte
        case (a, b) if isIntCat(a) && isIntCat(b) => Identity
        case (Ref(_), Ref(ObjectDesc)) => Identity
        case (Ref(_), Ref(d)) => CheckCast(internalNameOf(Ref(d)))
        case _ => throw new Exception("JVM backend: no conversion from " + actual + " to " + expected)

  def emit(actual: JType, expected: JType, cw: CodeWriter): Unit =
    emit(conversion(actual, expected), cw)

  def emit(conversion: Conversion, cw: CodeWriter): Unit =
    conversion match
      case Identity => ()
      case Drop => cw.pop()
      case DropWide => cw.pop2()
      case UnitValue => cw.aconstNull()
      case Box(primitive) => box(primitive, cw)
      case Unbox(primitive) => unbox(primitive, cw)
      case CheckCast(internalName) => cw.checkcast(internalName)
      case NarrowToByte => cw.i2b()
      case WidenFromByte => cw.iconst(0xFF); cw.iand()

  // A Jo `Char` boxes to `java.lang.Integer` rather than to
  // `java.lang.Character`, which holds 16 bits and would truncate every
  // supplementary code point (emoji, for one). Sharing `Integer` with `Int`
  // costs nothing observable: the only way a program can type-test a
  // primitive is through a union, and the typer rejects a union containing
  // more than one numeric type ("Union type cannot contain multiple
  // numeric/boolean types"), so no legal program can ask whether a boxed
  // value is a `Char` rather than an `Int`.
  //
  // A `Byte` needs no such compromise: its 8-bit pattern round-trips
  // through `java.lang.Byte` exactly.
  private def box(t: JType, cw: CodeWriter): Unit =
    val (owner, desc) = t match
      case I => ("java/lang/Integer", "(I)Ljava/lang/Integer;")
      case Z => ("java/lang/Boolean", "(Z)Ljava/lang/Boolean;")
      case B => ("java/lang/Byte", "(B)Ljava/lang/Byte;")
      case C => ("java/lang/Integer", "(I)Ljava/lang/Integer;")
      case D => ("java/lang/Double", "(D)Ljava/lang/Double;")
      case J => ("java/lang/Long", "(J)Ljava/lang/Long;")
      case _ => throw new Exception("cannot box " + t)
    cw.invokestatic(owner, "valueOf", desc)

  private def unbox(t: JType, cw: CodeWriter): Unit =
    val (owner, meth, desc) = t match
      case I => ("java/lang/Integer", "intValue", "()I")
      case Z => ("java/lang/Boolean", "booleanValue", "()Z")
      case B => ("java/lang/Byte", "byteValue", "()B")
      case C => ("java/lang/Integer", "intValue", "()I")
      case D => ("java/lang/Double", "doubleValue", "()D")
      case J => ("java/lang/Long", "longValue", "()J")
      case _ => throw new Exception("cannot unbox " + t)
    cw.checkcast(owner)
    cw.invokevirtual(owner, meth, desc)

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
    case Identity, Drop, UnitValue
    case Box(primitive: JType)
    case Unbox(primitive: JType)
    case CheckCast(internalName: String)

  import Conversion.*

  /** Decide a conversion without emitting bytecode. Lowering and tests can
    * reason about representation policy independently of the class writer.
    */
  def conversion(actual: JType, expected: JType): Conversion =
    if actual == expected then Identity
    else
      (actual, expected) match
        case (_, V) if actual != V => Drop
        case (V, Ref(_)) => UnitValue
        case (V, _) => Identity
        case (a, Ref(ObjectDesc)) if isPrimitive(a) => Box(a)
        case (Ref(d), b) if isPrimitive(b) && d == ObjectDesc => Unbox(b)
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
      case UnitValue => cw.aconstNull()
      case Box(primitive) => box(primitive, cw)
      case Unbox(primitive) => unbox(primitive, cw)
      case CheckCast(internalName) => cw.checkcast(internalName)

  // A Jo `Char` boxes to `java.lang.Integer`, not `java.lang.Character`:
  // `Character` holds 16 bits, which silently truncates every supplementary
  // code point (emoji, for one), while a Jo `Char` is a full Unicode code
  // point. Sharing `Integer` with `Int` costs nothing observable, because
  // the only way a program can type-test a primitive is through a union,
  // and the typer rejects a union containing more than one numeric type
  // ("Union type cannot contain multiple numeric/boolean types") — so no
  // legal program can ask whether a boxed value is a `Char` rather than an
  // `Int`.
  private def box(t: JType, cw: CodeWriter): Unit =
    val (owner, desc) = t match
      case I => ("java/lang/Integer", "(I)Ljava/lang/Integer;")
      case Z => ("java/lang/Boolean", "(Z)Ljava/lang/Boolean;")
      case B => ("java/lang/Byte", "(B)Ljava/lang/Byte;")
      case C => ("java/lang/Integer", "(I)Ljava/lang/Integer;")
      case F => ("java/lang/Float", "(F)Ljava/lang/Float;")
      case J => ("java/lang/Long", "(J)Ljava/lang/Long;")
      case _ => throw new Exception("cannot box " + t)
    cw.invokestatic(owner, "valueOf", desc)

  private def unbox(t: JType, cw: CodeWriter): Unit =
    val (owner, meth, desc) = t match
      case I => ("java/lang/Integer", "intValue", "()I")
      case Z => ("java/lang/Boolean", "booleanValue", "()Z")
      case B => ("java/lang/Byte", "byteValue", "()B")
      case C => ("java/lang/Integer", "intValue", "()I")
      case F => ("java/lang/Float", "floatValue", "()F")
      case J => ("java/lang/Long", "longValue", "()J")
      case _ => throw new Exception("cannot unbox " + t)
    cw.checkcast(owner)
    cw.invokevirtual(owner, meth, desc)

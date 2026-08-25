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
object JVMAdaptation:
  def emit(actual: JType, expected: JType, cw: CodeWriter): Unit =
    if actual == expected then ()
    else
      (actual, expected) match
        case (_, V) => if actual != V then cw.pop()
        case (V, Ref(_)) => cw.aconstNull()
        case (V, _) => ()
        case (a, Ref(ObjectDesc)) if isIntCat(a) => box(a, cw)
        case (Ref(d), b) if isIntCat(b) && d == ObjectDesc => unbox(b, cw)
        case (a, b) if isIntCat(a) && isIntCat(b) => ()
        case (Ref(_), Ref(ObjectDesc)) => ()
        case (Ref(_), Ref(d)) => cw.checkcast(internalNameOf(Ref(d)))
        case _ => throw new Exception("JVM backend prototype: no conversion from " + actual + " to " + expected)

  private def box(t: JType, cw: CodeWriter): Unit =
    val (owner, desc) = t match
      case I => ("java/lang/Integer", "(I)Ljava/lang/Integer;")
      case Z => ("java/lang/Boolean", "(Z)Ljava/lang/Boolean;")
      case B => ("java/lang/Byte", "(B)Ljava/lang/Byte;")
      case C => ("java/lang/Character", "(C)Ljava/lang/Character;")
      case F => ("java/lang/Float", "(F)Ljava/lang/Float;")
      case J => ("java/lang/Long", "(J)Ljava/lang/Long;")
      case _ => throw new Exception("cannot box " + t)
    cw.invokestatic(owner, "valueOf", desc)

  private def unbox(t: JType, cw: CodeWriter): Unit =
    val (owner, meth, desc) = t match
      case I => ("java/lang/Integer", "intValue", "()I")
      case Z => ("java/lang/Boolean", "booleanValue", "()Z")
      case B => ("java/lang/Byte", "byteValue", "()B")
      case C => ("java/lang/Character", "charValue", "()C")
      case F => ("java/lang/Float", "floatValue", "()F")
      case J => ("java/lang/Long", "longValue", "()J")
      case _ => throw new Exception("cannot unbox " + t)
    cw.checkcast(owner)
    cw.invokevirtual(owner, meth, desc)


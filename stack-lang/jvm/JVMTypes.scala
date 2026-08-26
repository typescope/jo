package jvm

import sast.*
import sast.Symbols.*
import sast.Types.*

import scala.collection.mutable
import jvm.JVMAdaptation.Conversion

/** The JVM-level value representations shared by lowering and emission.
  *
  * This deliberately contains no SAST traversal or reachability policy. A
  * backend phase may compare representations without depending on
  * [[JVMCodeGen]], while the code generator remains responsible for mapping
  * Jo types and assigning internal names to reachable classes.
  */
object JVMTypes:
  /** A name-independent representation key. In particular, comparing two
    * keys never makes a class reachable and never depends on generated-name
    * allocation order.
    */
  enum Representation:
    case Int, Bool, Byte, Char, Float, Long, Void, Object, String
    case Class(symbol: Symbol)

  enum JType:
    case I, Z, B, C, F, J, V
    case Ref(desc: String)

  import JType.*

  val ObjectClass = "java/lang/Object"
  val ObjectDesc = "Ljava/lang/Object;"
  val StringClass = "java/lang/String"
  val StringDesc = "Ljava/lang/String;"
  val ThrowableClass = "java/lang/Throwable"
  val ObjectArrayDesc = "[Ljava/lang/Object;"

  def classOrInterfaceSymbol(tpe: Type)(using Definitions): Option[Symbol] =
    tpe.approx.typeSymbolOpt.filter(_.isOneOf(Flags.Class | Flags.Interface))

  def representationOf(tp: Type)(using defn: Definitions): Representation =
    import Representation.*
    if tp.isVoidType then Void
    else
      tp.approx match
        case StaticRef(sym) if sym == defn.Int_type => Int
        case StaticRef(sym) if sym == defn.Bool_type => Bool
        case StaticRef(sym) if sym == defn.Byte_type => Byte
        case StaticRef(sym) if sym == defn.Char_type => Char
        case StaticRef(sym) if sym == defn.Float_type => Float
        case StaticRef(sym) if sym == defn.Long_type => Long
        case StaticRef(sym) if sym == defn.String_type => String
        case _ =>
          classOrInterfaceSymbol(tp) match
            // Arrays are intrinsified as Object[] at their operations, but
            // ordinary Jo-level Array values retain the generic Object
            // representation used by the previous mapper.
            case Some(sym) if sym == defn.Array_class => Object
            case Some(sym) => Class(sym)
            case None => Object

  def lower(rep: Representation, className: Symbol => String): JType =
    import Representation.*
    rep match
      case Int => I
      case Bool => Z
      case Byte => B
      case Char => C
      case Float => F
      case Long => J
      case Void => V
      case Object => Ref(ObjectDesc)
      case String => Ref(StringDesc)
      case Class(sym) => Ref("L" + className(sym) + ";")

  def typeOf(tpe: Type)(using context: JVMContext, defn: Definitions): JType =
    lower(representationOf(tpe), context.requireClass)

  def descriptorOf(tpe: Type)(using context: JVMContext, defn: Definitions): String =
    descOf(typeOf(tpe))

  def methodDescriptor(
    parameterTypes: List[Type], resultType: Type
  )(using context: JVMContext, defn: Definitions): String =
    "(" + parameterTypes.map(descriptorOf).mkString + ")" + descriptorOf(resultType)

  /** Classifies conversions that lowering can make explicit without assigning
    * JVM class names.
    */
  def loweringConversion(actual: Type, expected: Type)(using Definitions): Conversion =
    val actualRepresentation = representationOf(actual)
    val expectedRepresentation = representationOf(expected)
    (actualRepresentation, expectedRepresentation) match
      case (Representation.Class(actualClass), Representation.Class(expectedClass))
          if actualClass == expectedClass => Conversion.Identity
      case (Representation.Object | Representation.String | Representation.Class(_), Representation.Class(_)) =>
        Conversion.CheckCast("")
      case _ =>
        JVMAdaptation.conversion(
          lowerWithoutClassNames(actualRepresentation),
          lowerWithoutClassNames(expectedRepresentation)
        )

  private def lowerWithoutClassNames(representation: Representation): JType =
    representation match
      case Representation.Void => V
      case Representation.Int => I
      case Representation.Bool => Z
      case Representation.Byte => B
      case Representation.Char => C
      case Representation.Float => F
      case Representation.Long => J
      case Representation.String => Ref(StringDesc)
      case Representation.Object | Representation.Class(_) => Ref(ObjectDesc)

  def isIntCat(t: JType): Boolean = t match
    case I | Z | B | C => true
    case _ => false

  def isPrimitive(t: JType): Boolean = isIntCat(t) || t == F || t == J

  def isRef(t: JType): Boolean = t.isInstanceOf[Ref]

  def descOf(t: JType): String = t match
    case I => "I"
    case Z => "Z"
    case B => "B"
    case C => "C"
    case F => "F"
    case J => "J"
    case V => "V"
    case Ref(d) => d

  def internalNameOf(t: JType): String = t match
    case Ref(d) if d.startsWith("L") && d.endsWith(";") => d.substring(1, d.length - 1)
    case Ref(d) if d.startsWith("[") => d
    case _ => ObjectClass

  def parseFieldDesc(desc: String): JType = charToJType(desc, 0)._1

  def parseMethodParams(desc: String): List[JType] =
    val end = desc.indexOf(')')
    val params = new mutable.ArrayBuffer[JType]()
    var i = 1
    while i < end do
      val (t, next) = charToJType(desc, i)
      params += t
      i = next
    params.toList

  def parseMethodReturn(desc: String): JType =
    charToJType(desc, desc.indexOf(')') + 1)._1

  private def charToJType(desc: String, at: Int): (JType, Int) =
    desc(at) match
      case 'I' => (I, at + 1)
      case 'Z' => (Z, at + 1)
      case 'B' => (B, at + 1)
      case 'C' => (C, at + 1)
      case 'F' => (F, at + 1)
      case 'J' => (J, at + 1)
      case 'V' => (V, at + 1)
      case 'L' =>
        val semi = desc.indexOf(';', at)
        (Ref(desc.substring(at, semi + 1)), semi + 1)
      case '[' =>
        var j = at
        while desc(j) == '[' do j += 1
        val (_, next) = charToJType(desc, j)
        (Ref(ObjectDesc), next)
      case c => throw new Exception("Unexpected descriptor char '" + c + "' in " + desc)

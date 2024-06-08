import Symbols.Symbol

import scala.collection.immutable.ListMap

/** The type system of Stk.
  *
  * Stk has a structural type system, which means that the names of types
  * usually do not matter. Two types are equivalent if they refer to types that
  * are structurally the same.
  */
object Types:
  sealed trait Type:
    def isError: Boolean = this == Type.Error

    def isVoid: Boolean = this == Type.Void

    def isTypeRef: Boolean = this.isInstanceOf[Type.TypeRef]

    def isRecordType: Boolean =
      this match
        case _: Type.Record => true
        case Type.TypeRef(sym) => sym.info.isRecordType
        case _ => false

    def isValueType: Boolean =
      this match
        case Type.Int | Type.Bool | Type.Error => true
        case _: Type.TypeRef | _: Type.Record => true
        case _ => false

    def resultType: Type =
      this match
        case Type.Proc(_, _, resType) => resType
        case _ => throw new Exception("Not a proc type: " + this)

    def hasField(name: String): Boolean =
      this match
        case Type.Record(fields) => fields.contains(name)
        case Type.TypeRef(sym) => sym.info.hasField(name)
        case _ => false

    def fieldType(name: String): Type =
      this match
        case Type.Record(fields) =>
          fields.get(name) match
            case Some(tp) => tp
            case None =>
              throw new Exception("No such field " + name + " in " + this)

        case Type.TypeRef(sym) => sym.info.fieldType(name)

        case _ => throw new Exception("Not a record type: " + this)

    def dealias: Type =
      this match
        case Type.TypeRef(sym) => sym.info.dealias
        case _ => this

  object Type:
    case object Int extends Type
    case object Bool extends Type

    case object Void extends Type

    case object Error extends Type

    case class TypeRef(symbol: Symbol) extends Type:
      override def toString() = symbol.name

    case class Record(fields: ListMap[String, Type]) extends Type:
      override def toString =
        "[" + fields.map(_ + ": " + _).mkString(", ") + "]"

    case class Proc(
      names: List[String], paramTypes: List[Type], resType: Type)
    extends Type:
      val paramCount = paramTypes.size
      val resCount = if resType.isValueType then 1 else 0

  // TODO: handle non-termination
  def matches(tp1: Type, tp2: Type): Boolean =
    tp1.isError
    || tp2.isError
    || tp1 == tp2
    || tp1.isTypeRef && matches(tp1.dealias, tp2)
    || tp2.isTypeRef && matches(tp1, tp2.dealias)

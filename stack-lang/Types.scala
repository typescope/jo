import Symbols.Symbol

import scala.collection.immutable.ListMap

/** The type system of Stk.
  *
  * Stk has a structural type system, which means that the names of types
  * usually do not matter. Two types are equivalent if they refer to types that
  * are structurally the same.
  */
object Types:
  sealed abstract class Type:
    def isError: Boolean = this == Type.Error

    def isVoid: Boolean = this == Type.Void

    def isBottom: Boolean = this == Type.Bottom

    def isTypeRef: Boolean = this.isInstanceOf[Type.TypeRef]

    def isRecordType: Boolean =
      this match
        case _: Type.Record => true
        case Type.TypeRef(sym) => sym.info.isRecordType
        case _ => false

    def isUnionType: Boolean =
      this match
        case _: Type.Union => true
        case Type.TypeRef(sym) => sym.info.isUnionType
        case _ => false

    def isValueType: Boolean =
      this match
        case Type.Void | _: Type.Proc => false
        case _ => true

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

    def hasTag(tag: String): Boolean =
      this match
        case Type.Union(branches) => branches.contains(tag)
        case Type.TypeRef(sym) => sym.info.hasTag(tag)
        case _ => false

    def tagType(tag: String): Type =
      this match
        case Type.Union(branches) =>
          branches.get(tag) match
            case Some(tp) => tp
            case None =>
              throw new Exception("No such tag " + tag + " in " + this)

        case Type.TypeRef(sym) => sym.info.tagType(tag)

        case _ => throw new Exception("Not a union type: " + this)

    def tagIndex(tag: String): Int =
      this match
        case Type.Union(branches) =>
          branches.keys.toList.indexOf(tag) match
            case -1 =>
              throw new Exception("No such tag " + tag + " in " + this)
            case n =>
              n

        case Type.TypeRef(sym) => sym.info.tagIndex(tag)

        case _ => throw new Exception("Not a union type: " + this)

    def dealias: Type =
      this match
        case Type.TypeRef(sym) => sym.info.dealias
        case _ => this

    def asRecordType: Type.Record = this.dealias.asInstanceOf[Type.Record]

    def asUnionType: Type.Union = this.dealias.asInstanceOf[Type.Union]

  object Type:
    case object Int extends Type

    case object Bool extends Type

    case object Void extends Type

    case object Bottom extends Type

    case object Error extends Type

    case class TypeRef(symbol: Symbol) extends Type:
      override def toString() = symbol.name

    case class Record(fields: ListMap[String, Type]) extends Type:
      override def toString =
        "[" + fields.map(_ + ": " + _).mkString(", ") + "]"

    case class Union(branches: ListMap[String, Type]) extends Type:
      override def toString =
        "<" + branches.map(_ + " " + _).mkString(", ") + ">"

    case class Proc(
      names: List[String], paramTypes: List[Type], resType: Type)
    extends Type:
      val paramCount = paramTypes.size
      val resCount = if resType.isValueType then 1 else 0

  /** Whether `tp1` conforms to `tp2`.
    *
    * TODO: handle non-termination with recursive type
    */
  def conforms(tp1: Type, tp2: Type): Boolean =
    tp1.isError
    || tp2.isError
    || tp1.isBottom
    || tp1 == tp2
    || tp1.isTypeRef && conforms(tp1.dealias, tp2)
    || tp2.isTypeRef && conforms(tp1, tp2.dealias)


  /** The common result type of two different types.
    *
    * This method is used to compute the result type of if- and match-
    * expressions.
    *
    * The logic is different from computing join in the subtype lattice:
    *
    * - Type.Error always dominates
    * - Type.Void dominates Type.Bottom
    */
  def commonResultType(tp1: Type, tp2: Type): Option[Type] =
    if tp1.isError || tp2.isError then Some(Type.Error)
    else if tp1.isVoid && tp2.isBottom then Some(Type.Void)
    else if tp1.isBottom && tp2.isVoid then Some(Type.Void)
    else if conforms(tp1, tp2) then Some(tp2)
    else if conforms(tp2, tp1) then Some(tp1)
    else None

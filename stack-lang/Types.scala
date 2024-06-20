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

    def isDelayed: Boolean = this.isInstanceOf[Type.Delayed]

    def isRecordType: Boolean =
      this.dealias.isInstanceOf[Type.Record]

    def isUnionType: Boolean =
      this.dealias.isInstanceOf[Type.Union]

    def isValueType: Boolean =
      this.dealias match
        case Type.Void | _: Type.Proc => false
        case _ => true

    def isProcType: Boolean = this.underlying.isInstanceOf[Type.Proc]

    def asRecordType: Type.Record = this.dealias.asInstanceOf[Type.Record]

    def asUnionType: Type.Union = this.dealias.asInstanceOf[Type.Union]

    def asProcType: Type.Proc = this.dealias.asInstanceOf[Type.Proc]

    def resultType: Type =
      this.underlying match
        case Type.Proc(_, _, resType) => resType
        case _ => throw new Exception("Not a proc type: " + this)

    def underlying: Type =
      this match
        case delayed: Type.Delayed => delayed.take
        case _ => this

    def hasField(name: String): Boolean =
      val Type.Record(fields) = this.asRecordType
      fields.contains(name)

    def fieldType(name: String): Type =
      val Type.Record(fields) = this.asRecordType
      fields.get(name) match
        case Some(tp) => tp
        case None =>
          throw new Exception("No such field " + name + " in " + this)

    def hasTag(tag: String): Boolean =
      this.dealias match
        case Type.Union(branches) => branches.contains(tag)
        case Type.TypeRef(sym) => sym.info.hasTag(tag)
        case _ => false

    def tagType(tag: String): Type =
      val Type.Union(branches) = this.asUnionType
      branches.get(tag) match
        case Some(tp) => tp
        case None =>
          throw new Exception("No such tag " + tag + " in " + this)

    def tagIndex(tag: String): Int =
      val Type.Union(branches) = this.asUnionType
      branches.keys.toList.indexOf(tag) match
        case -1 =>
          throw new Exception("No such tag " + tag + " in " + this)
        case n =>
          n

    def tags: List[String] =
      val Type.Union(branches) = this.asUnionType
      branches.keys.toList

    def dealias: Type =
      this.underlying match
        case Type.TypeRef(sym) => sym.info.dealias
        case tp => tp

  object Type:
    case object Int extends Type

    case object Bool extends Type

    case object Void extends Type

    case object Bottom extends Type

    case object Error extends Type

    case class TypeRef(symbol: Symbol) extends Type:
      override def toString() = symbol.name

    /** A record type --- named tuples
      *
      * Warning: flattening of nested tuples is dangerous with subtyping
      * of records.
      */
    case class Record(fields: ListMap[String, Type]) extends Type:
      val fieldNames: List[String] = fields.keys.toList

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

    /** Delayed type for symbols to enable type inference and recursive types */
    case class Delayed() extends Type:
      // TODO: change equals and hash
      private var _underlying: Type = null

      def complete(tpe: Type): Unit =
        assert(_underlying == null, "Double completing: " + _underlying)
        _underlying = tpe

      def isComplete: Boolean = _underlying != null

      def take: Type =
        assert(_underlying != null)
        _underlying

      override def toString =
        "Delayed(" + _underlying + ")"

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
    || tp1.isDelayed && conforms(tp1.underlying, tp2)
    || tp2.isDelayed && conforms(tp1, tp2.underlying)
    || tp1.isRecordType && tp2.isRecordType
       && conformsRecordType(tp1.asRecordType, tp2.asRecordType)

  def conformsRecordType(tp1: Type.Record, tp2: Type.Record): Boolean =
    val names1 = tp1.fieldNames
    val names2 = tp2.fieldNames
    names1.size <= names2.size && names1.zip(names2).forall: (a, b) =>
      a == b && conforms(tp1.fieldType(a), tp2.fieldType(b))

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

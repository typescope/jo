import Symbols.Symbol

import scala.collection.immutable.ListMap
import scala.collection.mutable
import scala.reflect.ClassTag

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

    def is[T <: Type : ClassTag]: Boolean =
      this match
        case tp: T => true
        case _     => false

    def as[T <: Type]: T = this.asInstanceOf[T]

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

    /** Transitively eliminate type aliases and delayed types */
    def dealias: Type =
      // detect cycles in symbol definitions, e.g., type A = A
      val encountered = new mutable.ArrayBuffer[Symbol]
      def recur(tp: Type): Type =
        tp.underlying match
          case tref @ Type.TypeRef(sym) =>
            if encountered.contains(sym) then
              tref
            else
              encountered += sym
              recur(sym.info)
            end if

          case tp => tp
      end recur
      recur(this)

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
    checkConforms(tp1,tp2)(using Map.empty)

  /** The assumption that a type A is a subtype of B
    *
    * In essence, the subtyping follows Amber's rule for recursive types.
    *
    *                    Γ, α <: β ⊢ S <: T
    *                   ---------------------
    *                      μα.S  <: μβ.T
    *
    * The rule is sound and sufficiently expressive in pratical usage. For
    * example, it rules out that μα.α → ⊥ is a subtype of μβ.β → ⊤.
    *
    * However, it cannot prove that μα.α → Int is a subtype of μβ.β → Int. The
    * original paper includes a rule that takes equality of recursive types
    * into consideration:
    *
    *                         A = B
    *                    ----------------
    *                       Γ ⊢ A <: B
    *
    * The equality above is defined not syntatically but semantically. Such
    * equality is only theoretically motivated, thus it is not implemented in
    * the current language.
    *
    * - Paper: Subtyping recursive types, Roberto M. Amadio, Luca Cardelli, 1993
    * - Link: https://dl.acm.org/doi/10.1145/155183.155231
    */
  private type Assumptions = Map[Symbol, List[Symbol]]

  private def checkConforms(tp1: Type, tp2: Type)(using Assumptions): Boolean =
    tp1.isError
    || tp2.isError
    || tp1.isBottom
    || tp1 == tp2
    || tp1.is[Type.TypeRef] && tp2.is[Type.TypeRef]
       && checkConformsTypeRef(tp1.as[Type.TypeRef], tp2.as[Type.TypeRef])
    || tp1.is[Type.TypeRef] && checkConforms(tp1.dealias, tp2)
    || tp2.is[Type.TypeRef] && checkConforms(tp1, tp2.dealias)
    || tp1.is[Type.Delayed] && checkConforms(tp1.underlying, tp2)
    || tp2.is[Type.Delayed] && checkConforms(tp1, tp2.underlying)
    || tp1.is[Type.Record] && tp2.is[Type.Record]
       && checkConformsRecordType(tp1.as[Type.Record], tp2.as[Type.Record])

  private def checkConformsTypeRef(tp1: Type.TypeRef, tp2: Type.TypeRef)(using ass: Assumptions): Boolean =
    ass.get(tp1.symbol) match
      case Some(syms) =>
        if syms.contains(tp2.symbol) then
          true
        else
          val ass2 = ass.updated(tp1.symbol, tp2.symbol :: syms)
          checkConforms(tp1.symbol.info, tp2.symbol.info)(using ass2)

      case None =>
        val ass2 = ass.updated(tp1.symbol, tp2.symbol :: Nil)
        checkConforms(tp1.symbol.info, tp2.symbol.info)(using ass2)

  private def checkConformsRecordType(tp1: Type.Record, tp2: Type.Record)(using Assumptions): Boolean =
    val names1 = tp1.fieldNames
    val names2 = tp2.fieldNames
    names1.size <= names2.size && names1.zip(names2).forall: (a, b) =>
      a == b && checkConforms(tp1.fieldType(a), tp2.fieldType(b))

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

import Symbols.Symbol

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
    def isError: Boolean = this == ErrorType

    def isVoid: Boolean = this == VoidType

    def isAny: Boolean = this == AnyType

    def isBottom: Boolean = this == BottomType

    def isRecordType: Boolean =
      TypeOps.approx(this, isUp = true).isInstanceOf[RecordType]

    def isUnionType: Boolean =
      TypeOps.approx(this, isUp = true).isInstanceOf[UnionType]

    def isTypeLambda: Boolean =
      TypeOps.approx(this, isUp = true).isInstanceOf[TypeLambda]

    def isProcType: Boolean =
      TypeOps.approx(this, isUp = true).isInstanceOf[ProcType]

    def isFunctionType: Boolean =
      TypeOps.approx(this, isUp = true).isInstanceOf[FunctionType]

    def isPolyType: Boolean =
      TypeOps.approx(this, isUp = true).isInstanceOf[PolyType]

    def isValueType: Boolean =
      TypeOps.approx(this, isUp = true)  match
        case VoidType | _: ProcType | _: TypeLambda | _: PolyType => false
        case _ => true

    def asRecordType: RecordType = TypeOps.approx(this, isUp = true).asInstanceOf[RecordType]

    def asUnionType: UnionType = TypeOps.approx(this, isUp = true).asInstanceOf[UnionType]

    def asTypeLambda: TypeLambda = TypeOps.approx(this, isUp = true).asInstanceOf[TypeLambda]

    def asProcType: ProcType = TypeOps.approx(this, isUp = true).asInstanceOf[ProcType]

    def asFunctionType: FunctionType = TypeOps.approx(this, isUp = true).asInstanceOf[FunctionType]

    def asPolyType: PolyType = TypeOps.approx(this, isUp = true).asInstanceOf[PolyType]

    def is[T <: Type : ClassTag]: Boolean =
      this match
        case tp: T => true
        case _     => false

    def as[T <: Type]: T = this.asInstanceOf[T]

    /** A grounded type cannot be simplied further at the top-level
      *
      * The following types are grounded:
      *
      * - primitive types
      * - procedure types
      * - record types
      * - union types
      */
    def isGrounded: Boolean =
      this match
        case AnyType | BottomType | IntType | BoolType | ErrorType | VoidType => true
        case _: PolyType | _: ProcType | _: FunctionType | _: RecordType | _: UnionType | _: TypeBound => true
        case _: TypeLambda | _: TypeParamRef => true
        case _: TypeRef | _: AppliedType => false
        case _: DelayedType => false

    /** Erase a poly type by replacing type parameters with Any */
    def erasePolyType: Type =
      // implementation assumption: no nested poly types
      TypeOps.dealias(this) match
        case PolyType(_, bounds, resType) =>
          // cannot subst with bounds as they might be recursive
          // TODO: do it in a principled way
          TypeOps.substTypeParams(resType, bounds.map(_ => AnyType))

        case tp => tp

    /** Strip top-level delayed type */
    def stripDelayed: Type =
      this match
        case delayed: DelayedType => delayed.underlying
        case tp => tp

    def show: String =
      this match
        case IntType | BoolType | VoidType | AnyType | BottomType | ErrorType =>
          this.toString

        case TypeRef(sym) =>
          sym.name

        case RecordType(fields) =>
          fields.map(_ + ": " + _.show).mkString("{", ", ", "}")

        case UnionType(branches) =>
          def concat(tps: List[Type]) = tps.map(_.show).mkString(" * ")
          branches.map(_ + " " + concat(_)).mkString("<", ", ", ">")

        case delay: DelayedType =>
          delay.underlying.show

        case AppliedType(tctor, targs) =>
          tctor.show + targs.map(_.show).mkString("[", ", ", "]")

        case TypeLambda(names, bounds, body) =>
          val tparams = names.zip(bounds).map(_ + " <: " + _.show).mkString("[", ", ", "]")
          tparams + " => " + body.show

        case PolyType(names, bounds, resType) =>
          val tparams = names.zip(bounds).map(_ + " <: " + _.show).mkString("[", ", ", "]")
          tparams + resType.show

        case TypeParamRef(name, _) =>
          name

        case TypeBound(lo, hi) =>
          lo.show + " .. " + hi.show

        case ProcType(names, paramTypes, resType) =>
          val params = names.zip(paramTypes).map(_ + ": " + _.show).mkString("(", ", ", ")")
          params + ": " + resType.show

        case FunctionType(paramTypes, resType) =>
          val params = paramTypes.map(_.show).mkString(" * ")
          params + " => " + resType.show
    end show
  end Type

  case object IntType extends Type

  case object BoolType extends Type

  case object VoidType extends Type

  case object AnyType extends Type

  case object BottomType extends Type

  case object ErrorType extends Type

  case class TypeRef(symbol: Symbol) extends Type

  /** A record type --- named tuples
    *
    * Warning: flattening of nested tuples is dangerous with subtyping
    * of records.
    */
  case class RecordType(fields: List[(String, Type)]) extends Type:
    val fieldNames: List[String] = fields.map(_._1)

    def getFieldType(field: String): Option[Type] =
      fields.collectFirst:
        case (f, tp) if f == field => tp

    def hasField(name: String): Boolean =
      fieldNames.contains(name)

    def fieldType(name: String): Type =
      getFieldType(name).get

  case class UnionType(branches: List[(String, List[Type])]) extends Type:
    val tags: List[String] = branches.map(_._1)

    def getTagType(tag: String): Option[List[Type]] =
      branches.collectFirst:
        case (t, tps) if t == tag => tps

    def hasTag(tag: String): Boolean =
      tags.contains(tag)

    def tagType(tag: String): List[Type] =
      getTagType(tag).get

    def tagIndex(tag: String): Int =
      branches.indexWhere:
        case (t, _) => t == tag

  case class PolyType
    (names: List[String], bounds: List[Type], resultType: Type)
  extends Type:
    val paramCount = bounds.size

  case class ProcType
    (names: List[String], paramTypes: List[Type], resultType: Type)
  extends Type:
    val paramCount = paramTypes.size
    val resCount = if resultType.isValueType then 1 else 0

  /** A type lambda */
  case class TypeLambda
    (names: List[String], bounds: List[Type], body: Type)
  extends Type:
    val paramCount = names.size

  /** An index reference to type parameter
    *
    * There is no support for nested type parameters. Therefore, there is no
    * need to distinguish the holders that the type parameters are attached
    * to.
    */
  case class TypeParamRef
    (name: String, index: Int)
  extends Type

  case class AppliedType
    (tctor: Type, targs: List[Type])
  extends Type

  case class FunctionType
    (paramTypes: List[Type], resultType: Type)
  extends Type

  /** Represents upper and lower bounds of type parameters */
  case class TypeBound
    (lo: Type, hi: Type)
  extends Type

  /** Delayed type for symbols to enable type inference and recursive types */
  case class DelayedType
    ()
    (infoCompleter: => Type)
  extends Type:
    private var _underlying: Type = null

    def underlying: Type = force()

    private def complete(): Unit =
      assert(_underlying == null, "Double completing: " + _underlying)
      _underlying = infoCompleter

    def isComplete: Boolean = _underlying != null

    def force(): Type =
      if !isComplete then complete()
      _underlying

    override def equals(that: Any): Boolean =
      if !isComplete then false
      else
        that match
          case tp: DelayedType =>
            tp.isComplete && tp._underlying == this._underlying

          case _ =>
            false
        end match

    override def hashCode(): Int =
      if !isComplete then throw new Exception("Hashing incomplete type")
      else _underlying.hashCode

    override def toString =
      "Delayed(" + _underlying + ")"
  end DelayedType

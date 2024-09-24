import Symbols.Symbol

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

    def isVoid: Boolean = TypeOps.dealias(this) == VoidType

    def isAny: Boolean = TypeOps.dealias(this) == AnyType

    def isBottom: Boolean = TypeOps.approx(this, isUp = true) == BottomType

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

    def asInvokableType: InvokableType = TypeOps.approx(this, isUp = true).asInstanceOf[InvokableType]

    def asPolyType: PolyType = TypeOps.approx(this, isUp = true).asInstanceOf[PolyType]

    def is[T <: Type : ClassTag]: Boolean =
      this match
        case tp: T => true
        case _     => false

    def as[T <: Type]: T = this.asInstanceOf[T]

    def show: String = TypeOps.show(this)
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

  sealed trait InvokableType extends Type:
    def paramTypes: List[Type]
    def resultType: Type

    def paramCount = paramTypes.size
    def resCount = if resultType.isValueType then 1 else 0

  case class ParamInfo(name: String, tpe: Type)

  case class ProcType
    (params: List[ParamInfo], resultType: Type, preParamCount: Int)
  extends Type with InvokableType:
    val preParamTypes: List[Type] = params.take(preParamCount).map(_.tpe)
    val postParamTypes: List[Type] = params.drop(preParamCount).map(_.tpe)
    val paramTypes: List[Type] = params.map(_.tpe)
    def postParamCount = params.size - preParamCount
    def toFunType: FunctionType = FunctionType(paramTypes, resultType)

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
  extends Type with InvokableType

  /** Represents upper and lower bounds of type parameters */
  case class TypeBound
    (lo: Type, hi: Type)
  extends Type

  class TypeVar(name: String, handler: Inference.Handler) extends Type:
    override def toString = "TypeVar(" + name + ")"

    def dealias: Type = handler.dealias(this)

    def approx(isUp: Boolean) = handler.approx(this, isUp)

    def isSubtype(tp: Type): Boolean = handler.isSubtype(this, tp)

    def isSuptype(tp: Type): Boolean = handler.isSuptype(this, tp)

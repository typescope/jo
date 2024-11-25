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
    def isError: Boolean = TypeOps.dealias(this) == ErrorType

    def isVoidType: Boolean = TypeOps.dealias(this) == VoidType

    def isAnyType: Boolean = TypeOps.dealias(this) == AnyType

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

  /** A proxy type may be reduced to other types in subtype and shape checking.
    *
    * In addition, proxy types can lead to loops in types, thus need to be
    * handled specially in subtyping and approximation.
    */
  sealed abstract class ProxyType extends Type

  /** A reference to either a type symbol or a term symbol */
  case class TypeRef(symbol: Symbol) extends ProxyType

  /** A part of a type with a specific name */
  case class NamedInfo[+T](name: String, info: T)

  /** A record type --- named tuples
    *
    * Warning: flattening of nested tuples is dangerous with subtyping
    * of records.
    */
  case class RecordType(fields: List[NamedInfo[Type]]) extends Type:
    val fieldNames: List[String] = fields.map(_.name)

    def getFieldType(field: String): Option[Type] =
      fields.collectFirst:
        case NamedInfo(f, tp) if f == field => tp

    def hasField(name: String): Boolean =
      fieldNames.contains(name)

    def fieldType(name: String): Type =
      getFieldType(name).get

  case class UnionType(branches: List[NamedInfo[List[NamedInfo[Type]]]]) extends Type:
    val tags: List[String] = branches.map(_.name)

    def getTagType(tag: String): Option[List[Type]] =
      branches.collectFirst:
        case NamedInfo(t, tps) if t == tag => tps.map(_.info)

    def hasTag(tag: String): Boolean =
      tags.contains(tag)

    def tagType(tag: String): List[Type] =
      getTagType(tag).get

    def tagIndex(tag: String): Int =
      branches.indexWhere:
        case NamedInfo(t, _) => t == tag

  case class PolyType
    (tparams: List[NamedInfo[TypeBound]], resultType: Type)
  extends Type:
    val names: List[String] = tparams.map(_.name)
    val bounds: List[TypeBound] = tparams.map(_.info)
    val paramCount = tparams.size

  sealed trait InvokableType extends Type:
    def paramTypes: List[Type]
    def resultType: Type

    def paramCount = paramTypes.size
    def resCount = if resultType.isValueType then 1 else 0

  case class ProcType
    (params: List[NamedInfo[Type]], resultType: Type, preParamCount: Int)
  extends Type with InvokableType:
    val preParamTypes: List[Type] = params.take(preParamCount).map(_.info)
    val postParamTypes: List[Type] = params.drop(preParamCount).map(_.info)
    val paramTypes: List[Type] = params.map(_.info)
    def postParamCount = params.size - preParamCount
    def toFunType: FunctionType = FunctionType(paramTypes, resultType)

  /** A type lambda */
  case class TypeLambda
    (tparams: List[NamedInfo[TypeBound]], body: Type)
  extends Type:
    val names: List[String] = tparams.map(_.name)
    val bounds: List[Type] = tparams.map(_.info)
    val paramCount: Int = tparams.size

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
  extends ProxyType

  case class FunctionType
    (paramTypes: List[Type], resultType: Type)
  extends Type with InvokableType

  /** Represents upper and lower bounds of type parameters */
  case class TypeBound
    (lo: Type, hi: Type)
  extends Type

  class TypeVar(name: String, inferencer: Inference.Inferencer) extends ProxyType:
    override def toString = "TypeVar(" + name + ")"

    def isInstantiated: Boolean =
      this.dealias != this

    def dealias: Type = inferencer.dealias(this)

    def approx(isUp: Boolean): Type = inferencer.approx(this, isUp)

    def isSubtype(tp: Type): List[Subtyping.Task] =
      inferencer.isSubtype(this, tp)

    def isSuptype(tp: Type): List[Subtyping.Task] =
      inferencer.isSuptype(this, tp)

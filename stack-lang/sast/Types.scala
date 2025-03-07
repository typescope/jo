package sast

import Symbols.Symbol

import typing.Inference

import scala.reflect.ClassTag
import scala.collection.mutable

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

    def isObjectType: Boolean =
      TypeOps.approx(this, isUp = true).isInstanceOf[ObjectType]

    def isTypeLambda: Boolean =
      TypeOps.approx(this, isUp = true).isInstanceOf[TypeLambda]

    def isProcType: Boolean =
      TypeOps.approx(this, isUp = true).isInstanceOf[ProcType]

    def isPolyType: Boolean =
      TypeOps.approx(this, isUp = true).isInstanceOf[PolyType]

    def isValueType: Boolean =
      TypeOps.approx(this, isUp = true)  match
        case VoidType | _: ProcType | _: TypeLambda | _: PolyType | _: NameTableInfo => false
        case _ => true

    def dealias: Type = TypeOps.dealias(this)

    def asRecordType: RecordType =
      TypeOps.approx(this, isUp = true).asInstanceOf[RecordType]

    def asUnionType: UnionType =
      TypeOps.approx(this, isUp = true).asInstanceOf[UnionType]

    def asTypeLambda: TypeLambda =
      TypeOps.approx(this, isUp = true).asInstanceOf[TypeLambda]

    def asProcType: ProcType =
      TypeOps.approx(this, isUp = true).asInstanceOf[ProcType]

    def asPolyType: PolyType =
      TypeOps.approx(this, isUp = true).asInstanceOf[PolyType]

    def asObjectType: ObjectType =
      TypeOps.approx(this, isUp = true).asInstanceOf[ObjectType]

    def hasApplyMethod: Boolean =
      TypeOps.approx(this, isUp = true) match
        case objType: ObjectType =>
          val tp = objType.termMember("apply")
          tp.isProcType || tp.isPolyType

        case _ => false

    def getFunctionApplyType: Option[ProcType] =
      TypeOps.approx(this, isUp = true) match
        case ObjectType(Nil, NamedInfo("apply", tp) :: Nil, Nil) =>
         TypeOps.approx(tp, isUp = true) match
            case procType: ProcType => Some(procType)
            case _ => None

        case _ => None

    def refersTo(symbol: Symbol): Boolean =
      val visited = new mutable.ArrayBuffer[Symbol]

      def recur(tp: Type): Boolean =
        tp match
          case TypeRef(sym) =>
            sym == symbol || !visited.contains(sym) && {
              visited += sym
              recur(sym.info)
            }

          case AppliedType(ctor, _) => recur(ctor)

          case _ => false
      end recur
      recur(this)

    def refersAny(symbols: List[Symbol]): Boolean =
      val visited = new mutable.ArrayBuffer[Symbol]
      def recur(tp: Type): Boolean =
        tp match
          case TypeRef(sym) =>
            symbols.contains(sym) || !visited.contains(sym) && {
              visited += sym
              recur(sym.info)
            }

          case AppliedType(ctor, _) => recur(ctor)

          case _ => false
      end recur
      recur(this)

    def getTermMember(name: String): Option[Type] =
      TypeOps.approx(this, isUp = true) match
        case info: NameTableInfo =>
          info.resolveTerm(name).map(sym => TypeRef(sym))

        case recordType: RecordType =>
          recordType.getFieldType(name)

        case objectType: ObjectType =>
          objectType.getMemberType(name)

        case tp =>
          None

    def termMember(name: String): Type = getTermMember(name).get

    def hasTermMember(name: String): Boolean = getTermMember(name).nonEmpty

    def is[T <: Type : ClassTag]: Boolean =
      this match
        case tp: T => true
        case _     => false

    def as[T <: Type]: T = this.asInstanceOf[T]

    def show: String = TypeOps.show(this)
  end Type

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

  /** A reference to either a type symbol or a term symbol
    *
    * TODO: rename to RefType
    */
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

  /** The type of an object */
  case class ObjectType(
    fields: List[NamedInfo[Type]],
    methods: List[NamedInfo[Type]],
    mutableFields: List[String])
  extends Type:
    def fieldNames = fields.map(_.name)
    def methodNames = methods.map(_.name)

    def getMemberType(name: String): Option[Type] =
      val fieldOpt = fields.collectFirst:
        case NamedInfo(m, tp) if m == name => tp

      if fieldOpt.isEmpty then
        methods.collectFirst:
          case NamedInfo(m, tp) if m == name => tp
      else
        fieldOpt

    def isMutable(name: String): Boolean = mutableFields.contains(name)

  case class PolyType
    (tparams: List[NamedInfo[TypeBound]], resultType: Type)
  extends Type:
    val names: List[String] = tparams.map(_.name)
    val bounds: List[TypeBound] = tparams.map(_.info)
    val paramCount = tparams.size

  /** The type of a procedure or method
    *
    * The receive parameters of methods are always explicitly specified. If
    * unspecified, the receive parameters of methods are regarded as empty.
    *
    * For procedure, if unspecified, it means the receive parameters will be
    * inferred.
    */
  case class ProcType
    (params: List[NamedInfo[Type]], resultType: Type, receives: Option[List[Symbol]], preParamCount: Int)
  extends Type:
    val preParamTypes: List[Type] = params.take(preParamCount).map(_.info)
    val postParamTypes: List[Type] = params.drop(preParamCount).map(_.info)
    val paramTypes: List[Type] = params.map(_.info)
    val paramCount: Int = params.size

    def prepend(paramsToAdd: List[NamedInfo[Type]]): ProcType =
      ProcType(paramsToAdd ++ params, resultType, receives, preParamCount)

    def append(paramsToAdd: List[NamedInfo[Type]]): ProcType =
      ProcType(params ++ paramsToAdd, resultType, receives, preParamCount)

    def postParamCount = params.size - preParamCount

    def resCount = if resultType.isValueType then 1 else 0

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

  /** Represents upper and lower bounds of type parameters */
  case class TypeBound
    (lo: Type, hi: Type)
  extends Type

  class TypeVar(name: String, inferencer: Inference.Inferencer) extends ProxyType:
    override def toString = "TypeVar(" + name + ")"

    def isInstantiated: Boolean =
      this.dealias != this

    override def dealias: Type = inferencer.dealias(this)

    def approx(isUp: Boolean): Type = inferencer.approx(this, isUp)

    def isSubtype(tp: Type): List[Subtyping.Task] =
      inferencer.isSubtype(this, tp)

    def isSuptype(tp: Type): List[Subtyping.Task] =
      inferencer.isSuptype(this, tp)

  class NameTableInfo(val nameTable: NameTable) extends Type:
    def this() = this(new NameTable)

    export nameTable.{ resolve, resolveType, resolveTerm, define }

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
    /** Whether the type is an error type
      *
      * Avoid type reduction as the types might not be well-formed.
      */
    def isError(using Definitions): Boolean =
      this == ErrorType || this.match
        case TypeRef(sym) =>
          // Don't recur to avoid loops
          sym.info == ErrorType

        case _ =>
          false

    /** Whether the type is Void
      *
      * There is no way to write this type in the program. It is the type given
      * to while-loops, assignments and definitions.
      */
    def isVoidType: Boolean = this == VoidType

    def isAnyType(using Definitions): Boolean = TypeOps.dealias(this) == AnyType

    def isBottom(using Definitions): Boolean = TypeOps.approx(this, isUp = true) == BottomType

    def isRecordType(using Definitions): Boolean =
      TypeOps.approx(this, isUp = true).isInstanceOf[RecordType]

    def isUnionType(using Definitions): Boolean =
       // No polymorphism over union type thus only dealias no approximation
      widenTermRef.dealias.isInstanceOf[UnionType]

    /** Is the type a reference to a type alias */
    def isTypeRef: Boolean =
      this match
        case TypeRef(sym) => sym.isType
        case _ => false

    /** Is the type a reference to a term name */
    def isTermRef: Boolean =
      this match
        case TypeRef(sym) => !sym.isType
        case _ => false

    def isObjectType(using Definitions): Boolean =
      TypeOps.approx(this, isUp = true).isInstanceOf[ObjectType]

    def isTypeLambda(using Definitions): Boolean =
      TypeOps.approx(this, isUp = true).isInstanceOf[TypeLambda]

    def isProcType(using Definitions): Boolean =
      TypeOps.approx(this, isUp = true).isInstanceOf[ProcType]

    def isPolyType(using Definitions): Boolean =
      TypeOps.approx(this, isUp = true) match
        case procType: ProcType => procType.tparams.nonEmpty
        case _ => false

    def isTagType(using Definitions): Boolean =
      TypeOps.approx(this, isUp = true).isInstanceOf[TagType]

    def isValueType(using Definitions): Boolean =
      TypeOps.approx(this, isUp = true)  match
        case VoidType | _: ProcType | _: TypeLambda | _: NameTableInfo => false
        case _ => true

    /** A grounded type cannot be simplied further at the top-level
      *
      * The following proxy types are not grounded:
      *
      * - type aliases
      * - uninstantiated type variables
      */
    def isGrounded(using Definitions): Boolean = TypeOps.isGrounded(this)

    def dealias(using Definitions): Type = TypeOps.dealias(this)

    /** Widen a term reference to its underlying type */
    def widenTermRef(using Definitions): Type =
      this match
        case TypeRef(sym) if !sym.isType => sym.info
        case _ => this

    /** Widen a constant type to its underlying type */
    def widenConstType(using Definitions): Type =
      this match
        case constType: ConstantType => constType.underlying
        case _ => this

    def asRecordType(using Definitions): RecordType =
      TypeOps.approx(this, isUp = true).asInstanceOf[RecordType]

    def asUnionType(using Definitions): UnionType =
      // No polymorphism over union type thus only dealias no approximation
      widenTermRef.dealias.asInstanceOf[UnionType]

    def asTagType(using Definitions): TagType =
      TypeOps.approx(this, isUp = true).asInstanceOf[TagType]

    def asTypeLambda(using Definitions): TypeLambda =
      TypeOps.approx(this, isUp = true).asInstanceOf[TypeLambda]

    def asProcType(using Definitions): ProcType =
      TypeOps.approx(this, isUp = true).asInstanceOf[ProcType]

    def asObjectType(using Definitions): ObjectType =
      TypeOps.approx(this, isUp = true).asInstanceOf[ObjectType]

    def isSingleMethodObjectType(using Definitions): Boolean = getSingleMethodType.nonEmpty

    def getSingleMethodType(using Definitions): Option[NamedInfo[ProcType]] =
      TypeOps.approx(this, isUp = true) match
        case ObjectType(Nil, NamedInfo(name, tp) :: Nil, Nil) =>
          TypeOps.approx(tp, isUp = true) match
             case procType: ProcType => Some(NamedInfo(name, procType))
             case _ => None

        case _ => None

    /** Convert Partial[T] to T if possible */
    def stripPartial(using defn: Definitions): Type =
      this match
        case AppliedType(ctor, targs) if ctor.refers(defn.Predef_Partial) =>
          targs(0)

        case _ => this

    /** Is the current type equivalent to a TypeRef or AppliedType to the given symbol  */
    def refers(symbol: Symbol)(using Definitions): Boolean =
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

    def refersAny(symbols: List[Symbol])(using Definitions): Boolean =
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

    def getTermMember(name: String)(using Definitions): Option[Type] =
      TypeOps.approx(this, isUp = true) match
        case info: NameTableInfo =>
          info.resolveTerm(name).map(sym => TypeRef(sym))

        case recordType: RecordType =>
          recordType.getFieldType(name)

        case tagType: TagType =>
          tagType.getParamType(name)

        case objectType: ObjectType =>
          objectType.getMemberType(name)

        case tp =>
          // println("No member " + name + " on " + tp)
          None

    def termMember(name: String)(using Definitions): Type =
      getTermMember(name) match
        case Some(tp) => tp
        case None => throw new Exception(s"No member $name in " + this.show)

    def hasTermMember(name: String)(using Definitions): Boolean =
      getTermMember(name).nonEmpty

    def exists(pred: Type => Boolean)(using Definitions): Boolean =
      var exists = false
      val traverser = new TypeOps.TypeTraverser:
        def apply(tp: Type)(using Context) =
          exists = exists || pred(tp)
          if !exists then recur(tp)
      traverser.apply(this)
      exists


    def is[T <: Type : ClassTag]: Boolean =
      this match
        case tp: T => true
        case _     => false

    def as[T <: Type]: T = this.asInstanceOf[T]

    def show(using Definitions): String = Printing.show(this)
  end Type

  case object VoidType extends Type

  case object AnyType extends Type

  case object BottomType extends Type

  case object ErrorType extends Type

  case class ConstantType(const: Constant) extends Type:
    def underlying(using defn: Definitions): Type =
      const match
        case _: Constant.Bool => defn.BoolType
        case _: Constant.String => defn.StringType
        case _: Constant.Int => defn.IntType

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

  case class UnionType(branches: List[Type])(using Definitions) extends Type:
    private val tagMap: Map[String, TagType] =
      branches.foldLeft(Map.empty): (acc, branch) =>
        if branch.isTagType then
          val tagType = branch.asTagType
          assert(!acc.contains(tagType.tag), "duplicate tag " + tagType.tag + " in " + this.show)
          acc.updated(tagType.tag, tagType)

        else if branch.isUnionType then
          val unionType = branch.asUnionType
          unionType.tagTypes.foldLeft(acc): (acc, tagType) =>
            assert(!acc.contains(tagType.tag), "duplicate tag " + tagType.tag + " in " + this.show)
            acc.updated(tagType.tag, tagType)

        else
          throw new Exception("Expect union type or tag type, found = " + branch)

    val tags: List[String] = tagMap.keys.toList

    val tagTypes: List[TagType] = tagMap.values.toList

    def getTagType(tag: String): Option[TagType] = tagMap.get(tag)

    def hasTag(tag: String): Boolean = tagMap.contains(tag)

    def tagType(tag: String): TagType = tagMap(tag)

  /** The type for tagged value like `#Some(3)` */
  case class TagType(tag: String, params: List[NamedInfo[Type]]) extends Type:
    val paramTypes: List[Type] = params.map(_.info)

    def hasParam(name: String): Boolean = params.exists(_.name == name)

    def getParamType(name: String): Option[Type] =
      params.find(_.name == name).map(_.info)

    def paramIndex(name: String): Int = params.indexWhere(_.name == name)

  object TagType:
    def from(tag: String, paramTypes: List[Type]) =
      val params =
        paramTypes.zipWithIndex.map { case (tp, i) => NamedInfo("_" + (i + 1), tp) }
      this(tag, params)

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

  /** The type of a procedure or method
    *
    * The receive parameters of methods are always explicitly specified. If
    * unspecified, the receive parameters of methods are regarded as empty.
    *
    * For procedures, if unspecified, it means the receive parameters will be
    * inferred.
    */
  case class ProcType
    (tparams: List[Symbol], params: List[NamedInfo[Type]], autos: List[NamedInfo[Type]],
      resultType: Type, receives: Option[List[Symbol]], preParamCount: Int)
  extends Type:
    val preParamTypes: List[Type] = params.take(preParamCount).map(_.info)
    val postParamTypes: List[Type] = params.drop(preParamCount).map(_.info)

    val paramTypes: List[Type] = params.map(_.info)

    val paramCount: Int = params.size
    val tparamCount: Int = tparams.size

    val autoTypes: List[Type] = autos.map(_.info)

    val allParamTypes: List[Type] = paramTypes ++ autoTypes

    def minimumArgs(using Definitions): Int =
      if hasVararg then paramCount - 1 else paramCount

    def minimumPostArgs(using Definitions): Int =
      if hasVararg then postParamTypes.size - 1 else postParamTypes.size

    def hasVararg(using defn: Definitions): Boolean =
      paramCount > 0 && paramTypes.last.refers(defn.Predef_Pack)

    def bounds(using Definitions): List[TypeBound] =
      tparams.map(_.info.as[TypeBound])

    def instantiate(targs: List[Type])(using Definitions): ProcType =
      assert(tparamCount == targs.size, "expect " + tparamCount + ", found = " + targs.size)
      val subst = tparams.zip(targs).toMap
      // TODO: check bounds once they are supported
      TypeOps.substSymbols(this.copy(tparams = Nil), subst).as[ProcType]

    def prepend(paramsToAdd: List[NamedInfo[Type]]): ProcType =
      ProcType(tparams, paramsToAdd ++ params, autos, resultType, receives, preParamCount)

    def append(paramsToAdd: List[NamedInfo[Type]]): ProcType =
      ProcType(tparams, params ++ paramsToAdd, autos, resultType, receives, preParamCount)

    def postParamCount = params.size - preParamCount

    def resCount(using Definitions) = if resultType.isValueType then 1 else 0

  /** A type lambda */
  case class TypeLambda
    (tparams: List[Symbol], body: Type)
  extends Type:
    val names: List[String] = tparams.map(_.name)
    val paramCount: Int = tparams.size

    def bounds(using Definitions): List[Type] = tparams.map(_.info)

    def instantiate(targs: List[Type])(using Definitions): Type =
      assert(tparams.size == targs.size, "expect " + tparams.size + ", found = " + targs.size)
      val subst = tparams.zip(targs).toMap
      // TODO: check bounds once they are supported
      TypeOps.substSymbols(body, subst)

  case class AppliedType
    (tctor: Type, targs: List[Type])
  extends ProxyType:
    tctor match
      case TypeRef(sym) if sym.isType =>
      case _ => assert(false, tctor)

  /** Represents upper and lower bounds of type parameters */
  case class TypeBound
    (lo: Type, hi: Type)
  extends Type

  class TypeVar(name: String, inferencer: Inference.Inferencer) extends ProxyType:
    override def toString = "TypeVar(" + name + ")"

    def isInstantiated: Boolean = inferencer.isInstantiated(this)

    def instantiated: Type = inferencer.instantiated(this)

    def approx(isUp: Boolean): Type = inferencer.approx(this, isUp)

    def isSubtype(tp: Type): List[Subtyping.Task] =
      inferencer.isSubtype(this, tp)

    def isSuptype(tp: Type): List[Subtyping.Task] =
      inferencer.isSuptype(this, tp)

  class NameTableInfo(val nameTable: NameTable) extends Type:
    def this() = this(new NameTable)

    export nameTable.{ resolveType, resolveTerm, resolvePattern, define }

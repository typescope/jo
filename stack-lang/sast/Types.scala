package sast

import Symbols.Symbol

import typing.Inference

import scala.reflect.ClassTag
import scala.collection.mutable

/** The type system of Stk  */
object Types:
  sealed abstract class Type:
    /** Approximate this type to its supertype by dealiasing and widening */
    def approx(using defn: Definitions): Type =
      defn.cache.approximate(this):
        TypeOps.approx(this, isUp = true)

    /** Whether the type is an error type
      *
      * Avoid type reduction as the types might not be well-formed.
      */
    def isError(using Definitions): Boolean =
      this == ErrorType || this.match
        case StaticRef(sym) =>
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

    def isBottom(using Definitions): Boolean = this.approx == BottomType

    def isRecordType(using Definitions): Boolean =
      this.approx.isInstanceOf[RecordType]

    def isUnionType(using Definitions): Boolean =
       // No polymorphism over union type thus only dealias no approximation
      widenTermRef.dealias.isInstanceOf[UnionType]

    /** Is the type a reference to a type alias */
    def isTypeRef: Boolean =
      this match
        case StaticRef(sym) => sym.isType
        case MemberRef(_, sym) => sym.isType
        case _ => false

    /** Is the type a reference to a term name */
    def isTermRef: Boolean =
      this match
        case StaticRef(sym) => !sym.isType
        case MemberRef(_, sym) => !sym.isType
        case _ => false

    def isObjectType(using Definitions): Boolean =
      this.approx.isInstanceOf[ObjectType]

    def isTypeLambda(using Definitions): Boolean =
      this.approx.isInstanceOf[TypeLambda]

    def isProcType(using Definitions): Boolean =
      this.approx.isInstanceOf[ProcType]

    def isClassType(using Definitions): Boolean =
      this.approx.isInstanceOf[ClassInfo]

    def isPolyType(using Definitions): Boolean =
      this.approx match
        case procType: ProcType => procType.tparams.nonEmpty
        case _ => false

    def isTagType(using Definitions): Boolean =
      this.approx.isInstanceOf[TagType]

    def isValueType: Boolean =
      this match
        case VoidType | _: ProcType | _: TypeLambda | _: ContainerInfo | _: ClassInfo => false

        case refType: RefType =>
          val sym = refType.symbol

          !sym.isType && !sym.isFunction
          || sym.isType && sym.asTypeSymbol.kind == Kind.Simple

        case _ => true

    /** Return the kind of a value type and return None for non-value type. */
    def kind: Option[Kind] =
      this match
        case VoidType | _: ProcType | _: TypeLambda | _: ContainerInfo | _: ClassInfo =>
          None

        case refType: RefType if refType.symbol.isType =>
          Some(refType.symbol.asTypeSymbol.kind)

        case _ =>
          Some(Kind.Simple)

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
        case refType: RefType if !refType.symbol.isType => refType.info.widenTermRef
        case _ => this

    /** Widen a constant type to its underlying type */
    def widenConstType(using Definitions): Type =
      this match
        case constType: ConstantType => constType.underlying
        case _ => this

    def widen(using Definitions): Type = widenTermRef.widenConstType

    def asRecordType(using Definitions): RecordType =
      this.approx.asInstanceOf[RecordType]

    def asUnionType(using Definitions): UnionType =
      // No polymorphism over union type thus only dealias no approximation
      widenTermRef.dealias.asInstanceOf[UnionType]

    def asTagType(using Definitions): TagType =
      this.approx.asInstanceOf[TagType]

    def asTypeLambda(using Definitions): TypeLambda =
      this.approx.asInstanceOf[TypeLambda]

    def asProcType(using Definitions): ProcType =
      this.approx.asInstanceOf[ProcType]

    def asObjectType(using Definitions): ObjectType =
      this.approx.asInstanceOf[ObjectType]

    def asClassInfo(using Definitions): ClassInfo =
      this.approx.asInstanceOf[ClassInfo]

    def isSingleMethodObjectType(using Definitions): Boolean = getSingleMethodType.nonEmpty

    def getSingleMethodType(using Definitions): Option[NamedInfo[ProcType]] =
      this.approx match
        case ObjectType(NamedInfo(name, tp) :: Nil, Nil) =>
          tp.approx match
             case procType: ProcType => Some(NamedInfo(name, procType))
             case _ => None

        case _ => None

    /** Convert Partial[T] to T if possible */
    def stripPartial(using defn: Definitions): Type =
      this match
        case AppliedType(ctor, targs) if ctor.refers(defn.Predef_Partial) =>
          targs(0)

        case _ => this

    /** Is the current type equivalent to a StaticRef or AppliedType to the given symbol  */
    def refers(symbol: Symbol)(using Definitions): Boolean =
      val visited = new mutable.ArrayBuffer[Symbol]

      def recur(tp: Type): Boolean =
        tp match
          case StaticRef(sym) =>
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
          case StaticRef(sym) =>
            symbols.contains(sym) || !visited.contains(sym) && {
              visited += sym
              recur(sym.info)
            }

          case AppliedType(ctor, _) => recur(ctor)

          case _ => false
      end recur
      recur(this)

    def getTermMember(name: String)(using Definitions): Option[Type] =
      this.approx match
        case info: ContainerInfo =>
          info.resolveTerm(name).map(sym => StaticRef(sym))

        case info: ClassInfo =>
          info.getTermMember(this, name)

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
        case None => throw new Exception(s"No member $name in " + this + ", approx = " + this.approx)

    def hasTermMember(name: String)(using Definitions): Boolean =
      getTermMember(name).nonEmpty

    def exists(pred: Type => Boolean)(using Definitions): Boolean =
      var exists = false
      val traverser = new TypeTraverser:
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

  sealed abstract class RefType extends ProxyType:
    val symbol: Symbol

    def info(using Definitions): Type

  /** A reference to a symbol who type is does not depend on any prefix */
  case class StaticRef(symbol: Symbol) extends RefType:
    def info(using Definitions): Type = symbol.info

  /** A reference to member symbol whose type depends on that of its prefix */
  case class MemberRef(prefix: Type, symbol: Symbol) extends RefType:
    assert(!symbol.isType, "No support for member types: " + symbol)

    def info(using Definitions): Type =
      // compute the type with respect to the instantiated targs
      prefix.approx match
        case classInfo: ClassInfo =>
          TypeOps.substSymbols(symbol.info, classInfo.tparams, classInfo.targs)

        case _ =>
          symbol.info

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
    members: List[NamedInfo[Type]],
    mutableFields: List[String])
  extends Type:
    lazy val fields = members.filter(_.info.isValueType)
    lazy val methods = members.filter(!_.info.isValueType)

    lazy val fieldNames = fields.map(_.name)
    lazy val methodNames = methods.map(_.name)

    private val memberTypeMap: Map[String, Type] =
      val mutMap = mutable.Map.empty[String, Type]
      members.foreach:
        case NamedInfo(name, info) =>
          assert(!mutMap.contains(name), "duplicate member " + name + " in " + this)
          mutMap(name) = info
      mutMap.toMap

    def getMemberType(name: String): Option[Type] =
      memberTypeMap.get(name)

    def isMutable(name: String): Boolean = mutableFields.contains(name)

  /** The type of a function, method or pattern predicates */
  case class ProcType
    (tparams: List[Symbol], params: List[NamedInfo[Type]], autos: List[NamedInfo[Type]],
      resultType: Type, receivesInfo: () => List[Symbol], preParamCount: Int)
  extends Type:
    val preParamTypes: List[Type] = params.take(preParamCount).map(_.info)
    val postParamTypes: List[Type] = params.drop(preParamCount).map(_.info)

    val paramTypes: List[Type] = params.map(_.info)

    val paramCount: Int = params.size
    val tparamCount: Int = tparams.size

    val autoTypes: List[Type] = autos.map(_.info)

    val allParamTypes: List[Type] = paramTypes ++ autoTypes
    val allParamCount: Int = allParamTypes.size

    /** Unlike types, context parameter inference supports cycles thus its
      * computation must be delayed and be handled indirectly via the effect
      * engine.
      */
    lazy val receives: List[Symbol] = receivesInfo()

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
      // TODO: check bounds once they are supported
      TypeOps.substSymbols(this.copy(tparams = Nil), tparams, targs).as[ProcType]

    def prepend(paramsToAdd: List[NamedInfo[Type]]): ProcType =
      this.copy(params = paramsToAdd ++ params)

    def append(paramsToAdd: List[NamedInfo[Type]]): ProcType =
      this.copy(params = params ++ paramsToAdd)

    def postParamCount = params.size - preParamCount

    def resCount(using Definitions) = if resultType.isValueType then 1 else 0


  /** A type lambda */
  case class TypeLambda
    (tparams: List[Symbol], body: Type, preParamCount: Int)
  extends Type:
    val names: List[String] = tparams.map(_.name)
    val paramCount: Int = tparams.size

    def postParamCount = paramCount - preParamCount

    def bounds(using Definitions): List[Type] = tparams.map(_.info)

    def instantiate(targs: List[Type])(using Definitions): Type =
      assert(tparams.size == targs.size, "expect " + tparams.size + ", found = " + targs.size)
      // TODO: check bounds once they are supported
      TypeOps.substSymbols(body, tparams, targs)

  case class AppliedType
    (tctor: Type, targs: List[Type])
  extends ProxyType:
    assert(targs.nonEmpty, this)
    tctor match
      case StaticRef(sym) if sym.isType =>
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

  /** Represents the information of a namespace or section */
  class ContainerInfo(val nameTable: NameTable) extends Type:
    export nameTable.{ resolveType, resolveTerm, resolvePattern }

    def members: List[Symbol] = nameTable.members

  /** Represents the information of a class type
    *
    * @param methods all methods (including contructor)
    */
  case class ClassInfo(
    val classSymbol: Symbol, val tparams: List[Symbol], val targs: List[Type],
    val self: Symbol, val fields: List[Symbol], val methods: List[Symbol])
  extends Type:
    assert(tparams.size == targs.size, "Mismatch, tparams = " + tparams + ", targs = " + targs)

    /** Return all methods including the constructor */
    def allMethods: List[Symbol] = methods

    def field(name: String): Symbol =
      fields.find(_.name == name) match
        case Some(sym) => sym
        case None => throw new Exception("No field " + name + " in class " + classSymbol)

    def getMemberSymbol(name: String): Option[Symbol] =
      fields.find(_.name == name) match
        case None => methods.find(_.name == name)
        case res => res

    def getTermMember(prefix: Type, name: String)(using Definitions): Option[RefType] =
      getMemberSymbol(name).map: sym =>
        MemberRef(prefix, sym)

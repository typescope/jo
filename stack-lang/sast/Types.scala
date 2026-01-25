package sast

import Symbols.Symbol

import ast.Positions.Span

import scala.reflect.ClassTag

/** The type system of Jo  */
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

    def isTypeLambda(using Definitions): Boolean =
      this.approx.isInstanceOf[TypeLambda]

    def isProcType(using Definitions): Boolean =
      this.approx.isInstanceOf[ProcType]

    def isLambdaType(using Definitions): Boolean =
      this.approx.isInstanceOf[LambdaType]

    /** Is the current type after dealiasing a class or interface type*/
    def isClassInfoType(using Definitions): Boolean =
      this.approx.isInstanceOf[ClassInfo]

    /** Is the current type after dealiasing an interface type*/
    def isInterfaceType(using Definitions): Boolean =
      this.approx match
        case info: ClassInfo => info.classSymbol.is(Flags.Interface)
        case _ => false

    /** Is the current type after dealiasing an class type*/
    def isClassType(using Definitions): Boolean =
      this.approx match
        case info: ClassInfo => info.classSymbol.is(Flags.Class)
        case _ => false

    /** Is the current type after dealiasing the class type of a singleton object */
    def isSingletonObjectType(using Definitions): Boolean =
      this.approx match
        case info: ClassInfo => info.classSymbol.is(Flags.Object)
        case _ => false

    def isPolyType(using Definitions): Boolean =
      this.approx match
        case procType: ProcType => procType.tparams.nonEmpty
        case _ => false

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

    def isFullyInstantiated: Boolean =
      val checker = new TypeOps.FullyInstantiatedChecker
      checker(this)(using ())

    def uninstantiated: Set[TypeVar] =
      val censor = new TypeOps.UninstantiatedCensor
      censor(this)(using ())

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

    def widen(using Definitions): Type = TypeOps.widen(this)

    def asRecordType(using Definitions): RecordType =
      this.approx.asInstanceOf[RecordType]

    def asUnionType(using Definitions): UnionType =
      // No polymorphism over union type thus only dealias no approximation
      widenTermRef.dealias.asInstanceOf[UnionType]

    def asTypeLambda(using Definitions): TypeLambda =
      this.approx.asInstanceOf[TypeLambda]

    def asProcType(using Definitions): ProcType =
      this.approx.asInstanceOf[ProcType]

    def asLambdaType(using Definitions): LambdaType =
      this.approx.asInstanceOf[LambdaType]

    def asClassInfo(using Definitions): ClassInfo =
      this.approx.asInstanceOf[ClassInfo]

    /** Is this type an interface type compatible with a lambda type
      *
      * An interface is compatible to a lambda type if
      *
      * - it only has a single abstract method, and
      * - the method does not have type parameters nor auto parameters
      */
    def isLambdaInterface(using Definitions): Boolean =
      getLambdaInterfaceMethod.nonEmpty

    /** If this type is an interface type compatible with a lambda type, return
      * the corresponding lambda type.
      *
      * See the documentation for `isLambdaInterface`.
      */
    def getLambdaInterfaceType(using Definitions): Option[LambdaType] =
      getLambdaInterfaceMethod.map: sym =>
        val procType = MemberRef(this, sym).info.asProcType
        LambdaType(
          procType.params.map(_.info),
          procType.resultType,
          procType.receives
        )

    def getLambdaInterfaceMethod(using Definitions): Option[Symbol] =
      this.approx match
        case classInfo: ClassInfo if classInfo.classSymbol.isInterface =>
          val abstractMeths = classInfo.allMethods.filter(_.is(Flags.Defer))
          abstractMeths match
             case meth :: Nil =>
               val procType = meth.info.asProcType
               if procType.isPolyType || procType.autos.nonEmpty then None
               else Some(meth)

             case _ => None

        case _ => None

    /** Convert Partial[T] to T if possible */
    def stripPartial(using defn: Definitions): Type =
      this match
        case AppliedType(ctor, targs) if ctor == defn.Predef_Partial =>
          targs.head

        case _ => this

    /** Get adapters from a duck type, following type aliases */
    def adapters(using Definitions): List[ParamAdapter] =
      this match
        case duckType: DuckType =>
          duckType.adapters

        case StaticRef(sym) if sym.isType =>
          sym.info.adapters

        case tvar: TypeVar if tvar.isInstantiated =>
          tvar.instantiated.adapters

        case _ =>
          Nil

    /** Is the type Partial[T] */
    def isPartial(using defn: Definitions): Boolean =
      this match
        case AppliedType(ctor, targs) if ctor == defn.Predef_Partial =>
          true

        case _ => false

    /** Is the type ..T */
    def isVararg(using defn: Definitions): Boolean =
      this match
        case AppliedType(ctor, targs) if ctor == defn.Predef_Pack =>
          true

        case _ => false

    /** Convert ..T to T if possible */
    def stripVarargs(using defn: Definitions): Type =
      this match
        case AppliedType(sym, targs) if sym == defn.Predef_Pack =>
          targs(0)

        case _ => this

    def isSubtype(that: Type)(using Definitions): Boolean =
      Subtyping.conforms(this, that)

    /** Get intrinsic views declared within the class */
    def delegateViews(using Definitions): List[MemberRef] =
      this.approx match
        case info: ClassInfo =>
          info.delegateViews.map(view => MemberRef(this, view))

        case _ => Nil

    def getTermMember(name: String)(using Definitions): Option[Type] =
      this.approx match
        case info: ContainerInfo =>
          info.resolveTerm(name).map(sym => StaticRef(sym))

        case info: ClassInfo =>
          info.getTermMember(this, name)

        case recordType: RecordType =>
          recordType.getFieldType(name)

        case _ =>
          // println("No member " + name + " on " + tp)
          None

    def getPatternMember(name: String)(using Definitions): Option[Symbol] =
      this.approx match
        case info: ContainerInfo =>
          // For the moment, only containers may hold pattern members
          // println("resolving pattern " + name + " on " + info.nameTable.show)
          info.resolvePattern(name)

        case _ =>
          None


    def getContainerMember(name: String)(using Definitions): Option[Type] =
      this.approx match
        case info: ContainerInfo =>
          info.resolveContainer(name).map(sym => StaticRef(sym))

        case _ =>
          None

    def termMember(name: String)(using Definitions): Type =
      getTermMember(name) match
        case Some(tp) => tp
        case None => throw new Exception(s"No member $name in " + this + ", approx = " + this.approx)

    def hasTermMember(name: String)(using Definitions): Boolean =
      getTermMember(name).nonEmpty

    def hasContainerMember(name: String)(using Definitions): Boolean =
      getContainerMember(name).nonEmpty

    /** If the current type is a parameterless monomorphic function type (may have
      * autos), return the result type.
      *
      * Otherwise, return approx type of the current type.
      */
    def effectiveResultType(using Definitions): Type =
      this match
        case refType: RefType if !refType.symbol.isType =>
          // For RefType (MemberRef, StaticRef), get the actual type and recurse
          refType.info.effectiveResultType

        case procType: ProcType if procType.tparams.isEmpty && procType.params.isEmpty =>
          procType.resultType

        case tp =>
          tp

    def is[T <: Type : ClassTag]: Boolean =
      this match
        case _: T => true
        case _    => false

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
        case _: Constant.Float => defn.FloatType

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
          if classInfo.tparams.isEmpty then symbol.info
          else TypeOps.substSymbols(symbol.info, classInfo.tparams, classInfo.targs)

        case _ =>
          symbol.info

  /** A part of a type with a specific name */
  case class NamedInfo[+T](name: String, info: T)

  case class MemberCandidate(tp: Type, name: String)

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
    private val classMap: Map[Symbol, Type] =
      branches.foldLeft(Map.empty): (acc, branch) =>
        if branch.isClassType then
          val classInfo = branch.asClassInfo
          val cls = classInfo.classSymbol
          assert(!acc.contains(cls), "duplicate class " + cls + " in " + this.show)
          acc.updated(cls, branch)

        else if branch.isUnionType then
          val unionType = branch.asUnionType
          unionType.classTypes.foldLeft(acc): (acc, classType) =>
            val classInfo = classType.asClassInfo
            val cls = classInfo.classSymbol
            assert(!acc.contains(cls), "duplicate class " + cls + " in " + this.show)
            acc.updated(cls, classType)

        else
          throw new Exception("Expect union type or class type, found = " + branch.show)

    val classes: List[Symbol] = classMap.keys.toList

    val classTypes: List[Type] = classMap.values.toList

    def getClassType(cls: Symbol): Option[Type] = classMap.get(cls)

    def hasClass(cls: Symbol): Boolean = classMap.contains(cls)

    def classType(cls: Symbol): Type = classMap(cls)

  /** Adapters for duck types */
  enum ParamAdapter:
    case Function(symbol: Symbol)
    case Member(name: String)

  /** Duck type: compile-time duck typing with explicit adapters */
  case class DuckType(baseType: Type)(adaptersFun: () => List[ParamAdapter]) extends Type:
    lazy val adapters = adaptersFun()


  /** The type for lambdas, e.g. Int => Int receives indent */
  case class LambdaType(params: List[Type], resultType: Type, receives: List[Symbol]) extends Type:
    def toProcType: ProcType =
      val paramInfos = params.zipWithIndex.map:
        case (paramType, i) => NamedInfo("p" + i, paramType)

      ProcType(
        tparams = Nil,
        params = paramInfos,
        autos = Nil,
        candidates = Nil,
        resultType = resultType,
        receivesInfo = receives,
        preParamCount = 0
      )

  /** either the fun symbol or a list of effects */
  type ReceivesInfo = Symbol | List[Symbol]
  type LazyReceivesInfo = () => ReceivesInfo

  /** The type of a function, method or pattern predicates */
  case class ProcType
    (tparams: List[Symbol],
      params: List[NamedInfo[Type]],
      autos: List[NamedInfo[Type]],
      candidates: List[List[Symbol | MemberCandidate]],
      resultType: Type,
      receivesInfo: ReceivesInfo | LazyReceivesInfo,
      preParamCount: Int)
  extends Type:
    assert(autos.size == candidates.size)

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
    def receives(using defn: Definitions): List[Symbol] =
      receivesInfo match
        case sym: Symbol => defn.receives(sym)
        case effs: List[Symbol] => effs
        case lazyInfo: LazyReceivesInfo =>
          lazyInfo() match
            case sym: Symbol => defn.receives(sym)
            case effs: List[Symbol] => effs

    def minimumArgs(using Definitions): Int =
      if hasVararg then paramCount - 1 else paramCount

    def minimumPostArgs(using Definitions): Int =
      if hasVararg then postParamTypes.size - 1 else postParamTypes.size

    def hasVararg(using defn: Definitions): Boolean =
      paramCount > 0 && paramTypes.last.isVararg

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

    def resCount = if resultType.isValueType then 1 else 0


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
    (tctor: Symbol, targs: List[Type])
  extends ProxyType:
    assert(targs.nonEmpty, this)

  /** Represents upper and lower bounds of type parameters */
  case class TypeBound
    (lo: Type, hi: Type)
  extends Type

  /** TypeVars are local to a source file thus it may take a span */
  class TypeVar(name: String, val span: Span)(using context: TypeVars) extends Type:
    context.add(this)

    override def toString = "TypeVar(" + name + ")"

    def isInstantiated: Boolean = context.isInstantiated(this)

    def instantiated: Type = context.instantiated(this)

    def approx(isUp: Boolean): Type = context.approx(this, isUp)

    def checkSubtype(tp: Type)(using Definitions): List[Subtyping.Task] =
      context.isSubtype(this, tp)

    def checkSuptype(tp: Type)(using Definitions): List[Subtyping.Task] =
      context.isSuptype(this, tp)

  /** Represents the information of a namespace or section */
  class ContainerInfo(val nameTable: NameTable) extends Type:
    export nameTable.{ resolveType, resolveTerm, resolvePattern, resolveContainer }

  /** Represents the information of a class type
    *
    * @param methods all methods (including contructor)
    */
  class ClassInfo(
    val classSymbol: Symbol, val tparams: List[Symbol], val targs: List[Type],
    val self: Symbol, val fields: List[Symbol], val methods: List[Symbol],
    val directViews: List[Type])
  extends Type:
    assert(tparams.size == targs.size, "Mismatch, tparams = " + tparams + ", targs = " + targs)

    def name: String = classSymbol.name

    /** Return all methods including the constructor */
    def allMethods: List[Symbol] = methods

    def field(name: String): Symbol =
      fields.find(_.name == name) match
        case Some(sym) => sym
        case None => throw new Exception("No field " + name + " in class " + classSymbol)

    def constructor: Symbol =
      methods.find(_.name == Names.Constructor) match
        case Some(sym) => sym
        case None => throw new Exception("No constructor found in class " + classSymbol)

    def delegateViews: List[Symbol] =
      fields.filter(_.is(Flags.View))

    def memberSymbol(name: String): Symbol =
      getMemberSymbol(name) match
        case Some(sym) => sym
        case None => throw new Exception(s"No member named $name for $classSymbol")

    def getMemberSymbol(name: String): Option[Symbol] =
      fields.find(_.name == name) match
        case None => methods.find(_.name == name)
        case res => res

    def getTermMember(prefix: Type, name: String): Option[RefType] =
      getMemberSymbol(name).map: sym =>
        MemberRef(prefix, sym)

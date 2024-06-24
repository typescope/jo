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
    def isError: Boolean = this == Type.Error

    def isVoid: Boolean = this == Type.Void

    def isBottom: Boolean = this == Type.Bottom

    def isRecordType: Boolean =
      this.dealias.isInstanceOf[Type.Record]

    def isUnionType: Boolean =
      this.dealias.isInstanceOf[Type.Union]

    def isValueType: Boolean =
      this.dealias match
        case Type.Void | _: Type.Proc | _: Type.TypeLambda => false
        case _ => true

    def isProcType: Boolean = this.underlying.isInstanceOf[Type.Proc]

    def asRecordType: Type.Record = this.dealias.asInstanceOf[Type.Record]

    def asUnionType: Type.Union = this.dealias.asInstanceOf[Type.Union]

    def asTypeLambda: Type.TypeLambda = this.dealias.asInstanceOf[Type.TypeLambda]

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
    case class Record(fields: List[(String, Type)]) extends Type:
      val fieldNames: List[String] = fields.map(_._1)

      def getFieldType(field: String): Option[Type] =
        fields.collectFirst:
          case (f, tp) if f == field => tp

      def hasField(name: String): Boolean =
        fieldNames.contains(name)

      def fieldType(name: String): Type =
        getFieldType(name).get

      override def toString =
        "[" + fields.map(_ + ": " + _).mkString(", ") + "]"

    case class Union(branches: List[(String, Type)]) extends Type:
      val tags: List[String] = branches.map(_._1)

      def getTagType(tag: String): Option[Type] =
        branches.collectFirst:
          case (t, tp) if t == tag => tp

      def hasTag(tag: String): Boolean =
        tags.contains(tag)

      def tagType(tag: String): Type =
        getTagType(tag).get

      def tagIndex(tag: String): Int =
        branches.indexWhere:
          case (t, _) => t == tag

      override def toString =
        "<" + branches.map(_ + " " + _).mkString(", ") + ">"

    case class Proc
      (names: List[String], paramTypes: List[Type], resType: Type)
    extends Type:
      val paramCount = paramTypes.size
      val resCount = if resType.isValueType then 1 else 0

    /** A type lambda */
    case class TypeLambda
      (names: List[String], bounds: List[Type], body: Type)
    extends Type:
      val typeParamCount = names.size

      val typeParamRefs: List[TypeParamRef] =
        for i <- (0 until names.size).toList
        yield TypeParamRef(this, i)

    case class TypeParamRef
      (binder: TypeLambda, index: Int)
    extends Type:
      def bound: Type = binder.bounds(index)
      def name: String = binder.names(index)

    case class AppliedType
      (tctor: Type, targs: List[Type])
    extends Type:
      def reduce: Type =
        val typeLambda = tctor.asTypeLambda
        substTypeParams(typeLambda.body, targs)

    /** Delayed type for symbols to enable type inference and recursive types */
    case class Delayed
      ()
      (infoCompleter: => Type)
    extends Type:
      private var _underlying: Type = null

      private def complete(): Unit =
        assert(_underlying == null, "Double completing: " + _underlying)
        _underlying = infoCompleter

      def isComplete: Boolean = _underlying != null

      def take: Type =
        if !isComplete then complete()
        _underlying

      override def equals(that: Any): Boolean =
        if !isComplete then false
        else
          that match
            case tp: Delayed =>
              tp.isComplete && tp.underlying == this.underlying

            case _ =>
              false
          end match

      override def hashCode(): Int =
        if !isComplete then throw new Exception("Hashing incomplete type")
        else underlying.hashCode

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

  /** Check whether one type conforms to the other type.
    *
    * TODO: ensure termination for type lambdas.
    */
  private def checkConforms(tp1: Type, tp2: Type)(using ass: Assumptions): Boolean =
    tp1.isError
    || tp2.isError
    || tp1.isBottom
    || tp1 == tp2
    || tp1.is[Type.TypeRef] && tp2.is[Type.TypeRef]
       && checkConformsTypeRef(tp1.as[Type.TypeRef], tp2.as[Type.TypeRef])
    || tp1.is[Type.TypeRef] && tp1.dealias != tp1 && checkConforms(tp1.dealias, tp2)
    || tp2.is[Type.TypeRef] && tp2.dealias != tp2 && checkConforms(tp1, tp2.dealias)
    || tp1.is[Type.Delayed] && checkConforms(tp1.underlying, tp2)
    || tp2.is[Type.Delayed] && checkConforms(tp1, tp2.underlying)
    || tp1.is[Type.Record] && tp2.is[Type.Record]
       && checkConformsRecordType(tp1.as[Type.Record], tp2.as[Type.Record])
    || tp1.is[Type.AppliedType] && checkConforms(tp1.as[Type.TypeLambda], tp2)
    || tp2.is[Type.AppliedType] && checkConforms(tp1, tp2.as[Type.TypeLambda])

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
    names1.size >= names2.size && names1.zip(names2).forall: (a, b) =>
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

  /** Substitute type params with the given types */
  def substTypeParams(tpe: Type, to: List[Type]): Type =
    tpe match
      case Type.TypeParamRef(_, index) =>
        to(index)

      case Type.Void | Type.Error | Type.Bottom | Type.Int | Type.Bool =>
        tpe

      case _: Type.TypeRef =>
        tpe

      case Type.Record(fields) =>
        val fields2 =
          for (name, tpe) <- fields
          yield name -> substTypeParams(tpe, to)
        Type.Record(fields2)

      case Type.Union(branches) =>
        val branches2 =
          for (tag, tpe) <- branches
          yield tag -> substTypeParams(tpe, to)
        Type.Union(branches2)

      case Type.AppliedType(tctor, targs) =>
        // first-class type ctor might be supported later
        val tctor2 = substTypeParams(tctor, to)
        val targs2 = for targ <- targs yield substTypeParams(targ, to)
        Type.AppliedType(tctor2, targs2)

      case _: Type.TypeLambda =>
        // nested type lambdas not supported
        tpe

      case tp: Type.Proc =>
        tp

      case tp: Type.Delayed =>
        substTypeParams(tp.underlying, to)

  /** Replace type symbol reference with sym.info
    *
    * This method is used in type checking definitions with type parameters.
    */
  def eliminateSymbols(tpe: Type, syms: List[Symbol]): Type =
    tpe match
      case Type.Void | Type.Error | Type.Bottom | Type.Int | Type.Bool =>
        tpe

      case Type.TypeRef(sym) =>
        if syms.contains(sym) then sym.info
        else tpe

      case Type.Record(fields) =>
        val fields2 =
          for (name, tpe) <- fields
          yield name -> eliminateSymbols(tpe, syms)
        Type.Record(fields2)

      case Type.Union(branches) =>
        val branches2 =
          for (tag, tpe) <- branches
          yield tag -> eliminateSymbols(tpe, syms)
        Type.Union(branches2)

      case Type.AppliedType(tctor, targs) =>
        // first-class type ctor might be supported later
        val tctor2 = eliminateSymbols(tctor, syms)
        val targs2 = for targ <- targs yield eliminateSymbols(targ, syms)
        Type.AppliedType(tctor2, targs2)

      case _: Type.TypeLambda | _: Type.TypeParamRef =>
        // nested type lambdas not supported
        tpe

      case tp: Type.Proc =>
        tp

      case tp: Type.Delayed =>
        eliminateSymbols(tp.underlying, syms)

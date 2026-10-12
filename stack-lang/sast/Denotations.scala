package sast

import Types.*
import Symbols.Symbol
import ast.Positions.{ Positioned, Span }

/** The denotation of a symbol: what a symbol refers to or describes.
  *
  * Subtypes:
  *
  * - Type (expression/surface types),
  * - ClassInfo (class descriptor),
  * - TypeOperatorInfo (type alias / native type descriptor),
  * - TypeAlias (simple-kinded type alias / native type descriptor),
  * - TypeParamInfo (type parameter descriptor),
  * - NameTable (namespace descriptor).
  */
object Denotations:
  abstract class Denotation:
    def as[T <: Denotation]: T = asInstanceOf[T]

    def asType: Types.Type = asInstanceOf[Types.Type]

    def isType: Boolean = this.isInstanceOf[Types.Type]

  /** Represents the information of a class type
    *
    * @param methods all methods (including contructor)
    */
  class ClassInfo(
    val classSymbol: Symbol, val tparams: List[Symbol],
    val self: Symbol, fieldsInit: => List[Symbol], methodsInit: => List[Symbol],
    viewsInit: => List[Type])
  extends Denotation:
    lazy val fields: List[Symbol] = fieldsInit
    lazy val methods: List[Symbol] = methodsInit
    lazy val views: List[Type] = viewsInit

    def name: String = classSymbol.name

    /** Return all methods including the constructor */
    def allMethods: List[Symbol] = methods

    def field(name: String): Symbol =
      fields.find(_.name == name) match
        case Some(sym) => sym
        case None => throw new Exception("No field " + name + " in class " + classSymbol)

    def constructor: Symbol =
      methods.find(_.is(Flags.Constructor)) match
        case Some(sym) => sym
        case None => throw new Exception("No constructor found in class " + classSymbol)

    def memberSymbol(name: String)(using Definitions): Symbol =
      getMemberSymbol(name) match
        case Some(sym) => sym
        case None => throw new Exception(s"No member named $name for $classSymbol")

    def getMemberSymbol(name: String)(using Definitions): Option[Symbol] =
      fields.find(_.name == name) match
        case None =>
          methods.find(_.name == name) match
            case None =>
              val iter = views.iterator

              while iter.hasNext do
                val viewType = iter.next()
                viewType.classInfo.getMemberSymbol(name) match
                  case res @ Some(_) => return res
                  case None =>
                end match
              end while
              None

            case res => res

        case res => res

    def getTermMember(prefix: Type, name: String)(using Definitions): Option[RefType] =
      getMemberSymbol(name) match
        case Some(sym) => Some(MemberRef(prefix, sym))
        case None => None

  /** Descriptor for a simple-kinded type alias. */
  class TypeAlias(bodyInit: () => Type) extends Denotation:
    private val bodyLazy = new LazyValue(bodyInit)
    def body: Type = bodyLazy.value

  /** Descriptor for a type operator with nonempty type parameters.
    *
    * @param tparams       type parameters
    * @param body          alias body (a Type expression) or Any for abstract types
    * @param preParamCount number of pre-type-parameters (for infix type operators)
    */
  class TypeOperatorInfo(
    val tparams: List[Symbol], bodyInit: () => Type, val preParamCount: Int)
  extends Denotation:
    assert(tparams.nonEmpty, "TypeOperatorInfo requires type parameters")

    // Reentrant reads must reach the source provider's cycle detection.
    private val bodyLazy = new LazyValue(bodyInit)
    def body: Type = bodyLazy.value

    val names: List[String] = tparams.map(_.name)
    val paramCount: Int = tparams.size

    def postParamCount: Int = paramCount - preParamCount

    def instantiate(targs: List[Type])(using Definitions): Type =
      assert(tparams.size == targs.size, "expect " + tparams.size + ", found = " + targs.size)
      TypeOps.substSymbols(body, tparams, targs)

  /** The kind of a type parameter
    *
    * Bounds are intentionally not supported for the moment.
    */
  case class TypeParamInfo(kind: Kind) extends Denotation

  enum DefaultValue:
    case Lit(const: Constant)(val span: Span)
    case Ref(symbol: Symbol)(val span: Span)
    case None

  trait NamedType extends Denotation:
    val name: String
    val tpe: Type

  /** The denotation of a normal parameter
    *
    * The name field is duplicated such that ParamInfo can be used in ProcType directly
    */
  case class ParamInfo(name: String, tpe: Type, default: DefaultValue = DefaultValue.None) extends NamedType

  enum AutoCandidate extends Positioned:
    case Member(tpe: Type, name: String)(val span: Span)
    case Value(symbol: Symbol)(val span: Span)

    def show(using Definitions): String =
      this match
        case Value(sym) => sym.name
        case Member(tpt, name) => "[" + tpt.show + "]." + name

  /** The denotation of an auto parameter
    *
    * The name field is duplicated such that AutoInfo can be used in ProcType directly
    */
  case class AutoInfo(name: String, tpe: Type, candidates: List[AutoCandidate]) extends NamedType

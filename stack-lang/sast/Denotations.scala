package sast

import Types.*
import Symbols.Symbol

/** The denotation of a symbol: what a symbol refers to or describes.
  *
  * Subtypes:
  *
  * - Type (expression/surface types),
  * - ClassInfo (class descriptor),
  * - TypeOperatorInfo (parameterized type alias / native type descriptor),
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
    val self: Symbol, val fields: List[Symbol], val methods: List[Symbol],
    val views: List[Type])
  extends Denotation:

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

  /** Descriptor for a parameterized type operator: a type alias or native type with type parameters.
    *
    * @param tparams       type parameters
    * @param body          alias body (a Type expression) or Any for abstract types
    * @param preParamCount number of pre-type-parameters (for infix type operators)
    */
  case class TypeOperatorInfo(tparams: List[Symbol], body: Type, preParamCount: Int) extends Denotation:
    val names: List[String] = tparams.map(_.name)
    val paramCount: Int = tparams.size

    def postParamCount: Int = paramCount - preParamCount

    def instantiate(targs: List[Type])(using Definitions): Type =
      assert(tparams.size == targs.size, "expect " + tparams.size + ", found = " + targs.size)
      TypeOps.substSymbols(body, tparams, targs)

package sast

import Types.*
import Symbols.Symbol

/** The denotation of a symbol: what a symbol refers to or describes.
  *
  * Subtypes:
  *
  * - Type (expression/surface types),
  * - ClassInfo (class descriptor),
  * - TypeLambda (generic class/alias descriptor),
  * - NameTable (namespace descriptor).
  */
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
  val directViews: List[Type])
  (extensionsFun: () => List[Symbol])
extends Denotation:
  lazy val extensions: List[Symbol] = extensionsFun()

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
      case None =>
        methods.find(_.name == name) match
          case None => extensions.find(_.name == name)
          case res => res
      case res => res

  def getTermMember(prefix: Type, name: String)(using Definitions): Option[RefType] =
    getMemberSymbol(name).map: sym =>
      if sym.isExtensionMethod then StaticRef(sym)
      else MemberRef(prefix, sym)

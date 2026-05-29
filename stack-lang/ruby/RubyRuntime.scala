package ruby

import sast.*
import sast.Symbols.Symbol
import sast.Symbols.Annotation

import scala.collection.mutable

object RubyRuntime:
  def isValidMethodName(name: String): Boolean =
    if name.isEmpty then false
    else
      val base = if name.endsWith("?") || name.endsWith("!") then name.dropRight(1) else name
      if base.isEmpty then false
      else
        val first = base.charAt(0)
        (first.isLetter || first == '_') &&
          base.forall(c => c.isLetterOrDigit || c == '_')

/** Functions to support Ruby platform at runtime
  *
  * Run-time symbols are only available to the compiler.
  */
class RubyRuntime(using defn: Definitions):
  // Map from context parameter fullName to unique global variable name
  val paramIds: mutable.Map[String, String] = mutable.Map.empty

  // Set of required Ruby libraries (emitted as `require "name"` at top of file)
  val requiredLibs: mutable.LinkedHashSet[String] = mutable.LinkedHashSet.empty

  val runtimeNames = List("puts", "print", "ARGV")

  /** Get or create a unique global name for a context parameter */
  def getOrCreateParamId(sym: Symbol): String =
    paramIds.getOrElseUpdate(sym.fullName, {
      // Generate unique global name: $param_jo_IO_stdout
      val safeName = sym.fullName.replace('.', '_')
      s"$$param_$safeName"
    })

  val Ruby = defn.resolveContainer("jo.rb.runtime")

  val ParamSupport = Ruby.containerMember("ParamSupport")

  val emptyCtx    = ParamSupport.termMember("emptyCtx")
  val getParam    = ParamSupport.termMember("getParam")
  val startBatch  = ParamSupport.termMember("startBatch")
  val addBinding  = ParamSupport.termMember("addBinding")
  val finishBatch = ParamSupport.termMember("finish")
  val paramKey    = ParamSupport.termMember("paramKey")

  val start  = Ruby.termMember("start")
  val rb_raw = Ruby.termMember("rbRaw")

  val StringOps      = Ruby.containerMember("StringOps")
  val String_iterator = StringOps.termMember("iterator")

  // rb.* FFI API symbols
  val rb = defn.resolveContainer("jo.rb")

  val rb_nil      = rb.termMember("nil")
  val rb_dynamic  = rb.termMember("dynamic")
  val rb_const    = rb.termMember("const")
  val rb_array    = rb.termMember("array")
  val rb_require  = rb.termMember("require")
  val rb_isNil    = rb.termMember("isNil")
  val rb_isIdentical = rb.termMember("isIdentical")
  val rb_try      = rb.termMember("try")

  val rb_Dynamic               = rb.typeMember("Dynamic")
  val rb_Dynamic_selectDynamic = rb_Dynamic.termMember("selectDynamic")
  val rb_Dynamic_updateDynamic = rb_Dynamic.termMember("updateDynamic")
  val rb_Dynamic_callDynamic   = rb_Dynamic.termMember("callDynamic")
  val rb_Dynamic_init          = rb_Dynamic.termMember("init")
  val rb_Dynamic_getDynamic    = rb_Dynamic.termMember("getDynamic")
  val rb_Dynamic_setDynamic    = rb_Dynamic.termMember("setDynamic")
  val rb_Dynamic_cast          = rb_Dynamic.termMember("cast")
  val annot_interop          = rb.annotationMember("interop")
  val annot_targetName       = rb.annotationMember("targetName")
  val annot_keyword          = rb.annotationMember("keyword")
  val annot_positional       = rb.annotationMember("positional")

  // Result variant class symbols (from jo stdlib)
  val Jo     = defn.resolveContainer("jo")
  val jo_Ok  = Jo.typeMember("Ok")
  val jo_Err = Jo.typeMember("Err")

  // Symbols injected by the code generator that do not appear in the SAST.
  // rb.try injects Ok.new(...)/Err.new(...) at call sites — no SAST New node exists,
  // so the constructors must be declared as roots explicitly.
  def extraRoots: List[Symbol] =
    List(jo_Ok, jo_Err,
         jo_Ok.termMember(Names.Constructor),
         jo_Err.termMember(Names.Constructor))

  def intrinsicRewire: Map[Symbol, Symbol] =
    val strSym = defn.String_type
    Map(strSym.termMember("iterator") -> String_iterator)

  def rbTargetName(sym: Symbol): Option[String] =
    sym.annotation(annot_targetName).map:
      case Annotation(_, List(Constant.String(name))) => name
      case _ => throw new Exception(s"Unexpected @rb.targetName payload on ${sym.fullName}")

  def isKeywordType(tpe: Types.Type): Boolean =
    tpe.getAnnotation(annot_keyword).isDefined

  def isPositionalType(tpe: Types.Type): Boolean =
    tpe.getAnnotation(annot_positional).isDefined

  def keywordRename(tpe: Types.Type): Option[String] =
    tpe.getAnnotation(annot_keyword).flatMap:
      case Annotation(_, List(Constant.String(name))) if name.nonEmpty => Some(name)
      case _ => None

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

  val start = Ruby.termMember("start")

  val StringOps      = Ruby.containerMember("StringOps")
  val String_iterator = StringOps.termMember("iterator")

  // rb.* FFI API symbols
  val rb = defn.resolveContainer("jo.rb")

  val rb_nil   = rb.termMember("nil")
  val rb_value = rb.termMember("value")
  val rb_const = rb.termMember("const")
  val rb_require = rb.termMember("require")
  val rb_isNil  = rb.termMember("isNil")
  val rb_isSame = rb.termMember("isSame")
  val rb_try    = rb.termMember("try")

  val rb_Value             = rb.typeMember("Value")
  val rb_Value_selectDynamic = rb_Value.termMember("selectDynamic")
  val rb_Value_updateDynamic = rb_Value.termMember("updateDynamic")
  val rb_Value_callDynamic   = rb_Value.termMember("callDynamic")
  val rb_Value_getDynamic    = rb_Value.termMember("getDynamic")
  val rb_Value_setDynamic    = rb_Value.termMember("setDynamic")
  val rb_Value_cast          = rb_Value.termMember("cast")
  val annot_targetName       = rb.annotationMember("targetName")

  // Result variant class symbols (from jo stdlib)
  val Jo     = defn.resolveContainer("jo")
  val jo_Ok  = Jo.typeMember("Ok")
  val jo_Err = Jo.typeMember("Err")

  def rbTargetName(sym: Symbol): Option[String] =
    sym.annotation(annot_targetName).map:
      case Annotation(_, List(Constant.String(name))) => name
      case _ => throw new Exception(s"Unexpected @rb.targetName payload on ${sym.fullName}")

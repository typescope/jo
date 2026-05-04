package js

import sast.*
import sast.Symbols.Symbol
import sast.Symbols.Annotation

import scala.collection.mutable

object JSRuntime:
  def isValidIdentifier(name: String): Boolean =
    name.nonEmpty
    && (name.head.isLetter || name.head == '_' || name.head == '$')
    && name.tail.forall(c => c.isLetterOrDigit || c == '_' || c == '$')

/** Functions to support JS platform at runtime
  *
  * Run-time symbols are only available to the compiler.
  */
class JSRuntime(using defn: Definitions):
  // Map from context parameter fullName to unique global variable name
  val paramIds: mutable.Map[String, String] = mutable.Map.empty

  val runtimeNames = List("console", "process", "String")

  /** Get or create a unique global name for a context parameter */
  def getOrCreateParamId(sym: Symbol): String =
    paramIds.getOrElseUpdate(sym.fullName, {
      // Generate unique global name: __param_jo_IO_stdout
      val safeName = sym.fullName.replace('.', '_')
      s"__param_$safeName"
    })

  val JS = defn.resolveContainer("jo.js.runtime")

  val ParamSupport = JS.containerMember("ParamSupport")
  val paramKey = ParamSupport.termMember("paramKey")
  val emptyCtx = ParamSupport.termMember("emptyCtx")
  val getParam = ParamSupport.termMember("getParam")
  val startBatch = ParamSupport.termMember("startBatch")
  val addBinding = ParamSupport.termMember("addBinding")
  val finishBatch = ParamSupport.termMember("finish")

  val js_abort   = JS.termMember("abort")

  val start    = JS.termMember("start")

  val StringOps = JS.containerMember("StringOps")
  val String_size = StringOps.termMember("size")
  val String_get = StringOps.termMember("get")
  val String_substring = StringOps.termMember("substring")
  val String_indexOf = StringOps.termMember("indexOf")
  val String_iterator = StringOps.termMember("iterator")

  // jo.js FFI symbols
  val jo_js = defn.resolveContainer("jo.js")

  val js_Dynamic              = jo_js.typeMember("Dynamic")
  val js_Dynamic_selectDynamic = js_Dynamic.termMember("selectDynamic")
  val js_Dynamic_updateDynamic = js_Dynamic.termMember("updateDynamic")
  val js_Dynamic_callDynamic   = js_Dynamic.termMember("callDynamic")
  val js_Dynamic_getDynamic    = js_Dynamic.termMember("getDynamic")
  val js_Dynamic_setDynamic    = js_Dynamic.termMember("setDynamic")
  val js_Dynamic_call          = js_Dynamic.termMember("call")
  val js_Dynamic_cast          = js_Dynamic.termMember("cast")
  val js_Dynamic_isSame        = js_Dynamic.termMember("===")
  val js_Dynamic_isInstance    = js_Dynamic.termMember("isInstance")
  val js_Dynamic_isUndefined   = js_Dynamic.termMember("isUndefined")
  val js_Dynamic_isNull        = js_Dynamic.termMember("isNull")
  val js_Dynamic_isNullish     = js_Dynamic.termMember("isNullish")
  val annot_interop          = jo_js.annotationMember("interop")
  val annot_targetName       = jo_js.annotationMember("targetName")
  val annot_property         = jo_js.annotationMember("property")

  val js_dynamic     = jo_js.termMember("dynamic")
  val js_require     = jo_js.termMember("require")
  val js_global      = jo_js.termMember("global")
  val js_undefined   = jo_js.termMember("undefined")
  val js_null        = jo_js.termMember("null")
  val js_isUndefined = jo_js.termMember("isUndefined")
  val js_isNull      = jo_js.termMember("isNull")
  val js_isNullish   = jo_js.termMember("isNullish")
  val js_isInstance  = jo_js.termMember("isInstance")
  val js_typeof      = jo_js.termMember("typeof")
  val js_hasOwn      = jo_js.termMember("hasOwn")
  val js_spread      = jo_js.termMember("spread")
  val js_try         = jo_js.termMember("try")
  val js_init        = jo_js.termMember("init")
  val js_array       = jo_js.termMember("array")

  def jsTargetName(sym: Symbol): Option[String] =
    sym.annotation(annot_targetName).map:
      case Annotation(_, List(Constant.String(name))) => name
      case _ => throw new Exception(s"Unexpected @js.targetName payload on ${sym.fullName}")

  // Result variant class symbols (from jo stdlib)
  val Jo    = defn.resolveContainer("jo")
  val jo_Ok = Jo.typeMember("Ok")
  val jo_Err = Jo.typeMember("Err")

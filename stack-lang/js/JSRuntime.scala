package js

import sast.*
import sast.Symbols.Symbol

import scala.collection.mutable

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

  val js_escape  = JS.termMember("javascript")
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

  val js_Value             = jo_js.typeMember("Value")
  val js_Value_selectDynamic = js_Value.termMember("selectDynamic")
  val js_Value_updateDynamic = js_Value.termMember("updateDynamic")
  val js_Value_callDynamic   = js_Value.termMember("callDynamic")
  val js_Value_getDynamic    = js_Value.termMember("getDynamic")
  val js_Value_setDynamic    = js_Value.termMember("setDynamic")
  val js_Value_call          = js_Value.termMember("call")
  val js_Value_cast          = js_Value.termMember("cast")
  val js_Value_isSame        = js_Value.termMember("===")
  val js_Value_isInstance    = js_Value.termMember("isInstance")
  val js_Value_isUndefined   = js_Value.termMember("isUndefined")
  val js_Value_isNull        = js_Value.termMember("isNull")
  val js_Value_isNullish     = js_Value.termMember("isNullish")

  val js_value       = jo_js.termMember("value")
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
  val js_construct   = jo_js.termMember("construct")
  val js_array       = jo_js.termMember("array")
  val js_obj         = jo_js.termMember("obj")

  // Result variant class symbols (from jo stdlib)
  val Jo    = defn.resolveContainer("jo")
  val jo_Ok = Jo.typeMember("Ok")
  val jo_Err = Jo.typeMember("Err")

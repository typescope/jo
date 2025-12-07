package js

import sast.*
import sast.Symbols.Universe

/** Functions to support JS platform at runtime
  *
  * Run-time symbols are only available to the compiler.
  */
class JSRuntime(runtimeRootNameTable: NameTable)(using Definitions):
  private val paramsName = "__runtime_contextParams"

  val runtimeNames = List("console", "process", paramsName, "String")

  val globalDefCode: String = s"""var $paramsName = {};"""

  val JS = Definitions.resolveStatic(runtimeRootNameTable, "jo.runtime.JS", Universe.Container).head
  val JS_getParam = JS.termMember("getParam")
  val JS_setParam = JS.termMember("setParam")
  val JS_hasParam = JS.termMember("hasParam")
  val JS_delParam = JS.termMember("delParam")

  val JS_js =  JS.termMember("js")

  val JS_start    = JS.termMember("start")

  val JS_Array_createBool = JS.termMember("Array_createBool")
  val JS_Array_createInt = JS.termMember("Array_createInt")
  val JS_Array_createObject = JS.termMember("Array_createObject")

  val JS_String_size = JS.termMember("String_size")
  val JS_String_apply = JS.termMember("String_apply")
  val JS_String_substring = JS.termMember("String_substring")
  val JS_String_plus = JS.termMember("String_plus")
  val JS_String_equals = JS.termMember("String_equals")

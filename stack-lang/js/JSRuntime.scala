package js

import sast.*

/** Functions to support JS platform at runtime
  *
  * Run-time symbols are only available to the compiler.
  */
class JSRuntime(runtimeRootNameTable: NameTable):
  import runtimeRootNameTable.resolvePath

  private val paramsName = "__runtime_contextParams"

  val runtimeNames = List("console", "process", paramsName)

  val globalDefCode: String = s"""const $paramsName = {};"""

  val JS = resolvePath("stk.runtime.JS")
  val JS_getParam = JS.termMember("getParam")
  val JS_setParam = JS.termMember("setParam")
  val JS_hasParam = JS.termMember("hasParam")

  val JS_print = JS.termMember("print")
  val JS_p = JS.termMember("p")

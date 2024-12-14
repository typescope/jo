package js

import sast.*
import sast.Symbols.*

/** Functions to support JS platform at runtime
  *
  * Run-time symbols are only available to the compiler.
  */
class JSRuntime(val runtimeRootNameTable: NameTable):
  def resolveNamespace(path: String): Symbol =
    NameTable.resolvePath(runtimeRootNameTable, path, isType = false)

  private val paramsName = "__runtime_contextParams"

  val runtimeNames: List[String] = List(
    "console", "process", paramsName
  )

  val globalDefCode: String = s"""const $paramsName = {};"""

  val JS = resolveNamespace("stk.runtime.JS")
  val JS_getParam = JS.termMember("getParam")
  val JS_setParam = JS.termMember("setParam")

  val JS_print = JS.termMember("print")
  val JS_p = JS.termMember("p")

package js

import sast.*
import sast.Symbols.*

import common.Dynamic

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

object JSRuntime:
  val key = new Dynamic.Key[JSRuntime]("js-runtime")

  def initialize(runtimeRootNameTable: NameTable): Unit =
    val jsRuntime = new JSRuntime(runtimeRootNameTable)
    Dynamic.install(JSRuntime.key, jsRuntime)

  def instance: JSRuntime = Dynamic.get(JSRuntime.key)

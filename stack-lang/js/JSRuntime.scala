package js

import sast.*
import sast.Symbols.*

import common.Dynamic

/** Functions to support JS platform at runtime
  *
  * Run-time symbols are only available to the compiler.
  */
class JSRuntime(val runtimeRootNameTable: NameTable):
  def resolveTerm(path: String): Symbol =
    NameTable.resolvePath(runtimeRootNameTable, path, isType = false)

  def resolveType(path: String): Symbol =
    NameTable.resolvePath(runtimeRootNameTable, path, isType = true)

  private val paramsName = "__runtime_contextParams"

  val runtimeNames: List[String] = List(
    "console", "process", paramsName
  )

  val globalDefCode: String = s"""const $paramsName = {};"""

  val getParam = resolveTerm("stk.runtime.JS.getParam")
  val setParam = resolveTerm("stk.runtime.JS.setParam")

  val print = resolveTerm("stk.runtime.JS.print")
  val p = resolveTerm("stk.runtime.JS.p")

object JSRuntime:
  val key = new Dynamic.Key[JSRuntime]("js-runtime")

  def initialize(runtimeRootNameTable: NameTable): Unit =
    val jsRuntime = new JSRuntime(runtimeRootNameTable)
    Dynamic.install(JSRuntime.key, jsRuntime)

  def instance: JSRuntime = Dynamic.get(JSRuntime.key)

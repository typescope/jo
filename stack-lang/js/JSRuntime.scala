package js

import sast.*

/** Functions to support JS platform at runtime
  *
  * Run-time symbols are only available to the compiler.
  */
class JSRuntime(using defn: Definitions):
  private val paramsName = "__runtime_contextParams"

  val runtimeNames = List("console", "process", paramsName, "String")

  val globalDefCode: String = s"""var $paramsName = {};"""

  val JS = defn.resolveContainer("jo.runtime.JS")
  val getParam = JS.termMember("getParam")
  val setParam = JS.termMember("setParam")
  val hasParam = JS.termMember("hasParam")
  val delParam = JS.termMember("delParam")

  val js =  JS.termMember("js")

  val start    = JS.termMember("start")

  val StringOps = JS.containerMember("StringOps")

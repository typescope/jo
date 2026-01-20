package js

import sast.*

import scala.collection.mutable

/** Functions to support JS platform at runtime
  *
  * Run-time symbols are only available to the compiler.
  */
class JSRuntime(using defn: Definitions):
  private val paramsName = "__runtime_contextParams"

  // Map from context parameter fullName to unique global variable name
  val paramIds: mutable.Map[String, String] = mutable.Map.empty

  val runtimeNames = List("console", "process", paramsName, "String")

  val globalDefCode: String = s"""var $paramsName = {};"""

  /** Get or create a unique global name for a context parameter */
  def getOrCreateParamId(fullName: String): String =
    paramIds.getOrElseUpdate(fullName, {
      // Generate unique global name: __param_jo_IO_stdout
      val safeName = fullName.replace('.', '_').replace("$", "D")
      s"__param_$safeName"
    })

  val JS = defn.resolveContainer("jo.runtime.JS")
  val paramSymbol = JS.termMember("paramSymbol")
  val getParam = JS.termMember("getParam")
  val setParam = JS.termMember("setParam")
  val hasParam = JS.termMember("hasParam")
  val delParam = JS.termMember("delParam")

  val js =  JS.termMember("js")

  val start    = JS.termMember("start")

  val StringOps = JS.containerMember("StringOps")

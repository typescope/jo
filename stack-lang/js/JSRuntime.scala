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

  val JS = defn.resolveContainer("js")

  val ParamSupport = JS.containerMember("ParamSupport")
  val paramKey = ParamSupport.termMember("paramKey")
  val emptyCtx = ParamSupport.termMember("emptyCtx")
  val getParam = ParamSupport.termMember("getParam")
  val startBatch = ParamSupport.termMember("startBatch")
  val addBinding = ParamSupport.termMember("addBinding")
  val finishBatch = ParamSupport.termMember("finish")

  val js =  JS.termMember("javascript")

  val start    = JS.termMember("start")

  val StringOps = JS.containerMember("StringOps")
  val String_size = StringOps.termMember("size")
  val String_get = StringOps.termMember("get")
  val String_substring = StringOps.termMember("substring")
  val String_indexOf = StringOps.termMember("indexOf")
  val String_iterator = StringOps.termMember("iterator")

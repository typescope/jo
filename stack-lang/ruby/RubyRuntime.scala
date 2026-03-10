package ruby

import sast.*
import sast.Symbols.Symbol

import scala.collection.mutable

/** Functions to support Ruby platform at runtime
  *
  * Run-time symbols are only available to the compiler.
  */
class RubyRuntime(using defn: Definitions):
  private val paramsName = "$runtime_contextParams"

  // Map from context parameter fullName to unique global variable name
  val paramIds: mutable.Map[String, String] = mutable.Map.empty

  val runtimeNames = List("puts", "print", "ARGV", paramsName)

  /** Get or create a unique global name for a context parameter */
  def getOrCreateParamId(sym: Symbol): String =
    paramIds.getOrElseUpdate(sym.fullName, {
      // Generate unique global name: $param_jo_IO_stdout
      val safeName = sym.fullName.replace('.', '_')
      s"$$param_$safeName"
    })

  val Ruby = defn.resolveContainer("rb")

  val ParamSupport = Ruby.containerMember("ParamSupport")

  val emptyCtx = ParamSupport.termMember("emptyCtx")
  val getParam = ParamSupport.termMember("getParam")
  val bindParam = ParamSupport.termMember("bindParam")
  val paramKey = ParamSupport.termMember("paramKey")

  val ruby = Ruby.termMember("ruby")

  val start = Ruby.termMember("start")

  val StringOps = Ruby.containerMember("StringOps")
  val String_iterator = StringOps.termMember("iterator")

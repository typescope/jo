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

  val Ruby = defn.resolveContainer("jo.runtime.Ruby")
  val getParam = Ruby.termMember("getParam")
  val setParam = Ruby.termMember("setParam")
  val hasParam = Ruby.termMember("hasParam")
  val delParam = Ruby.termMember("delParam")

  val ruby = Ruby.termMember("ruby")
  val paramKey = Ruby.termMember("paramKey")

  val start = Ruby.termMember("start")

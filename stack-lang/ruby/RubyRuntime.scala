package ruby

import sast.*

/** Functions to support Ruby platform at runtime
  *
  * Run-time symbols are only available to the compiler.
  */
class RubyRuntime(using defn: Definitions):
  private val paramsName = "$runtime_contextParams"

  val runtimeNames = List("puts", "print", "ARGV", paramsName)

  val globalDefCode: String = s"""$paramsName = {}"""

  val Ruby = defn.resolveContainer("jo.runtime.Ruby")
  val getParam = Ruby.termMember("getParam")
  val setParam = Ruby.termMember("setParam")
  val hasParam = Ruby.termMember("hasParam")
  val delParam = Ruby.termMember("delParam")

  val ruby = Ruby.termMember("ruby")

  val start = Ruby.termMember("start")

  val StringOps = Ruby.containerMember("StringOps")

  val Console = Ruby.containerMember("Console")

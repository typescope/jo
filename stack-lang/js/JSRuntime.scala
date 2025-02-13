package js

import sast.*

/** Functions to support JS platform at runtime
  *
  * Run-time symbols are only available to the compiler.
  */
class JSRuntime(runtimeRootNameTable: NameTable):
  import runtimeRootNameTable.resolvePath

  private val paramsName = "__runtime_contextParams"

  val runtimeNames = List("console", "process", paramsName, "String")

  val globalDefCode: String = s"""var $paramsName = {};"""

  val JS = resolvePath("stk.runtime.JS")
  val JS_getParam = JS.termMember("getParam")
  val JS_setParam = JS.termMember("setParam")
  val JS_hasParam = JS.termMember("hasParam")
  val JS_delParam = JS.termMember("delParam")

  val JS_cast = JS.termMember("cast")
  val JS_byteToChar = JS.termMember("byteToChar")
  val JS_byteToInt = JS.termMember("byteToInt")
  val JS_charToByte = JS.termMember("charToByte")
  val JS_charToInt = JS.termMember("charToInt")
  val JS_intToByte = JS.termMember("intToByte")
  val JS_intToChar = JS.termMember("intToChar")

  val JS_Array_createBool = JS.termMember("Array_createBool")
  val JS_Array_createInt = JS.termMember("Array_createInt")
  val JS_Array_createObject = JS.termMember("Array_createObject")

  val JS_String_length = JS.termMember("String_length")
  val JS_String_apply = JS.termMember("String_apply")
  val JS_String_substring = JS.termMember("String_substring")
  val JS_String_plus = JS.termMember("String_plus")

  val JS_abort = JS.termMember("abort")
  val JS_print = JS.termMember("print")
  val JS_printChar = JS.termMember("printChar")
  val JS_p = JS.termMember("p")

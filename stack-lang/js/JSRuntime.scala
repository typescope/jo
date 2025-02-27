package js

import sast.*
import Symbols.Symbol

/** Functions to support JS platform at runtime
  *
  * Run-time symbols are only available to the compiler.
  */
class JSRuntime(runtimeRootNameTable: NameTable, main: Symbol):
  import runtimeRootNameTable.resolvePath

  private val paramsName = "__runtime_contextParams"

  val runtimeNames = List("console", "process", paramsName, "String")

  val globalDefCode: String = s"""var $paramsName = {};"""

  val JS = resolvePath("stk.runtime.JS")
  val JS_getParam = JS.termMember("getParam")
  val JS_setParam = JS.termMember("setParam")
  val JS_hasParam = JS.termMember("hasParam")
  val JS_delParam = JS.termMember("delParam")

  val JS_js =  JS.termMember("js")

  val JS_start    = JS.termMember("start")
  val JS_mainStub = JS.termMember("mainStub")

  val JS_cast = JS.termMember("cast")
  val JS_byteToChar = JS.termMember("byteToChar")
  val JS_byteToInt = JS.termMember("byteToInt")
  val JS_charToByte = JS.termMember("charToByte")
  val JS_charToInt = JS.termMember("charToInt")
  val JS_charToStr = JS.termMember("charToStr")
  val JS_intToByte = JS.termMember("intToByte")
  val JS_intToChar = JS.termMember("intToChar")
  val JS_intToStr = JS.termMember("intToStr")

  val JS_Array_createBool = JS.termMember("Array_createBool")
  val JS_Array_createInt = JS.termMember("Array_createInt")
  val JS_Array_createObject = JS.termMember("Array_createObject")

  val JS_String_length = JS.termMember("String_length")
  val JS_String_apply = JS.termMember("String_apply")
  val JS_String_substring = JS.termMember("String_substring")
  val JS_String_plus = JS.termMember("String_plus")

  val JS_openFile = JS.termMember("openFile")
  val JS_createStdIn = JS.termMember("createStdIn")
  val JS_createStdOut = JS.termMember("createStdOut")
  val JS_createStdErr = JS.termMember("createStdErr")

  val JS_abort = JS.termMember("abort")

  def link(sym: Symbol): Option[Symbol] =
    if sym == JS_mainStub then Some(main) else None

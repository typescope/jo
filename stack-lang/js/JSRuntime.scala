package js

import sast.*
import sast.Symbols.*
import sast.Types.*

/** Functions to support JS platform at runtime */
object JSRuntime:
  //----------------------------------------------------------------------------
  // run-time symbols are only available to the compiler

  // read context parameter
  val getParam =
    val tpe = ProcType(NamedInfo("key", StringType) :: Nil, AnyType, preParamCount = 0)
    new Symbol("readParam", tpe, Flags.Fun, owner = Predef.predefSym, sourcePos = null)

  // define new context parameter
  // set the new value, return the existing value associated with the same key
  val setParam =
    val key = NamedInfo("key", StringType)
    val value = NamedInfo("value", AnyType)
    val tpe = ProcType(key :: value :: Nil, AnyType, preParamCount = 0)
    new Symbol("pushParam", tpe, Flags.Fun, owner = Predef.predefSym, sourcePos = null)

  private val getParamRuntimeName = "__runtime_getParam"
  private val setParamRuntimeName = "__runtime_setParam"

  val runtimeSymbolMap: Map[Symbol, String] = Map(
    Predef.p     -> "console.log",
    Predef.print -> "process.stdout.write",
    getParam    -> getParamRuntimeName,
    setParam    -> setParamRuntimeName,
  )

  private val paramsName = "__runtime_contextParams"

  val runtimeCode: String = s"""
const $paramsName = {};

function $getParamRuntimeName(key) {
  const v = $paramsName[key];
  if (v) {
     return v;
  } else {
     throw "Unbound parameter " + key;
  }
}

function $setParamRuntimeName(key, value) {
  const old = $paramsName[key];
  $paramsName[key] = value;
  return old;
}
"""

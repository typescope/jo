package js

import sast.*
import sast.Symbols.*
import sast.Types.*

/** Functions to support JS platform at runtime */
object JSRuntime:
  //----------------------------------------------------------------------------
  // run-time symbols are only available to the compiler

  // read context parameter
  val readParam =
    val tpe = ProcType(NamedInfo("key", StringType) :: Nil, AnyType, preParamCount = 0)
    new Symbol("readParam", tpe, Flags.Prim, owner = Predef.predefSym, sourcePos = null)

  // define context parameters
  // def withParam(keyValues: Any, callback: () => Any): Any
  val withParam =
    val keyValuesParam = NamedInfo("keyValues", AnyType)
    val callbackParam = NamedInfo("callback", FunctionType(paramTypes = Nil, AnyType))
    val tpe = ProcType( keyValuesParam :: callbackParam :: Nil, AnyType, preParamCount = 0)
    new Symbol("withParam", tpe, Flags.Prim, owner = Predef.predefSym, sourcePos = null)

  private val readParamRuntimeName = "__runtime_readParam"
  private val withParamRuntimeName = "__runtime_withParam"

  val runtimeSymbolMap: Map[Symbol, String] = Map(
    Predef.p     -> "console.log",
    Predef.print -> "process.stdout.write",
    readParam    -> readParamRuntimeName,
    withParam    -> withParamRuntimeName
  )

  private val paramsName = "__runtime_contextParams"

  val runtimeCode: String = s"""
var $paramsName = {};

function $readParamRuntimeName(key) {
  if (key in $paramsName) {
     return $paramsName[key];
  } else {
     throw "Unbound parameter " + key;
  }
}

function $withParamRuntimeName(keyValues, callback) {
  const last = $paramsName;
  $paramsName = Object.create(last);
  for (let key in keyValues) $paramsName[key] = keyValues[key];
  const res = callback();
  $paramsName = last;
  return res;
}
"""

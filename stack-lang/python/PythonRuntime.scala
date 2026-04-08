package python

import sast.*
import sast.Symbols.Symbol

import scala.collection.mutable

/** Functions to support Python platform at runtime
  *
  * Run-time symbols are only available to the compiler.
  */
class PythonRuntime(using defn: Definitions):
  // Map from context parameter fullName to unique global variable name
  val paramIds: mutable.Map[String, String] = mutable.Map.empty

  // Map from singleton object symbol to unique global variable name
  val singletonIds: mutable.Map[Symbol, String] = mutable.Map.empty

  val runtimeNames = List("print", "sys")

  /** Get or create a unique global name for a context parameter */
  def getOrCreateParamId(sym: Symbol): String =
    paramIds.getOrElseUpdate(sym.fullName, {
      // Generate unique global name: _param_jo_IO_stdout
      val safeName = sym.fullName.replace('.', '_')
      s"_param_$safeName"
    })

  /** Get or create a unique global name for a singleton object */
  def getOrCreateSingletonId(sym: Symbol): String =
    singletonIds.getOrElseUpdate(sym, {
      // Generate unique global name: _singleton_jo_Predef_Unit
      val safeName = sym.fullName.replace('.', '_').replace("$", "D")
      s"_singleton_$safeName"
    })

  val Python = defn.resolveContainer("jo.runtime.py")

  val ParamSupport = Python.containerMember("ParamSupport")
  val emptyCtx = ParamSupport.termMember("emptyCtx")
  val getParam = ParamSupport.termMember("getParam")
  val startBatch = ParamSupport.termMember("startBatch")
  val addBinding = ParamSupport.termMember("addBinding")
  val finishBatch = ParamSupport.termMember("finish")
  val paramKey = ParamSupport.termMember("paramKey")

  val python = Python.termMember("python")
  val start = Python.termMember("start")

  val StringOps = Python.containerMember("StringOps")
  val String_iterator = StringOps.termMember("iterator")

  val FFI = defn.resolveContainer("jo.runtime.py.ffi")

  // Public API functions
  val ffi_importModule = FFI.termMember("importModule")
  val ffi_try          = FFI.termMember("try")
  val ffi_Error        = FFI.typeMember("Error")
  val ffi_splice       = FFI.termMember("splice")
  val ffi_kwarg        = FFI.termMember("kwarg")
  val ffi_kwargs       = FFI.termMember("kwargs")

  // ValueOps extension methods (all intrinsified)
  val ffi_ValueOps          = FFI.containerMember("ValueOps")
  val ffi_value_call        = ffi_ValueOps.termMember("call")
  val ffi_value_field       = ffi_ValueOps.termMember("field")
  val ffi_value_setField    = ffi_ValueOps.termMember("setField")
  val ffi_value_get         = ffi_ValueOps.termMember("get")
  val ffi_value_set         = ffi_ValueOps.termMember("set")
  val ffi_value_isNone      = ffi_ValueOps.termMember("isNone")
  val ffi_value_cast        = ffi_ValueOps.termMember("cast")
  val ffi_value_isInstance  = ffi_ValueOps.termMember("isInstance")


  // Result variant class symbols (from jo stdlib)
  val Jo       = defn.resolveContainer("jo")
  val jo_Ok    = Jo.typeMember("Ok")
  val jo_Err   = Jo.typeMember("Err")

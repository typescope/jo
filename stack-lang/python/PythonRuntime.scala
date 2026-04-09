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

  val Python = defn.resolveContainer("jo.runtime.python")

  val ParamSupport = Python.containerMember("ParamSupport")
  val emptyCtx = ParamSupport.termMember("emptyCtx")
  val getParam = ParamSupport.termMember("getParam")
  val startBatch = ParamSupport.termMember("startBatch")
  val addBinding = ParamSupport.termMember("addBinding")
  val finishBatch = ParamSupport.termMember("finish")
  val paramKey = ParamSupport.termMember("paramKey")

  val start = Python.termMember("start")

  val StringOps = Python.containerMember("StringOps")
  val String_iterator = StringOps.termMember("iterator")

  val py_abort    = Python.termMember("abort")

  val ffi = Python.containerMember("ffi")

  // Public API functions
  val ffi_importModule = ffi.termMember("importModule")
  val ffi_try          = ffi.termMember("try")
  val ffi_splice       = ffi.termMember("splice")
  val ffi_kwarg        = ffi.termMember("kwarg")
  val ffi_kwargs       = ffi.termMember("kwargs")

  // Raw FFI primitives (all intrinsified)
  val ffi_call        = ffi.termMember("call")
  val ffi_field       = ffi.termMember("field")
  val ffi_setField    = ffi.termMember("setField")
  val ffi_get         = ffi.termMember("get")
  val ffi_set         = ffi.termMember("set")
  val ffi_isNone      = ffi.termMember("isNone")
  val ffi_cast        = ffi.termMember("cast")
  val ffi_isInstance  = ffi.termMember("isInstance")
  val ffi_isSame      = ffi.termMember("isSame")


  // Result variant class symbols (from jo stdlib)
  val Jo       = defn.resolveContainer("jo")
  val jo_Ok    = Jo.typeMember("Ok")
  val jo_Err   = Jo.typeMember("Err")

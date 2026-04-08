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
  val ffi_try          = FFI.termMember("try")
  val ffi_importModule = FFI.termMember("importModule")
  val ffi_valueMember  = FFI.termMember("valueMember")
  val ffi_setMember    = FFI.termMember("setMember")
  val ffi_callMember   = FFI.termMember("callMember")
  val ffi_isNone       = FFI.termMember("isNone")
  val ffi_isInstance   = FFI.termMember("isInstance")
  val ffi_getItem      = FFI.termMember("getItem")
  val ffi_setItem      = FFI.termMember("setItem")
  val ffi_splice       = FFI.termMember("splice")
  val ffi_kwargs       = FFI.termMember("kwargs")
  val ffi_kwarg        = FFI.termMember("kwarg")

  // Python collection constructors in jo.runtime.py
  val core_list  = Python.termMember("list")
  val core_tuple = Python.termMember("tuple")
  val core_dict  = Python.termMember("dict")

  // Result variant class symbols (from jo stdlib)
  val Jo       = defn.resolveContainer("jo")
  val jo_Ok    = Jo.typeMember("Ok")
  val jo_Err   = Jo.typeMember("Err")

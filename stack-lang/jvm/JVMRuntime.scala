package jvm

import sast.*
import sast.Symbols.{ Symbol, Annotation }

/** Resolves the symbols the JVM backend treats specially: the `@extern`
  * FFI annotation, the small `jo.jvm.runtime` support library, and the
  * program entry point.
  *
  * Mirrors the role `RubyRuntime`/`NativeRuntime` play for their backends.
  */
class JVMRuntime(using defn: Definitions):
  val JvmNs        = defn.resolveContainer("jo.jvm")
  val JvmRuntimeNs = defn.resolveContainer("jo.jvm.runtime")

  // jo.jvm.extern: scoped under `jvm` since it's specific to this backend,
  // named `extern` (not `jvm` itself) so it doesn't collide with `jvm`
  // reserved as a future namespace root for reflective FFI
  // (`import jvm "java...."`).
  val annot_extern = JvmNs.annotationMember("extern")

  val start        = JvmRuntimeNs.termMember("start")
  val abort        = JvmRuntimeNs.termMember("abort")
  val throwAny     = JvmRuntimeNs.termMember("throwAny")
  val refEq        = JvmRuntimeNs.termMember("refEq")
  val isNull       = JvmRuntimeNs.termMember("isNull")
  val cast         = JvmRuntimeNs.termMember("cast")

  val Lowering     = JvmRuntimeNs.containerMember("Lowering")
  val lowerInvokeLambda = Lowering.termMember("invokeLambda")
  val lowerBox = Map(
    JVMTypes.JType.I -> Lowering.termMember("boxInt"),
    JVMTypes.JType.Z -> Lowering.termMember("boxBool"),
    JVMTypes.JType.B -> Lowering.termMember("boxByte"),
    JVMTypes.JType.C -> Lowering.termMember("boxChar"),
    JVMTypes.JType.F -> Lowering.termMember("boxFloat"),
    JVMTypes.JType.J -> Lowering.termMember("boxLong")
  )
  val lowerUnbox = Map(
    JVMTypes.JType.I -> Lowering.termMember("unboxInt"),
    JVMTypes.JType.Z -> Lowering.termMember("unboxBool"),
    JVMTypes.JType.B -> Lowering.termMember("unboxByte"),
    JVMTypes.JType.C -> Lowering.termMember("unboxChar"),
    JVMTypes.JType.F -> Lowering.termMember("unboxFloat"),
    JVMTypes.JType.J -> Lowering.termMember("unboxLong")
  )

  val ParamSupport = JvmRuntimeNs.containerMember("ParamSupport")
  val paramKey     = ParamSupport.termMember("paramKey")

  // String.{size,get,substring,indexOf} redirect here rather than being
  // hand-compiled: the Unicode code-point/UTF-16-code-unit bridging they
  // need is API-level behavior, implemented as ordinary Jo code in
  // runtime/jvm/Runtime.jo, not as bytecode-emission logic in JVMCodeGen.
  val StringOps       = JvmRuntimeNs.containerMember("StringOps")
  val String_size     = StringOps.termMember("size")
  val String_get      = StringOps.termMember("get")
  val String_substring = StringOps.termMember("substring")
  val String_indexOf  = StringOps.termMember("indexOf")
  val String_iterator = StringOps.termMember("iterator")

  // Array[T] is a real JVM Object[]; these are the actual array bytecode
  // instructions, so — like Int/Bool arithmetic — they're intrinsified
  // directly rather than routed through the @extern FFI mechanism (which
  // models member calls, not dedicated opcodes).
  val RefArray      = JvmRuntimeNs.containerMember("RefArray")
  val Array_create  = RefArray.termMember("create")
  val Array_get     = RefArray.termMember("get")
  val Array_set     = RefArray.termMember("set")
  val Array_size    = RefArray.termMember("size")
  val Array_clone   = RefArray.termMember("clone")

  /** Extra symbols reachable once a given symbol is reached.
    *
    * `cast[T]`/`refEq`/`isNull`/`throwAny` are intrinsified directly and never
    * enqueue anything by themselves, so no extra entries are needed yet —
    * kept as a hook mirroring RubyRuntime.intrinsicDeps for future growth.
    */
  def intrinsicDeps: Map[Symbol, List[Symbol]] = Map.empty

  def nativeSpec(sym: Symbol): Option[JVMRuntime.NativeSpec] =
    sym.annotation(annot_extern).map:
      case Annotation(_, Constant.String(owner) :: Constant.String(member) :: Constant.String(desc) :: Constant.String(kind) :: Nil) =>
        JVMRuntime.NativeSpec(owner, member, desc, kind)
      case other =>
        throw new Exception(s"Unexpected @extern payload on ${sym.fullName}: $other")
end JVMRuntime

object JVMRuntime:
  /** Decoded payload of an `@extern(owner, member, desc, kind)` annotation. */
  case class NativeSpec(owner: String, member: String, desc: String, kind: String)

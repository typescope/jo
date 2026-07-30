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

  val ParamSupport = JvmRuntimeNs.containerMember("ParamSupport")
  val paramKey     = ParamSupport.termMember("paramKey")

  /** Extra symbols reachable once a given symbol is reached.
    *
    * `cast[T]`/`refEq`/`isNull`/`throwAny` are intrinsified directly and never
    * enqueue anything by themselves, so no extra entries are needed yet —
    * kept as a hook mirroring RubyRuntime.intrinsicDeps for future growth.
    */
  def intrinsicDeps: Map[Symbol, List[Symbol]] = Map.empty

  /** Decoded payload of an `@extern(owner, member, desc, kind)` annotation. */
  case class NativeSpec(owner: String, member: String, desc: String, kind: String)

  def nativeSpec(sym: Symbol): Option[NativeSpec] =
    sym.annotation(annot_extern).map:
      case Annotation(_, Constant.String(owner) :: Constant.String(member) :: Constant.String(desc) :: Constant.String(kind) :: Nil) =>
        NativeSpec(owner, member, desc, kind)
      case other =>
        throw new Exception(s"Unexpected @extern payload on ${sym.fullName}: $other")
end JVMRuntime

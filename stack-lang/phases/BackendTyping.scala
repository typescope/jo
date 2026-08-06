package phases

import sast.Symbols.Symbol
import sast.Types.{ProcType, Type}

/** The constraints a backend's type discipline places on generated code.
  *
  * Two questions live here, and they are deliberately *not* derived from one
  * another — on the JVM they disagree, as tests/pos/bridge-covariant-result.jo
  * demonstrates.
  *
  * Neither is answerable by `Subtyping.conforms`. Subtyping is permissive
  * exactly where a target's representation can differ: a covariant result
  * conforms but changes a JVM descriptor, and a `String` where the interface
  * says `Any` conforms but changes native's tagging. Conformance is also
  * reflexive, so it can never flag identical signatures that a backend
  * nonetheless treats differently.
  */
trait BackendTyping:
  /** Can a value of type `a` be used where `b` is expected with no coercion
    * instruction?
    *
    * Not a claim about physical representation: on the JVM a `String` and an
    * `Object` are both plain references, yet using an `Object` as a `String`
    * still needs a `checkcast` for the verifier. Hence *coercion*, and hence
    * directional — the widening direction is free while the narrowing one is
    * not.
    *
    * Called only on non-lambda value types; `TypeAdapter` walks lambda
    * structure itself and consults this at the leaves.
    */
  def compatible(a: Type, b: Type): Boolean

  /** Will a call compiled against `iface`'s signature dispatch directly to
    * `impl`, or does it need a bridge that adapts and forwards?
    *
    * Deliberately not derived from `compatible`. `invokeinterface` resolves by
    * exact descriptor, so a class whose method returns `String` where the
    * interface says `Object` is coercion-free yet unreachable — it needs a
    * bridge despite needing no cast.
    */
  def callCompatible(iface: ProcType, impl: ProcType): Boolean

  /** The name a bridge must carry for `iface`-dispatch to reach it. */
  def bridgeName(ifaceMethod: Symbol): String

/** Every value is tagged and every reference interchangeable (js/ruby/python),
  * so nothing ever needs coercion or a bridge.
  *
  * Those backends do not run `InterfaceBridge` at all, so `callCompatible`
  * and `bridgeName` are unreachable there; `compatible` is too, since
  * `TypeAdapter`'s `allTagged` fast path answers before consulting it. This
  * exists because `Erasure` and `TypeAdapter` take a policy, and saying
  * "nothing needs coercion" is the honest one to give them.
  *
  * `callCompatible` still answers `true` rather than throwing, so that a
  * backend adding the phase later gets the right behaviour — no bridges —
  * instead of a crash.
  */
object UniformTyping extends BackendTyping:
  def compatible(a: Type, b: Type): Boolean = true

  def callCompatible(iface: ProcType, impl: ProcType): Boolean = true

  def bridgeName(ifaceMethod: Symbol): String =
    throw new Exception("no bridges are needed when every value is tagged")

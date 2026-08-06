package jvm

import phases.BackendTyping

import sast.*
import sast.Symbols.Symbol
import sast.Types.*

import JVMTypes.JRepr

/** The JVM's type discipline: descriptors for dispatch, the verifier for
  * coercion. See `BackendTyping` for why those are two questions.
  */
class JVMTyping(using Definitions) extends BackendTyping:
  /** Identical representations never need a coercion. Between two references,
    * a *widening* conversion is implicit — so this is exactly subtyping, and
    * the narrowing direction needs a `checkcast`. Crossing the primitive
    * boundary always costs a box or unbox.
    *
    * Note this is where the JVM and native diverge: native passes every
    * reference identically, so `String` and `Any` are interchangeable there,
    * while here `Any → String` needs an instruction and `String → Any` does
    * not. Deciding it by `repr` equality alone would be too strict — two
    * distinct classes in a subtype relation need no coercion, and treating
    * them as if they did wraps context-parameter values in spurious
    * adaptation lambdas.
    */
  def compatible(a: Type, b: Type): Boolean =
    val from = JVMTypes.repr(a)
    val to = JVMTypes.repr(b)

    if from == to then true
    else if isRef(from) && isRef(to) then Subtyping.conforms(a, b)
    else false

  /** A JVM method's identity is its descriptor, so a call reaches `impl` only
    * when every position — parameters, autos, result — has the same
    * representation. `invokeinterface` resolves against the interface's own
    * erased descriptor, so a class implementing `Ord[String]` with natural
    * `(String, String)` parameters needs a bridge carrying
    * `(Object, Object)Int`.
    *
    * Compared on `JRepr` rather than on descriptor strings because a
    * descriptor needs a class *name*, and names are allocated statefully by
    * `JVMCodeGen.classSimpleName`. Symbol identity answers it purely.
    */
  def callCompatible(iface: ProcType, impl: ProcType): Boolean =
    signature(iface) == signature(impl)

  /** Unlike native, the JVM leaves no room for a naming convention: the bridge
    * *is* the method `invokeinterface` resolves, so it must carry the
    * interface method's own name. The JVM tells it apart from the
    * natural-typed method by descriptor alone — a return type is part of a
    * method's identity in bytecode, even though Java source cannot overload
    * on it.
    */
  def bridgeName(ifaceMethod: Symbol): String = ifaceMethod.name

  private def signature(pt: ProcType): List[JRepr] =
    (pt.paramTypes ++ pt.autoTypes :+ pt.resultType).map(JVMTypes.repr)

  private def isRef(r: JRepr): Boolean = r match
    case JRepr.Obj | JRepr.Str | JRepr.Cls(_) => true
    case _ => false

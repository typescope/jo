package jvm

import sast.*
import sast.Symbols.*
import sast.Types.*

/** How the JVM backend represents a Jo type, independent of class naming and
  * class-emission state.
  *
  * This is the single source of truth for "what does this type compile to".
  * `JVMCodeGen.jvmType` derives its descriptor-carrying `JType` from it, and
  * `JVMTyping` compares method signatures with it — so the bridge decision
  * and the emitted descriptors can never drift apart.
  *
  * The split exists because naming a class is *stateful*: `enqueueClass`
  * records the class for compilation and `classSimpleName` allocates a
  * deduplicated name. Keying references by `Symbol` here instead of by
  * generated name keeps this pure, which is what lets a phase ask the
  * representation question without touching the code generator.
  */
object JVMTypes:

  enum JRepr:
    case I, Z, B, C, F, J, V

    /** `java.lang.String` */
    case Str

    /** A compiled Jo class or interface. Two symbols denote the same JVM
      * class exactly when they are equal — `JVMCodeGen.classSimpleName`
      * allocates a distinct name per symbol — so symbol identity is a
      * faithful stand-in for descriptor equality, with no name needed.
      */
    case Cls(sym: Symbol)

    /** `java.lang.Object`: erased type parameters, unions, `Any`, `Bottom`,
      * and `Array[T]` (intrinsified as a real `Object[]`, so it never gets a
      * `ClassDef` of its own).
      */
    case Obj

  /** Only the SAST's *internal* `VoidType` marker means "no bytecode
    * representation at all". Jo's user-visible `Unit` and `Bottom` are
    * genuine value types and fall through to `Obj`, with `null` as their
    * runtime representation — see `JVMCodeGen.jvmType`'s doc comment.
    */
  def repr(tp: Type)(using defn: Definitions): JRepr =
    if tp.isVoidType then JRepr.V
    else
      // `.approx` dealiases and widens term references (e.g. an `Ident`'s
      // `.tpe` is a `StaticRef` to the *symbol*, not its value type).
      tp.approx match
        case StaticRef(sym) if sym == defn.Int_type    => JRepr.I
        case StaticRef(sym) if sym == defn.Bool_type   => JRepr.Z
        case StaticRef(sym) if sym == defn.Byte_type   => JRepr.B
        case StaticRef(sym) if sym == defn.Char_type   => JRepr.C
        case StaticRef(sym) if sym == defn.Float_type  => JRepr.F
        case StaticRef(sym) if sym == defn.Long_type   => JRepr.J
        case StaticRef(sym) if sym == defn.String_type => JRepr.Str

        // A concrete class/interface reference (including a generic class's
        // own instantiation, e.g. `Pair[Bool, Int]`) keeps its compiled
        // identity through `Erasure` — see `Erasure.EraseTypeMap`'s
        // `AppliedType(tctor, _) => StaticRef(tctor)`, which only collapses a
        // genuinely unresolved type parameter to `Any`. Mirroring that here
        // is what makes a bridge's descriptor differ from the natural
        // method's; collapsing every non-primitive to `Obj` would make the
        // two collide as duplicate methods.
        case _ =>
          classOrInterfaceSymbol(tp) match
            case Some(sym) if sym != defn.Array_class => JRepr.Cls(sym)
            case _ => JRepr.Obj

  def classOrInterfaceSymbol(tp: Type)(using Definitions): Option[Symbol] =
    tp.approx.typeSymbolOpt.filter(_.isOneOf(Flags.Class | Flags.Interface))

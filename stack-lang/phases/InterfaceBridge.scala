package phases

import sast.*
import sast.Trees.*

/** Materializes the bridge methods `Erasure` detects a need for (see its
  * own doc comment on `bottomErasedTo`/`bridgeMap` and `makeBridge`) into
  * real `FunDef`s, appended to each class's `funs`.
  *
  * Split out from `Erasure` itself so a backend can choose *when* bridges
  * get materialized relative to its own later phases. `Erasure`'s bridge
  * *detection* triggers lazily, the first time anything forces a class's
  * `classSym.info` — so it only ever sees classes that exist by that point.
  * A class `ElimCapture` synthesizes afterward (lifting a lambda literal
  * into one implementing a SAM interface) is invisible to it — not because
  * bridging doesn't apply to such a class, but purely because of phase
  * ordering; `ElimCapture` itself doesn't need bridges to exist first
  * either (it only needs the lambda's natural type and the
  * `Encoded(lambda)(interfaceType)` marker `Erasure` already leaves).
  *
  * Running `InterfaceBridge` after `ElimCapture` instead of immediately
  * after `erasure` closes that gap: the same detection/materialization
  * logic just gets a later look, uniformly covering both ordinary and
  * lifted classes, so a backend that needs this doesn't need its own
  * separate, narrower bridge-synthesis path for lifted classes (see the
  * JVM backend's history before this phase existed).
  *
  * Only `native` and the JVM backend ever populate any bridges at all
  * (`Erasure`'s detection is gated on `!allPrimitivesTagged`, false for
  * js/ruby/python) — so this phase is a genuine no-op wherever it's placed
  * for those backends. For `native`, inserting it immediately after
  * `erasure` (its historical materialization point, when this was still
  * done inside `Erasure.transformClassDef`) keeps its behavior unchanged.
  */
class InterfaceBridge(bridges: Erasure.Bridges)(using Definitions) extends Phase:
  // Only `cdef.funs` itself gains new (bridge) entries — nothing here needs
  // to walk into any existing function's body.
  override def transformFunDef(fdef: FunDef)(using Context): FunDef = fdef

  override def transformClassDef(cdef: ClassDef)(using Context): ClassDef =
    val classSym = cdef.symbol
    Phase.owner.set(classSym)
    val bridgeDefs = bridges.materialize(classSym)
    if bridgeDefs.isEmpty then cdef
    else cdef.copy(funs = cdef.funs ++ bridgeDefs)(cdef.annots, cdef.span)

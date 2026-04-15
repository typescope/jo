package typing

import ast.{ Trees => Ast }
import ast.Positions.*

import sast.*
import sast.Trees.*
import sast.Types.*

import reporting.Reporter

import Inference.*

/** Dynamic member access fallback for types advertising the dynamic protocol.
  *
  * Invoked from transformSelect, transformCall, transformAssign, and bracket
  * dispatch after the receiver has already been typed.
  *
  * A type opts in by defining the relevant *Dynamic methods:
  *
  *   selectDynamic(name: String): Value     — x.foo  (attribute read)
  *   updateDynamic(name: String, v: Any)    — x.foo = v  (attribute write)
  *   callDynamic(name: String, args: ..Any) — x.foo(args)  (method call)
  *   getDynamic(key: Any): Value            — x[k]  (bracket read)
  *   setDynamic(key: Any, v: Any): Unit     — x[k] = v  (bracket write)
  *
  */
trait DynamicTyper:
  this: Namer =>

  /** Whether a type supports the select-dynamic protocol. */
  def supportsDynamicSelect(tpe: Type)(using Definitions): Boolean =
    tpe.hasTermMember("selectDynamic")

  /** Whether a type supports the call-dynamic protocol. */
  def supportsDynamicCall(tpe: Type)(using Definitions): Boolean =
    tpe.hasTermMember("callDynamic")

  /** Whether a type supports the update-dynamic protocol. */
  def supportsDynamicUpdate(tpe: Type)(using Definitions): Boolean =
    tpe.hasTermMember("updateDynamic")

  /** Whether a type supports the bracket-read dynamic protocol. */
  def supportsDynamicGet(tpe: Type)(using Definitions): Boolean =
    tpe.hasTermMember("getDynamic")

  /** Whether a type supports the bracket-write dynamic protocol. */
  def supportsDynamicSet(tpe: Type)(using Definitions): Boolean =
    tpe.hasTermMember("setDynamic")

  /** Rewrite `qual.name` → `qual.selectDynamic("name")`. */
  def tryDynamicSelect(qual: Word, name: String, span: Span)
      (using Definitions, Scope, Reporter, Source, TargetType, TypeVars, ControlScope)
  : Option[Word] =
    if !supportsDynamicSelect(qual.tpe) then return None

    val fun = resolveTypedSelect(qual, "selectDynamic", span, allowAdapt = false)

    if fun.tpe.isError then None
    else Some(applyResolvedFun(fun, List(Ast.StringLit(name)(span)), span))

  /** Rewrite `qual.name = rhs` → `qual.updateDynamic("name", rhs)`. */
  def tryDynamicUpdate(qual: Word, name: String, rhs: Ast.Word, span: Span)
      (using Definitions, Scope, Reporter, Source, TargetType, TypeVars, ControlScope)
  : Option[Word] =
    if !supportsDynamicUpdate(qual.tpe) then return None

    val fun = resolveTypedSelect(qual, "updateDynamic", span, allowAdapt = false)

    if fun.tpe.isError then None
    else Some(applyResolvedFun(fun, List(Ast.StringLit(name)(span), rhs), span))

  /** Rewrite `qual.name(args)` → `qual.callDynamic("name", args...)`. */
  def tryDynamicCall(qual: Word, name: String, args: List[Ast.CallArg], span: Span)
      (using Definitions, Scope, Reporter, Source, TargetType, TypeVars, ControlScope)
  : Option[Word] =
    if !supportsDynamicCall(qual.tpe) then return None

    val fun = resolveTypedSelect(qual, "callDynamic", span, allowAdapt = false)

    if fun.tpe.isError then None
    else Some(applyResolvedFun(fun, Ast.StringLit(name)(span) :: args, span))

  /** Rewrite `qual[args...]` → `qual.getDynamic(args...)`. */
  def tryDynamicGet(qual: Word, args: List[Ast.Word], span: Span)
      (using Definitions, Scope, Reporter, Source, TargetType, TypeVars, ControlScope)
  : Option[Word] =
    if !supportsDynamicGet(qual.tpe) then return None

    val fun = resolveTypedSelect(qual, "getDynamic", span, allowAdapt = false)

    if fun.tpe.isError then None
    else Some(applyResolvedFun(fun, args, span))

  /** Rewrite `qual[args...] = rhs` → `qual.setDynamic(args..., rhs)`. */
  def tryDynamicSet(qual: Word, args: List[Ast.Word], rhs: Ast.Word, span: Span)
      (using Definitions, Scope, Reporter, Source, TargetType, TypeVars, ControlScope)
  : Option[Word] =
    if !supportsDynamicSet(qual.tpe) then return None

    val fun = resolveTypedSelect(qual, "setDynamic", span, allowAdapt = false)

    if fun.tpe.isError then None
    else Some(applyResolvedFun(fun, args :+ rhs, span))

end DynamicTyper

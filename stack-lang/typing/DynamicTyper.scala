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
  * Invoked from transformSelect, transformCall, and transformAssign.  Each
  * caller types the qualifier with TargetType.Member(name) using a fresh
  * silenced reporter, checks whether the receiver type supports the protocol
  * and lacks the requested member statically, then delegates here.
  *
  * A type opts in by defining the relevant *Dynamic methods:
  *   selectDynamic(name: String): Value    — x.foo  (attribute read)
  *   updateDynamic(name: String, v: Any)   — x.foo = v  (attribute write)
  *   callDynamic(name: String, args: ..Any) — x.foo(args)  (method call)
  *   getDynamic(key: Any): Value           — x[k]  (bracket read)
  *   setDynamic(key: Any, v: Any): Unit    — x[k] = v  (bracket write)
  *
  * Rewrites are constructed by synthesising a typed AST proxy for the
  * already-typed qualifier and delegating back to transform, so that normal
  * argument checking and adaptation apply.
  */
trait DynamicTyper:
  this: Namer =>

  /** Whether a type supports the select-dynamic protocol. */
  def supportsDynamicSelect(tpe: Type)(using Definitions): Boolean =
    !tpe.isError && tpe.hasTermMember("selectDynamic")

  /** Whether a type supports the call-dynamic protocol. */
  def supportsDynamicCall(tpe: Type)(using Definitions): Boolean =
    !tpe.isError && tpe.hasTermMember("callDynamic")

  /** Whether a type supports the update-dynamic protocol. */
  def supportsDynamicUpdate(tpe: Type)(using Definitions): Boolean =
    !tpe.isError && tpe.hasTermMember("updateDynamic")

  /** Whether a type supports the bracket-read dynamic protocol. */
  def supportsDynamicGet(tpe: Type)(using Definitions): Boolean =
    !tpe.isError && tpe.hasTermMember("getDynamic")

  /** Whether a type supports the bracket-write dynamic protocol. */
  def supportsDynamicSet(tpe: Type)(using Definitions): Boolean =
    !tpe.isError && tpe.hasTermMember("setDynamic")

  /** Rewrite `qual.name` → `qual.selectDynamic("name")`. */
  def tryDynamicSelect(qual: Word, name: String, span: Span)
      (using Definitions, Scope, Reporter, Source, TargetType, TypeVars, ControlScope)
  : Option[Word] =
    if !supportsDynamicSelect(qual.tpe) then return None

    val proxy = mkProxy(qual, span)
    val callAst = Ast.Apply(
      Ast.Select(proxy, "selectDynamic")(span),
      List(Ast.StringLit(name)(span))
    )(span)

    Some(transform(callAst))

  /** Rewrite `qual.name = rhs` → `qual.updateDynamic("name", rhs)`. */
  def tryDynamicUpdate(qual: Word, name: String, rhs: Ast.Word, span: Span)
      (using Definitions, Scope, Reporter, Source, TargetType, TypeVars, ControlScope)
  : Option[Word] =
    if !supportsDynamicUpdate(qual.tpe) then return None

    val proxy = mkProxy(qual, span)
    val callAst = Ast.Apply(
      Ast.Select(proxy, "updateDynamic")(span),
      List(Ast.StringLit(name)(span), rhs)
    )(span)

    Some(transform(callAst))

  /** Rewrite `qual.name(args)` → `qual.callDynamic("name", args...)`. */
  def tryDynamicCall(qual: Word, name: String, args: List[Ast.CallArg], span: Span)
      (using Definitions, Scope, Reporter, Source, TargetType, TypeVars, ControlScope)
  : Option[Word] =
    if !supportsDynamicCall(qual.tpe) then return None

    val proxy = mkProxy(qual, span)
    val callAst = Ast.Apply(
      Ast.Select(proxy, "callDynamic")(span),
      Ast.StringLit(name)(span) :: args
    )(span)

    Some(transform(callAst))

  /** Rewrite `qual[args...]` → `qual.getDynamic(args...)`. */
  def tryDynamicGet(qual: Word, args: List[Ast.Word], span: Span)
      (using Definitions, Scope, Reporter, Source, TargetType, TypeVars, ControlScope)
  : Option[Word] =
    if !supportsDynamicGet(qual.tpe) then return None

    val proxy = mkProxy(qual, span)
    val callAst = Ast.Apply(
      Ast.Select(proxy, "getDynamic")(span),
      args
    )(span)

    Some(transform(callAst))

  /** Rewrite `qual[args...] = rhs` → `qual.setDynamic(args..., rhs)`. */
  def tryDynamicSet(qual: Word, args: List[Ast.Word], rhs: Ast.Word, span: Span)
      (using Definitions, Scope, Reporter, Source, TargetType, TypeVars, ControlScope)
  : Option[Word] =
    if !supportsDynamicSet(qual.tpe) then return None

    val proxy = mkProxy(qual, span)
    val callAst = Ast.Apply(
      Ast.Select(proxy, "setDynamic")(span),
      args :+ rhs
    )(span)

    Some(transform(callAst))

  /** Create a synthetic AST ident pre-marked as the already-typed qualifier.
    *
    * When transform encounters this node it returns `qual` directly via the
    * TypedWord key, preserving the typed tree without re-typing.
    */
  private def mkProxy(qual: Word, span: Span): Ast.Ident =
    val proxy = Ast.Ident("__")(span)
    proxy.addKey(Namer.TypedWord, qual)
    proxy

end DynamicTyper

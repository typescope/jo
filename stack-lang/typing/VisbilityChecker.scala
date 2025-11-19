package sast

import Trees.*
import Types.*
import Symbols.*

import ast.Positions.*
import reporting.Reporter

/** Check coherence of the visibility symbols that appear in the types of
  * top-level definitions.
  *
  * The visibility check is already performed for selections during type
  * checking. However, the check for usage is delayed for:
  *
  * - Infered context parameters
  * - Infered result type of functions and methods
  *
  * Meanwhile, the check for coherence of visibility is missing, e.g., to ensure
  * that a private type should not appear in a public API.
  *
  * The coherence of visibility is defined as follows:
  *
  *     A symbolic name X that appears in a top-level definition Y is coherent
  *     if and only if the visibile scope of X contains the visibile scope of Y.
  *
  * Warning: The check can only be performed after effect analysis.
  */
object VisibilityChecker:
  def check(nss: List[Namespace])(using Definitions, Reporter): List[Namespace] =
    for ns <- nss do
      given Source = ns.symbol.sourcePos.source
      checkDefs(ns.defs)
    end for
    nss

  def checkDefs(defs: List[Def])(using Definitions, Reporter, Source): Unit =
    for
      defn <- defs
    do
      defn match
        case fdef: FunDef => checkFunDef(fdef)

        case pdef: PatDef =>
          // Check type parameters
          pdef.tparams.foreach: tparam =>
            checkType(tparam, tparam.info, tparam.sourcePos)

          // Check parameters
          pdef.params.foreach: param =>
            checkType(param, param.info, param.sourcePos)

          // Check result type
          checkType(pdef.symbol, pdef.resultType.tpe, pdef.resultType.pos)

        case pdef: ParamDef => checkType(pdef.symbol, pdef.symbol.info, pdef.tpt.pos)

        case pdef: TypeDef => checkType(pdef.symbol, pdef.symbol.info, pdef.symbol.sourcePos)

        case section: Section => checkDefs(section.defs)

        case cdef: ClassDef =>
          cdef.vals.foreach: sym =>
            checkType(sym, sym.info, sym.sourcePos)

          cdef.funs.foreach: fdef =>
            checkFunDef(fdef)

        case _ =>
    end for

  def checkFunDef(fdef: FunDef)(using Definitions, Reporter, Source): Unit =
    val funSym = fdef.symbol

    // No type bounds for now, still do it to be future-proof
    fdef.tparams.foreach: tparam =>
      checkType(tparam, tparam.info, tparam.sourcePos)

    fdef.params.foreach: param =>
      checkType(param, param.info, param.sourcePos)

    fdef.adapters.foreach: adapters =>
      for adapter <- adapters do
        adapter match
          case ParamAdapter.Function(symbol) =>
            checkUsage(symbol, funSym, adapter.pos)
            checkCoherence(symbol, funSym, adapter.pos)

          case _ =>

    fdef.autos.foreach: auto =>
      checkType(auto, auto.info, auto.sourcePos)

    fdef.candidates.foreach: cands =>
      for cand <- cands do
        cand match
          case AutoCandidate.Value(symbol) =>
            checkUsage(symbol, funSym, cand.pos)
            checkCoherence(symbol, funSym, cand.pos)

          case _ =>

    checkType(funSym, fdef.resultType.tpe, fdef.resultType.pos)

    // Check effects
    val procType = funSym.info.asProcType

    procType.receives.foreach: eff =>
      checkUsage(eff, funSym, funSym.sourcePos)
      checkCoherence(eff, funSym, funSym.sourcePos)

  def checkType(defSymbol: Symbol, tpe: Type, pos: SourcePosition)(using Definitions, Reporter): Unit =
    val traverser = new TypeTraverser:
      def apply(tp: Type): Unit =
        tp match
          case StaticRef(sym) =>
            checkUsage(sym, defSymbol, pos)
            checkCoherence(sym, defSymbol, pos)

          case tvar: TypeVar => recur(tvar.instantiated)

          case _ => recur(tp)
      end apply

    traverser.apply(tpe)

  def checkUsage(reference: Symbol, site: Symbol, pos: SourcePosition)(using Definitions, Reporter): Unit =
    if !reference.visibleScope.visibleIn(site) then
      Reporter.error("The private symbol " + reference + " cannot be access here", pos)

  def checkCoherence(reference: Symbol, binder: Symbol, pos: SourcePosition)(using Definitions, Reporter): Unit =
    if !reference.visibleScope.contains(binder.visibleScope) then
      Reporter.error("The private symbol " + reference + " is leaked by the type of " + binder, pos)

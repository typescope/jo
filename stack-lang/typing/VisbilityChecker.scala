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

        case idef: InterfaceDef =>
          // Check type parameters
          idef.tparams.foreach: tparam =>
            checkType(tparam, tparam.info, tparam.sourcePos)

          // Check methods
          idef.methods.foreach: fdef =>
            checkFunDef(fdef)

        case adef: AliasDef =>
          val target = adef.symbol.info.as[StaticRef].symbol
          if !target.visibleScope.contains(adef.symbol.visibleScope) then
            Reporter.error("An alias should have equal or smaller visible scope than the target", adef.symbol.sourcePos)

        case _: ValDef => ??? // not global variables
    end for

  def checkFunDef(fdef: FunDef)(using Definitions, Reporter, Source): Unit =
    val funSym = fdef.symbol

    // No type bounds for now, still do it to be future-proof
    fdef.tparams.foreach: tparam =>
      checkType(tparam, tparam.info, tparam.sourcePos)

    fdef.params.foreach: param =>
      checkType(funSym, param.info, param.sourcePos)

    fdef.autos.foreach: auto =>
      checkType(funSym, auto.info, auto.sourcePos)

    fdef.candidates.foreach: cands =>
      for cand <- cands do
        cand match
          case AutoCandidate.Value(symbol) =>
            // The sysmbol should be coherent itself
            checkUsage(symbol, funSym, cand.pos)
            checkCoherence(symbol, funSym, cand.pos)

          case AutoCandidate.Member(tp, _) =>
            checkType(funSym, tp.tpe, cand.pos)

    checkType(funSym, fdef.resultType.tpe, fdef.resultType.pos)

    // Check effects
    val procType = funSym.info.asProcType

    procType.receives.foreach: eff =>
      checkUsage(eff, funSym, funSym.sourcePos)
      checkCoherence(eff, funSym, funSym.sourcePos)

  def checkType(defSymbol: Symbol, tpe: Type, pos: SourcePosition)(using Reporter): Unit =

    val traverser = new TypeTraverser:
      def apply(tp: Type): Unit =
        tp match
          case StaticRef(sym) if !sym.isTypeParameter =>
            // The sysmbol should be coherent itself
            checkUsage(sym, defSymbol, pos)
            checkCoherence(sym, defSymbol, pos)

          case duckType @ DuckType(baseType) =>
            // Check adapters for function symbols
            for adapter <- duckType.adapters do
              adapter match
                case ParamAdapter.Function(symbol) =>
                  checkUsage(symbol, defSymbol, pos)
                  checkCoherence(symbol, defSymbol, pos)
                case _ =>
            recur(baseType)

          case tvar: TypeVar => recur(tvar.instantiated)

          case _ => recur(tp)
      end apply

    traverser.apply(tpe)

  def checkUsage(reference: Symbol, site: Symbol, pos: SourcePosition)(using Reporter): Unit =
    if !reference.visibleIn(site) then
      Reporter.error("The private symbol " + reference + " cannot be access here", pos)

  def checkCoherence(reference: Symbol, binder: Symbol, pos: SourcePosition)(using Reporter): Unit =
    if !reference.visibleScope.contains(binder.visibleScope) then
      Reporter.error("The private symbol " + reference + " is leaked by the type of " + binder, pos)

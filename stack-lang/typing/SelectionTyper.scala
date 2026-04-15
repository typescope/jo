package typing

import ast.Positions.*

import sast.*
import sast.Trees.*
import sast.Types.*

import reporting.Reporter

import common.Debug

/** Selection typing for `x.foo`.
  *
  * `resolveTypedSelect` resolves a term member on an already-typed receiver and
  * adapts the selected result to the current target type.
  *
  * If `allowAdapt` is true, delegate-view adaptation is allowed after direct
  * term selection fails. Dynamic fallback and container-member policy are
  * handled by the caller.
  */
trait SelectionTyper:
  this: Namer =>

  def resolveTypedSelect(qual: Word, name: String, span: Span, allowAdapt: Boolean)
      (using defn: Definitions, sc: Scope, rp: Reporter, so: Source, tvars: TypeVars, cs: ControlScope)
  : Word = Debug.trace(s"resolve ${qual.show}.$name, allowAdapt = $allowAdapt", enable = false):
    val qualType = qual.tpe

    def directSelect(): Option[Word] =
      qualType.getTermMember(name) match
        case Some(tp) =>
          tp match
            case ref: RefType =>
              Checker.checkAccess(ref.symbol, sc.owner, span)
            case _ =>

          tp match
            case StaticRef(sym) if !qualType.isValueType =>
              Some(Ident(sym.dealias)(span))

            case _ =>
              Some(TreeOps.smartSelect(qual, name, span))

        case None =>
          None

    def adaptedSelect(): Word =
      Adaptation.adaptMember(qual, name, sc.owner, selectMember = true) match
        case Adaptation.MemberAdaptResult.Success(selectedWord) =>
          selectedWord

        case _: Adaptation.MemberAdaptResult.Invisible =>
          Reporter.error(s"Found a member $name on a delegate view, but it is not visible at the location", qual.pos)
          errorWord(span)

        case Adaptation.MemberAdaptResult.Ambiguous(candidates) =>
          val views = candidates.map(_.show).mkString(", ")
          val tip = s"\nPlease disambiguate by selecting the view explicitly, e.g. .view[${candidates.head.show}].$name"
          Reporter.error(s"More than one view has the member $name, views = " + views + tip, qual.pos)
          errorWord(span)

        case Adaptation.MemberAdaptResult.NotFound =>
          Reporter.error(s"The prefix does not contain the member $name", qual.pos)
          errorWord(span)

    directSelect() match
      case Some(word) =>
        word

      case None =>
        if allowAdapt then
          adaptedSelect()

        else
          Reporter.error(s"The prefix does not contain the member $name", qual.pos)
          errorWord(span)

package sast

import Sast.*

object Desugaring:

  /** Desguar short-cutting || and &&
    *
    *     lhs || rhs    ===>    if lhs then true else rhs
    *     lhs && rhs    ===>    if lhs then rhs  else false
    */
  def desugarShortcutAndOr(apply: Apply)(using defn: Definitions): Word =
    val Apply(fun, args) = apply

    if fun.refersTo(defn.Predef_and) then
      val lhs :: rhs :: Nil = args: @unchecked
      val falseLit = BoolLit(false)(apply.tpe, rhs.span)
      If(lhs, rhs, falseLit)(apply.tpe, apply.span)

    else if fun.refersTo(defn.Predef_or) then
      val lhs :: rhs :: Nil = args: @unchecked
      val trueLit = BoolLit(true)(apply.tpe, lhs.span)
      If(lhs, trueLit, rhs)(apply.tpe, apply.span)

    else
      apply

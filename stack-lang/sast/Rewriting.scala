package sast

import Trees.*

object Rewriting:

  /** Desguar short-cutting || and &&
    *
    *     lhs || rhs    ===>    if lhs then true else rhs
    *     lhs && rhs    ===>    if lhs then rhs  else false
    */
  def rewrite(word: Word)(using defn: Definitions): Word =
    word match
      case Apply(fun, args, autos) =>

        if fun.refers(defn.Bool_and) then
          val lhs :: rhs :: Nil = args: @unchecked
          val falseLit = BoolLit(false)(rhs.span.endPoint)
          If(lhs, rhs, falseLit)(word.tpe, word.span)

        else if fun.refers(defn.Bool_or) then
          val lhs :: rhs :: Nil = args: @unchecked
          val trueLit = BoolLit(true)(lhs.span.endPoint)
          If(lhs, trueLit, rhs)(word.tpe, word.span)

        else
          word

      case _ =>
        word

package sast

import Sast.*
import Types.*
import Symbols.*

object Exhaustivity:
  enum Space:
    case EmptySpace
    case TypeSpace(tpe: Type)
    case TagSpace(tag: String, args: List[Space])
    case PredSpace(pred: Symbol, fun: Word, args: List[Space])
    case UnionSpace(spaces: Seq[Space])

  import Space.*

  def project(pattern: Pattern): Space =
    pattern match
      case AscribePattern(id, nested) => project(nested)

      case TypePattern(tpt) => TypeSpace(tpt.tpe)

      case WildcardPattern() => TypeSpace(pattern.tpe)

      case tagPat: TagPattern =>
        val spaces = tagPat.nested.map(project)
        TagSpace(tagPat.tag, spaces)

      case app @ ApplyPattern(pred, nested) =>
        val spaces = nested.map(project)
        PredSpace(app.symbol, pred, nested)

  def substract(s1: Space, s2: Space): Space =
    (s1, s2) match
      case (_, EmptySpace) => s1
      case (EmptySpace, _) => s1

      case (_, UnionSpace(spaces)) =>
        spaces.foldLeft(s1): (acc, s2) =>
          substract(acc, s2)

      case (UnionSpace(spaces), _) =>
        val spacesRest = spaces.map(s1 => substract(s1, s2))
        UnionSpace(spacesRest)

      case (TypeSpace(tp1), TypeSpace(tpe2)) =>
        if Subtyping.conforms(tp1, tp2) then
          EmptySpace

        else if tp1.isUnionType then
          val unionType = tp1.asUnionType
          val spaces = LazyList(unionType.branches).map(TypeSpace.apply)
          substract(UnionSpace(spaces), s2)

        else if tp2.isUnionType then
          val unionType = tp2.asUnionType
          val spaces = unionType.branches.map(TypeSpace.apply)
          substract(UnionSpace(spaces), s2)

        else
          s1

      case (TagSpace(tag1, args1), TagSpace(tag2, args2)) =>
        if tag1 == tag2 then
          assert(args1.size >= args2.size, s"args1.size = ${args1.size}, args2.size = ${args2.size}")
          LazyList(args1.zip(args2)).flatMap: (arg1, arg2) =>
            val res = substract(arg1, arg2)
        else
          s1

      case (PredSpace(pred1, fun1, args1), PredSpace(pred2, fun2, args2)) => ???

      case (TypeSpace(tp), TagSpace(tag, args)) => ???

      case (TagSpace(tag, args), TypeSpace(tp)) => ???

      case (PredSpace(pred, fun, args1), TagSpace(tag, args2)) => ???

      case (TagSpace(tag, args), PredSpace(pred, fun, args1)) => ???

      case (PredSpace(pred, fun, args1), TypeSpace(tp)) => ???

      case (TypeSpace(tp), PredSpace(pred, fun, args1)) => ???

  def intersect(s1: Space, s2: Space): Space = ???

  def isSubspace(s1: Space, s2: Space): Space = ???

  def isEmpty(space: Space): Space = ???

package sast

import Trees.*
import Types.*
import Symbols.*

import SeqPattern.Size

import common.Debug

object Exhaustivity:
  enum Space:
    case EmptySpace
    case TypeSpace(tpe: Type)
    case PredSpace(pred: Symbol, tpe: ProcType, args: List[Space])
    case PartialSpace(space: Space)
    case SeqSpace(tpe: Type, size: Size)
    case UnionSpace(spaces: Seq[Space])

    def show(using Definitions): String = Exhaustivity.show(this)

  def UnionSpace(spaces: Seq[Space]): Space =
    val nonEmpty = spaces.filter(s => !isEmpty(s))
    val res = nonEmpty.sizeCompare(1)
    if res < 0 then Space.EmptySpace
    else if res == 0 then nonEmpty.head
    else Space.UnionSpace(nonEmpty)

  import Space.*

  def show(space: Space)(using Definitions): String =
    space match
      case EmptySpace => "Empty"

      case TypeSpace(tpe) =>
        tpe.show

      case SeqSpace(tp, size) =>
        tp.show + " of " + size.toString

      case PartialSpace(space) =>
        show(space)

      case PredSpace(pred, tpe, args) =>
        val argsText =
          if args.isEmpty then ""
          else args.map(show).mkString("(", ", ", ")")

        pred.name + argsText

      case UnionSpace(spaces) =>
        spaces.map(show).mkString(", ")

  def flatten(space: Space): Seq[Space] =
    space match
      case EmptySpace => Nil

      case _: TypeSpace => space :: Nil

      case PartialSpace(space) => space :: Nil

      case _: SeqSpace => space :: Nil

      case PredSpace(pred, tpe, args) =>
        if args.exists(isEmpty) then
          Nil
        else
          space :: Nil

      case UnionSpace(spaces) =>
        spaces.flatMap(flatten)

  def isIrrefutable(pat: Pattern)(using defn: Definitions): Boolean =
    pat match
      case BindPattern(_, nested) => isIrrefutable(nested)

      case TypePattern(tpt) => Subtyping.isEqualType(tpt.tpe, pat.scrutineeType)

      case WildcardPattern() => true

      case seqPat: SeqPattern => isIrrefutable(seqPat)

      case ValuePattern(value) => false

      case ApplyPattern(pred, nested) =>
        assert(pred.tpe.isProcType, pred.tpe)
        !pred.tpe.asProcType.resultType.isPartial

      case _: OrPattern => false

      case AndPattern(lhs, rhs) => isIrrefutable(lhs) && isIrrefutable(rhs)

      case _: GuardPattern => false

      case AssignPattern(_) => true

  def isIrrefutable(pat: SeqPattern)(using Definitions): Boolean =
    pat.patterns.forall:
      case AtomPattern(pat)    => isIrrefutable(pat)
      case SkipToPattern(pat)  => isIrrefutable(pat)
      case StarPattern(pat)    => isIrrefutable(pat)
      case RestPattern(pat)    => isIrrefutable(pat)

  def project(pattern: Pattern)(using defn: Definitions): Space =
    pattern match
      case BindPattern(id, nested) => project(nested)

      case TypePattern(tpt) => TypeSpace(tpt.tpe)

      case WildcardPattern() => TypeSpace(pattern.valueType)

      case seqPat: SeqPattern =>

        if isIrrefutable(seqPat) then
          SeqSpace(seqPat.valueType, seqPat.totalSize)
        else
          PartialSpace(SeqSpace(seqPat.valueType, seqPat.totalSize))

      case ValuePattern(value) =>
        value match
          case Literal(b: Constant.Bool) =>
            TypeSpace(ConstantType(b))

          case _ =>
            val tp = AppliedType(defn.Predef_Partial, value.tpe :: Nil)
            TypeSpace(tp)

      case app @ ApplyPattern(pred, nested) =>
        val spaces = nested.map(project)
        PredSpace(app.symbol, pred.tpe.asProcType, spaces)

      case OrPattern(lhs, rhs) =>
        UnionSpace(project(lhs) :: project(rhs) :: Nil)

      case AndPattern(lhs, rhs) =>
        if isIrrefutable(rhs) then project(lhs)
        else PartialSpace(project(lhs))

      case GuardPattern(_) =>
        EmptySpace

      case AssignPattern(assignments) =>
        // Assignment pattern always matches (just binds values)
        TypeSpace(pattern.valueType)

  def subtract(s1: Space, s2: Space)(using defn: Definitions): Space = Debug.trace(s"subtract(${s1.show}, ${s2.show})", (_: Space).show, enable = false):
    (s1, s2) match
      case (_, EmptySpace | _: PartialSpace) => s1
      case (EmptySpace, _) => s1

      case (_: PartialSpace, _) => s1

      case (_, UnionSpace(spaces)) =>
        spaces.foldLeft(s1): (acc, s2) =>
          subtract(acc, s2)

      case (UnionSpace(spaces), _) =>
        val spacesRest = spaces.map(s1 => subtract(s1, s2))
        UnionSpace(spacesRest)

      case (TypeSpace(tp1), TypeSpace(tp2)) =>
        if Subtyping.conforms(tp1, tp2) then
          EmptySpace

        else if tp1.isUnionType then
          val unionType = tp1.asUnionType
          val spaces = LazyList.from(unionType.branches.map(TypeSpace.apply))
          val s1 = UnionSpace(spaces)
          subtract(s1, s2)

        else if tp2.isUnionType then
          val unionType = tp2.asUnionType
          val spaces = unionType.branches.map(TypeSpace.apply)
          val s2 = UnionSpace(spaces)
          subtract(s1, s2)

        // It's correct to not use subtyping here: otherwise a constant type `false` will loop
        else if tp1.dealias == defn.BoolType then
          val trueType = ConstantType(Constant.Bool(true))
          val falseType = ConstantType(Constant.Bool(false))
          val s1 = UnionSpace(TypeSpace(trueType) :: TypeSpace(falseType) :: Nil)
          subtract(s1, s2)

        else
          s1

      case (PredSpace(pred1, tp1, args1), PredSpace(pred2, tp2, args2)) =>
        if pred1 == pred2 && Subtyping.isEqualType(tp1, tp2) then
          assert(args1.size >= args2.size, s"args1.size = ${args1.size}, args2.size = ${args2.size}")

          val disjoint = args1.zip(args2).exists: (arg1, arg2) =>
            isDisjoint(arg1, arg2)

          if disjoint then
            s1
          else
            val lazySpaces = LazyList.from(args1.zip(args2)).flatMap: (arg1, arg2) =>
              val res = subtract(arg1, arg2)
              if isEmpty(res) then Nil
              else PredSpace(pred1, tp1, args1.map(arg => if arg `eq` arg1 then res else arg)) :: Nil

            UnionSpace(lazySpaces)

        else
          s1

      case (PredSpace(pred, procType, args), TypeSpace(tp)) =>
        if Subtyping.conforms(procType.resultType, tp) then
          EmptySpace

        else
          s1

      case (TypeSpace(tp), PredSpace(pred, procType, _)) =>
        if Subtyping.conforms(tp, procType.resultType) then
          val s1 = PredSpace(pred, procType, procType.params.map(param => TypeSpace(param.info)))
          subtract(s1, s2)

        else if tp.isUnionType then
          val unionType = tp.asUnionType
          val s1 = UnionSpace(unionType.branches.map(TypeSpace.apply))
          subtract(s1, s2)

        else
          s1

      case (SeqSpace(tp1, size1), SeqSpace(tp2, size2)) =>
        if Subtyping.conforms(tp1, tp2) then
          val rest = size1 - size2
          UnionSpace(rest.map(size => SeqSpace(tp1, size)))

        else
          s1

      case (TypeSpace(tp1), SeqSpace(tp2, size)) =>
        if Subtyping.conforms(tp1, tp2) then
          val s1 = SeqSpace(tp2, Size.GreatEq(0))
          subtract(s1, s2)

        else
          s1

      case (SeqSpace(tp1, size), TypeSpace(tp2)) =>
        if Subtyping.conforms(tp1, tp2) then EmptySpace else s1

      case _ => s1

  def isDisjoint(s1: Space, s2: Space)(using Definitions): Boolean = Debug.trace(s"isDisjoint(${s1.show}, ${s2.show})", enable = false):
    (s1, s2) match
      case (_, EmptySpace) => true
      case (EmptySpace, _) => true

      case (_, PartialSpace(s2)) => isDisjoint(s1, s2)
      case (PartialSpace(s1), _) => isDisjoint(s1, s2)

      case (_, UnionSpace(spaces)) =>
        spaces.forall: s2 =>
          isDisjoint(s1, s2)

      case (UnionSpace(spaces), _) =>
        spaces.forall: s1 =>
          isDisjoint(s1, s2)

      case (TypeSpace(tp1), TypeSpace(tp2)) =>
        if Subtyping.conforms(tp1, tp2) || Subtyping.conforms(tp2, tp1) then
          false

        else if tp1.isUnionType then
          val unionType = tp1.asUnionType
          unionType.branches.forall: tp1 =>
            isDisjoint(TypeSpace(tp1), s2)

        else if tp2.isUnionType then
          val unionType = tp2.asUnionType
          unionType.branches.forall: tp2 =>
            isDisjoint(s1, TypeSpace(tp2))

        else
          true

      case (PredSpace(pred1, procType1, args1), PredSpace(pred2, procType2, args2)) =>
        if pred1 == pred2 && Subtyping.isEqualType(procType1, procType2) then
          args1.zip(args2).exists: (arg1, arg2) =>
            isDisjoint(arg1, arg2)
        else
          val s1 = TypeSpace(procType1.resultType.stripPartial)
          val s2 = TypeSpace(procType2.resultType.stripPartial)
          isDisjoint(s1, s2)

      case (SeqSpace(tp1, size1), SeqSpace(tp2, size2)) =>
        if Subtyping.conforms(tp1, tp2) || Subtyping.conforms(tp2, tp1) then
          size1.isDisjoint(size2)

        else
          true

      case (_, SeqSpace(tp2, size)) =>
        val s2 = TypeSpace(tp2)
        isDisjoint(s1, s2)

      case (SeqSpace(tp1, size), _) =>
        val s1 = TypeSpace(tp1)
        isDisjoint(s1, s2)

      case (PredSpace(_, procType, _), _) =>
        val s1 = TypeSpace(procType.resultType.stripPartial)
        isDisjoint(s1, s2)

      case (_, PredSpace(_, procType, _)) =>
        val s2 = TypeSpace(procType.resultType.stripPartial)
        isDisjoint(s1, s2)

  def isEmpty(space: Space): Boolean =
    space match
      case EmptySpace => true

      case TypeSpace(tpe: Type) => false

      case _: SeqSpace => false

      case PredSpace(_, _, args) => args.exists(isEmpty)

      case UnionSpace(spaces) => spaces.forall(isEmpty)

      case PartialSpace(space) => isEmpty(space)

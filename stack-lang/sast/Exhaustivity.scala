package sast

import Sast.*
import Types.*
import Symbols.*

import common.Debug

object Exhaustivity:
  enum Space:
    case EmptySpace
    case TypeSpace(tpe: Type)
    case TagSpace(tag: String, args: List[Space])
    case PredSpace(pred: Symbol, tpe: ProcType, args: List[Space])
    case PartialSpace(space: Space)
    case UnionSpace(spaces: Seq[Space])

    def show: String = Exhaustivity.show(this)

  def UnionSpace(spaces: Seq[Space]): Space =
    val nonEmpty = spaces.filter(s => !isEmpty(s))
    val res = nonEmpty.sizeCompare(1)
    if res < 0 then Space.EmptySpace
    else if res == 0 then nonEmpty.head
    else Space.UnionSpace(nonEmpty)

  import Space.*

  def show(space: Space): String =
    space match
      case EmptySpace => "Empty"

      case TypeSpace(tpe) =>
        tpe.show

      case PartialSpace(space) =>
        show(space)

      case TagSpace(tag, args) =>
        val argsText =
          if args.isEmpty then ""
          else args.map(show).mkString("(", ", ", ")")

        "#" + tag + argsText

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

      case TagSpace(tag, args) =>
        if args.exists(isEmpty) then
          Nil
        else
          space :: Nil

      case PredSpace(pred, tpe, args) =>
        if args.exists(isEmpty) then
          Nil
        else
          space :: Nil

      case UnionSpace(spaces) =>
        spaces.flatMap(flatten)

  def project(pattern: Pattern)(using defn: Definitions): Space =
    pattern match
      case AscribePattern(id, nested) => project(nested)

      case TypePattern(tpt) => TypeSpace(tpt.tpe)

      case WildcardPattern() => TypeSpace(pattern.tpe)

      case ValuePattern(value) =>
        value match
          case Literal(b: Constant.Bool) =>
            TypeSpace(ConstantType(b))

          case _ =>
            val tp = AppliedType(TypeRef(defn.Predef_Partial), value.tpe :: Nil)
            TypeSpace(tp)

      case tagPat: TagPattern =>
        val spaces = tagPat.nested.map(project)
        TagSpace(tagPat.tag, spaces)

      case app @ ApplyPattern(pred, nested) =>
        val spaces = nested.map(project)
        PredSpace(app.symbol, pred.tpe.asProcType, spaces)

      case OrPattern(lhs, rhs) =>
        UnionSpace(project(lhs) :: project(rhs) :: Nil)

      case GuardPattern(pattern, _) =>
        PartialSpace(project(pattern))

      case TermBindingPattern(pattern, bindings) =>
        project(pattern)

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

        else if tp1.refersTo(defn.Predef_Bool) then
          val trueType = ConstantType(Constant.Bool(true))
          val falseType = ConstantType(Constant.Bool(false))
          val s1 = UnionSpace(TypeSpace(trueType) :: TypeSpace(falseType) :: Nil)
          subtract(s1, s2)

        else
          s1

      case (TagSpace(tag1, args1), TagSpace(tag2, args2)) =>
        if tag1 == tag2 then
          assert(args1.size >= args2.size, s"args1.size = ${args1.size}, args2.size = ${args2.size}")

          val disjoint = args1.zip(args2).exists: (arg1, arg2) =>
            isDisjoint(arg1, arg2)

          if disjoint then
            s1
          else
            val lazySpaces = LazyList.from(args1.zip(args2)).flatMap: (arg1, arg2) =>
              val res = subtract(arg1, arg2)
              if isEmpty(res) then Nil
              else TagSpace(tag1, args1.map(arg => if arg `eq` arg1 then res else arg)) :: Nil
            UnionSpace(lazySpaces)
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

      case (TypeSpace(tp), TagSpace(tag, _)) =>
        if tp.isTagType then
          val tagType = tp.asTagType
          if tagType.tag == tag then
            val s1 = TagSpace(tag, tagType.params.map(param => TypeSpace(param.info)))
            subtract(s1, s2)
          else
            s1

        else if tp.isUnionType then
          val unionType = tp.asUnionType
          if unionType.hasTag(tag) then
            val s1 = UnionSpace(unionType.branches.map(TypeSpace.apply))
            subtract(s1, s2)
          else
            s1

        else
          s1

      case (TagSpace(tag, _), TypeSpace(tp)) =>
        if tp.isTagType then
          val tagType = tp.asTagType
          if tagType.tag == tag then
            val s2 = TagSpace(tag, tagType.params.map(param => TypeSpace(param.info)))
            subtract(s1, s2)
          else
            s1

        else if tp.isUnionType then
          val unionType = tp.asUnionType
          if unionType.hasTag(tag) then
            val s2 = UnionSpace(unionType.branches.map(TypeSpace.apply))
            subtract(s1, s2)
          else
            s1

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

      case (_: PredSpace, _: TagSpace) => s1

      case (_: TagSpace, _: PredSpace) => s1

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

      case (TagSpace(tag1, args1), TagSpace(tag2, args2)) =>
        if tag1 == tag2 then
          args1.zip(args2).exists: (arg1, arg2) =>
            isDisjoint(arg1, arg2)

        else
          true

      case (PredSpace(pred1, tp1, args1), PredSpace(pred2, tp2, args2)) =>
        if pred1 == pred2 && Subtyping.isEqualType(tp1, tp2) then
          args1.zip(args2).exists: (arg1, arg2) =>
            isDisjoint(arg1, arg2)
        else
          true

      case (TypeSpace(tp), TagSpace(tag, _)) =>
        if tp.isTagType then
          val tagType = tp.asTagType
          tagType.tag != tag

        else if tp.isUnionType then
          val unionType = tp.asUnionType
          !unionType.hasTag(tag)

        else
          true

      case (TagSpace(tag, _), TypeSpace(tp)) =>
        if tp.isTagType then
          val tagType = tp.asTagType
          tagType.tag != tag

        else if tp.isUnionType then
          val unionType = tp.asUnionType
          !unionType.hasTag(tag)

        else
          true

      case (PredSpace(_, procType, _), _: TypeSpace) =>
        val s1 = TypeSpace(procType.resultType.stripPartial)
        isDisjoint(s1, s2)

      case (TypeSpace(tp), PredSpace(_, procType, _)) =>
        val s2 = TypeSpace(procType.resultType.stripPartial)
        isDisjoint(s1, s2)

      case (PredSpace(_, procType, _), _: TagSpace) =>
        val s1 = TypeSpace(procType.resultType.stripPartial)
        isDisjoint(s1, s2)

      case (_: TagSpace, PredSpace(_, procType, _)) =>
        val s2 = TypeSpace(procType.resultType.stripPartial)
        isDisjoint(s1, s2)

  def isEmpty(space: Space): Boolean =
    space match
      case EmptySpace => true

      case TypeSpace(tpe: Type) => false

      case TagSpace(_, args) => args.exists(isEmpty)

      case PredSpace(_, _, args) => args.exists(isEmpty)

      case UnionSpace(spaces) => spaces.forall(isEmpty)

      case PartialSpace(space) => isEmpty(space)

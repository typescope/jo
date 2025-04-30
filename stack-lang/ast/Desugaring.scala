package ast

import Ast.*
import Positions.Source

import reporting.Reporter

import scala.collection.mutable

object Desugaring:
  def synthesize(defs: List[Def])(using Reporter, Source): List[Def] =
    val dataDefs = mutable.Map.empty[String, DataDef]
    val sectionDefs = mutable.Map.empty[String, Section]

    defs.foreach:
      case ddef: DataDef => dataDefs(ddef.name) = ddef
      case sec: Section  => sectionDefs(sec.name) = sec
      case _ =>

    val defs2 =
      defs.flatMap:
        case ddef: DataDef => synthesizeDataDef(ddef, sectionDefs.getOrElse(ddef.name, null))
        case edef: EnumDef  => synthesizeEnumDef(edef, sectionDefs.getOrElse(edef.name, null))
        case sec: Section  => synthesizeSection(sec, dataDefs.getOrElse(sec.name, null)) :: Nil
        case defn => defn :: Nil

    if defs2.size != defs.size then synthesize(defs2) else defs2

  /** Desugar a data definition
    *
    * Given
    *
    *     data A[X, ...](x1: T1, ...)
    *
    * It will desugar to
    *
    *     type A[X, ...] = #A(x1: X, ...)
    *
    *     section A
    *       fun A[X, ...](x1: T1, ...): A = #A x1 ...
    *
    *       pattern A[X, ...](x1: T1, ...): A = case #A x1 ...
    *
    * If section `A` already exists, the synthesized members will become members
    * of the existing section.
    *
    * If the member of the same name already exists in section `A`, the
    * corresponding synthetic member is ignored to prefer the user-defined
    * member.
    */
  def synthesizeDataDef(ddef: DataDef, secDef: Section | Null)
      (using Reporter, Source)
  : List[Def] =

    val tagType = TagType(ddef.ident, ddef.params)(ddef.span)
    val tdef = TypeDef(ddef.ident, ddef.tparams, tagType, isBound = false)(ddef.span)
    if secDef != null then
      // section defitions have a chance to do its own desugaring with current
      // data defintion
      tdef :: Nil
    else
      val sec = Section(ddef.ident, defs = Nil)(ddef.span)
      tdef :: synthesizeSection(sec, ddef) :: Nil

  /** Synthesize members for companion section of a data definition
    *
    * Given
    *
    *     section A
    *       ...
    *
    *     data A[X, ...](x1: T1, ...)
    *
    * The following members will be added to section A:
    *
    *
    *     section A
    *       fun A[X, ...](x1: T1, ...): A = #A x1 ...
    *
    *       pattern A[X, ...](x1: T1, ...): A = case #A x1 ...
    *
    * The synthesis will be skipped if target of the same name already exists.
    */
  def synthesizeSection(secDef: Section, ddef: DataDef | Null)
      (using Reporter, Source)
  : Section =

    if ddef == null then secDef
    else
      val id = ddef.ident
      val tp =
        if ddef.tparams.isEmpty then id
        else AppliedType(id, ddef.tparams.map(_.ident))(id.span | ddef.tparams.last.span)

      val syntheticMembers = new mutable.ArrayBuffer[Def]

      val hasPatternMember = secDef.defs.exists: defn =>
        defn.name == ddef.name && defn.isInstanceOf[PatDef]

      val hasFunMember = secDef.defs.exists: defn =>
        defn.name == ddef.name && defn.isInstanceOf[FunDef]

      if !hasPatternMember then
        val pat =
          val tag = Tag(id)(id.span)
          if ddef.params.isEmpty then tag
          else Apply(tag, ddef.params.map(_.ident))(ddef.span)
        val body = Case(pat, Block(Nil)(id.span))(ddef.span) :: Nil
        val pdef = PatDef(id, ddef.tparams, ddef.params, tp, body, preParamCount = 0)(ddef.span)
        syntheticMembers += pdef

      if !hasFunMember then
        val body = Apply(Tag(id)(id.span), ddef.params.map(_.ident))(ddef.span)
        val receiveParams = Some(Nil)
        val fdef = FunDef(id, ddef.tparams, ddef.params, tp, receiveParams, body, preParamCount = 0)(ddef.span)
        syntheticMembers += fdef

      if syntheticMembers.isEmpty then
        secDef

      else
        Section(secDef.ident, (syntheticMembers ++ secDef.defs).toList)(secDef.span)

  /** An enum definition
    *
    *     data F[X, ...] = A(a: T1, ...) | B(b: S1, ...) | C
    *
    * desugars to
    *
    *     type F[X, ...] = A[U, ...] | B[V, ...] | C
    *
    *     data A[U, ...](a: T1, ...)
    *     data B[V, ...](b: S1, ...)
    *     data C
    *
    * where `U, ...` is a subset of `X, ...` that appear in the branch
    * `A`. Similarly for `V, ...`.
    *
    * For future: what if `X, ...` have dependencies among them via bounds?
    *
    * It's not useful for type params of type definitions to have bounds even if
    * it might make sense for functions and patterns. They do not improve
    * expressiveness.
    */
  def synthesizeEnumDef(enumDef: EnumDef, secDef: Section | Null)
      (using Reporter, Source)
  : List[Def] =

    val typeParams = mutable.Map.empty[String, TypeParam]

    for tparam <- enumDef.tparams do
      if typeParams.contains(tparam.name) then
        Reporter.error("Duplicate type param " + tparam.name, tparam.pos)

      else
        typeParams(tparam.name) = tparam

    val branchTypes = new mutable.ArrayBuffer[TypeTree]
    val dataDefs = new mutable.ArrayBuffer[DataDef]

    for tagType <- enumDef.branches do
      val typeParamRefs = mutable.Set.empty[String]
      val traverser = new AstOps.TypeTreeTraverser:
        def apply(tpt: TypeTree): Unit =
          tpt match
            case Ident(name) =>
              if typeParams.contains(name) then
                typeParamRefs += name

            case obj: ObjectType =>
              // polymorphic method types complicate the check and is not useful in practice
              Reporter.error("Only aliases of object type allowed in enum definitions", obj.pos)

            case _ =>
              recur(tpt)
        end apply

      traverser.apply(tagType)

      // Ensure same order as in declaration
      val tparamsReferred = enumDef.tparams.filter(tparam => typeParamRefs.contains(tparam.name))

      val branchType =
        if tparamsReferred.isEmpty then
          tagType.tag
        else
          val targs = tparamsReferred.map(_.ident)
          AppliedType(tagType.tag, targs)(tagType.span)

      branchTypes += branchType

      val dataDef = DataDef(tagType.tag, tparamsReferred, tagType.params)(tagType.span)
      dataDefs += dataDef
    end for

    val unionType = UnionType(branchTypes.toList)(enumDef.span)
    val tdef = TypeDef(enumDef.ident, enumDef.tparams, unionType, isBound = false)(enumDef.span)
    tdef :: dataDefs.toList

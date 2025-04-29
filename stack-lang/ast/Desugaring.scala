package ast

import Ast.*

import scala.collection.mutable

object Desugaring:
  def synthesize(defs: List[Def]): List[Def] =
    val dataDefs = mutable.Map.empty[String, DataDef]
    val sectionDefs = mutable.Map.empty[String, Section]

    defs.foreach:
      case ddef: DataDef => dataDefs(ddef.name) = ddef
      case sec: Section  => sectionDefs(sec.name) = sec
      case _ =>

    defs.flatMap:
      case ddef: DataDef => synthesizeDataDef(ddef, sectionDefs.getOrElse(ddef.name, null))
      case sec: Section  => synthesizeSection(sec, dataDefs.getOrElse(sec.name, null)) :: Nil
      case defn => defn :: Nil

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
  def synthesizeDataDef(ddef: DataDef, secDef: Section | Null): List[Def] =
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
  def synthesizeSection(secDef: Section, ddef: DataDef | Null): Section =
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
        val pat = Apply(Tag(id)(id.span), ddef.params.map(_.ident))(ddef.span)
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

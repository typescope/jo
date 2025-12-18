package typing

import ast.Trees.*
import ast.Positions.Source

import sast.Flags

import common.KeyProps
import reporting.Reporter

import scala.collection.mutable

object Desugaring:
  val ExtraFlags = new KeyProps.Key[Flags]("Desugaring.ExtraFlags")

  def synthesize(defs: List[Def])(using Reporter, Source): List[Def] =
    val defs2 =
      defs.flatMap:
        case edef: UnionDef  => synthesizeUnionDef(edef)
        case cdef: ClassDef => synthesizeClassDef(cdef)
        case pdef: ParamDef => desugarParamDef(pdef)
        case defn => defn :: Nil

    if defs2.size != defs.size then synthesize(defs2) else defs2

  /** A union definition
    *
    *     union F[X, ...] = A(a: T1, ...) | B(b: S1, ...) | C
    *
    * desugars to
    *
    *     type F[X, ...] = A[U, ...] | B[V, ...] | C
    *
    *     class A[U, ...](a: T1, ...)
    *     class B[V, ...](b: S1, ...)
    *     class C
    *
    * where `U, ...` is a subset of `X, ...` that appear in the branch
    * `A`. Similarly for `V, ...`.
    *
    * The classes are then processed by synthesizeClassDef which generates
    * constructor functions and patterns for each branch.
    *
    * For future: what if `X, ...` have dependencies among them via bounds?
    *
    * It's not useful for type params of type definitions to have bounds even if
    * it might make sense for functions and patterns. They do not improve
    * expressiveness.
    */
  def synthesizeUnionDef(enumDef: UnionDef)(using Reporter, Source): List[Def] =
    val typeParams = mutable.Map.empty[String, TypeParam]

    for tparam <- enumDef.tparams do
      if typeParams.contains(tparam.name) then
        Reporter.error("Duplicate type param " + tparam.name, tparam.pos)

      else
        typeParams(tparam.name) = tparam

    val branchTypes = new mutable.ArrayBuffer[TypeTree]
    val classDefs = new mutable.ArrayBuffer[ClassDef]

    for classDef <- enumDef.branches do
      val typeParamRefs = mutable.Set.empty[String]
      val traverser = new ast.TreeOps.TypeTreeTraverser:
        def apply(tpt: TypeTree): Unit =
          tpt match
            case Ident(name) =>
              if typeParams.contains(name) then
                typeParamRefs += name

            case obj: ObjectType =>
              // polymorphic method types complicate the check and is not useful in practice
              Reporter.error("Only aliases of object type allowed in union definitions", obj.pos)

            case _ =>
              recur(tpt)
        end apply

      for param <- classDef.params do
        traverser.apply(param.tpt)

      // Ensure same order as in declaration
      val tparamsReferred = enumDef.tparams.filter(tparam => typeParamRefs.contains(tparam.name))

      val branchType =
        if tparamsReferred.isEmpty then
          classDef.ident
        else
          val targs = tparamsReferred.map(_.ident)
          AppliedType(classDef.ident, targs)(classDef.span)

      branchTypes += branchType

      val updatedClassDef = classDef.copy(tparams = tparamsReferred)(classDef.span)
      classDefs += updatedClassDef
    end for

    val unionType = UnionType(branchTypes.toList)(enumDef.span)
    val tdef = TypeDef(enumDef.ident, enumDef.tparams, unionType, isBound = false, preParamCount = 0)(enumDef.span)
    tdef :: classDefs.toList

  /** Desugar a class definition
    *
    * Given
    *
    *     class A[X, ...](x1: T1, ...)
    *
    * It will desugar to
    *
    *     class A[X, ...]
    *       val x1: T1 = ...
    *       ...
    *       def A(x1: T1, ...) = { this.x1 = x1, ... }
    *     end
    *
    *     def A[X, ...](x1: T1, ...): A[X, ...] = new A[X, ...](x1, ...)
    *
    *     pattern A[X, ...](x1: T1, ...): A[X, ...] = case o then x1 = o.x1, ...
    *
    * If the class has no parameters, only the class itself is returned (no constructor function or pattern).
    */
  def synthesizeClassDef(cdef: ClassDef)(using Reporter, Source): List[Def] =
    // If class has no parameters and no views, return it as-is (just desugar vals)
    if cdef.params.isEmpty && cdef.views.isEmpty then
      return List(desugarClassDefInternal(cdef))

    val id = cdef.ident

    val tp =
      if cdef.tparams.isEmpty then id
      else AppliedType(id, cdef.tparams.map(_.ident))(id.span | cdef.tparams.last.span)

    // Desugar the class (move params to vals, desugar views, create constructor)
    val cdef2 = desugarClassDefInternal(cdef)

    val isDataClass = cdef.params.nonEmpty || cdef.vals.isEmpty

    // Generate standalone constructor function (only if class has parameters)
    val fdef =
      if !isDataClass then None
      else
        val classType =
          if cdef.tparams.isEmpty then id
          else AppliedType(id, cdef.tparams.map(_.ident))(id.span | cdef.tparams.last.span)

        val body = New(classType, cdef.params.map(_.ident))(cdef.span)

        val autos = Nil
        val receiveParams = None
        Some(FunDef(id, cdef.tparams, cdef.params, autos, tp, receiveParams, body, preParamCount = 0)(cdef.span))

    // Generate pattern definition (only if class has parameters)
    val pdef =
      if !isDataClass then None
      else
        val pat =
          val o = Ident("$o")(id.span)
          val assignments = cdef.params.map { param =>
            (param.ident, Select(o, param.name)(param.span))
          }
          AssignPattern(o, assignments)(cdef.span)

        val body = Case(pat, Block(Nil)(id.span))(cdef.span) :: Nil
        Some(PatDef(id, cdef.tparams, cdef.params, tp, body, preParamCount = 0)(cdef.span))

    cdef2 :: fdef.toList ::: pdef.toList

  /** Internal helper to desugar a class definition's structure
    *
    * This handles:
    * - Moving class parameters to val fields
    * - Desugaring views
    * - Moving val initializers to constructor
    * - Creating or updating the constructor method
    *
    * This is kept separate from synthesizeClassDef so it can be called from both
    * the synthesize phase and when processing nested classes.
    */
  private def desugarClassDefInternal(cdef: ClassDef)(using Reporter, Source): ClassDef =
    val vals = new mutable.ArrayBuffer[ValDef]
    val initializers = new mutable.ArrayBuffer[Word]

    val span = cdef.ident.span
    val thisIdent = Ident("this")(span)

    // Check if constructor already exists
    val existingCtor = cdef.funs.find(_.name == cdef.name)

    if existingCtor.isDefined && cdef.params.nonEmpty then
      Reporter.error(s"Constructor ${cdef.name} already exists, class parameters will be ignored", cdef.pos)
    else
      // Create val fields for each parameter
      for param <- cdef.params do
        vals += ValDef(param.ident, param.tpt, Block(Nil)(param.span), mutable = false)(param.span)

        // class parameters are always first initialized
        val lhs = Select(thisIdent, param.name)(param.span)
        val rhs = param.ident
        initializers += Assign(lhs, rhs)(param.span)

    def desugarValDef(vdef: ValDef): Unit =
      if vdef.rhs.isEmptyBlock then
        vals += vdef
      else
        vals += vdef.copy(rhs = Block(Nil)(vdef.span))(vdef.span)
        val lhs = Select(thisIdent, vdef.name)(vdef.span)
        initializers += Assign(lhs, vdef.rhs)(vdef.span)

    // Process views: desugar and move RHS to initializers
    for
      vdecl <- cdef.views
      vdef <- desugarView(vdecl)
    do
      desugarValDef(vdef)

    // Process existing fields: move RHS to initializers
    for vdef <- cdef.vals do desugarValDef(vdef)

    existingCtor match
      case Some(ctor) =>
        // Prepend field initializations to existing constructor body
        val newBody = ctor.body match
          case Block(stats) => Block(initializers.toList ++ stats)(ctor.body.span)
          case single => Block(initializers.toList :+ single)(ctor.body.span)

        val ctor2 = ctor.copy(body = newBody)(ctor.span)
        val funs2 = ctor2 :: cdef.funs.filter(_.name != cdef.name)
        cdef.copy(params = Nil, views = Nil, vals = vals.toList, funs = funs2)(cdef.span)

      case None =>
        // Generate constructor with field initializations
        val ctor = FunDef(
          cdef.ident,
          Nil,  // no type params
          cdef.params,
          Nil,  // no autos
          EmptyTypeTree()(cdef.ident.span),  // result type inferred
          None,  // infer effects
          Block(initializers.toList)(cdef.ident.span),
          preParamCount = 0
        )(cdef.span)

        // Return new ClassDef with empty params and views (they've been desugared)
        cdef.copy(params = Nil, views = Nil, vals = vals.toList, funs = ctor :: cdef.funs)(cdef.span)

  /* Desugaring for an optional context parameter
   *
   *    <Context> <Default> param a: T
   *
   *    <Default> fun a$default = rhs
   */
  def desugarParamDef(pdef: ParamDef): List[Def] =
    val paramId = pdef.ident
    val paramType = pdef.tpt

    lazy val defaultId = Ident(pdef.name + "$default")(paramId.span)

    def createDefaultFun(rhs: Word): FunDef =
      val tparams = Nil
      val params = Nil
      val autos = Nil
      val receives = Some(Nil) // no context params allowed for default

      val fdef = FunDef(defaultId, tparams, params, autos, paramType, receives, rhs, preParamCount = 0)(pdef.span)
      fdef.addKey(ExtraFlags, Flags.Default)
      fdef

    pdef.default match
      case None => pdef :: Nil

      case Some(rhs) =>
        val pdef2 = pdef.copy(default = None)(pdef.span)
        pdef2.addKey(ExtraFlags, Flags.Default)
        pdef2 :: createDefaultFun(rhs) :: Nil

  /* Desugaring views
   *
   * 1. Direct views
   *
   *    From
   *
   *        view T
   *
   *    to
   *
   *        <View> def N: T = ...
   *
   *    where N is the name after stripping type parameters. It is an error if T
   *    is neither of the form `X` nor `F[..]`.
   *
   * 2. Delegate views
   *
   *    From
   *
   *        view T = expr
   *
   *    to
   *
   *        <View> val N: T = expr
   *
   *    where N is the name after stripping type parameters. It is an error if T
   *    is neither of the form `X` nor `F[..]`.
   */
  def desugarView(vdecl: ViewDecl)(using Reporter, Source): List[ValDef] =
    // Extract type name from TypeTree (strip type parameters)
    val typeNameOpt: Option[String] = vdecl.tpe match
      case id: Ident => Some(id.name)
      case Select(_, name) => Some(name)
      case AppliedType(tpeCtor: Ident, _) => Some(tpeCtor.name)
      case AppliedType(Select(_, name), _) => Some(name)
      case _ => None


    typeNameOpt match
      case None =>
        Reporter.error("View type must be an identifier or applied type", vdecl.pos)
        Nil

      case Some(name) =>
        val viewId = Ident(name)(vdecl.span)

        vdecl.rhs match
          case None =>
            // Direct view: create view field
            // <View><Defer> val N: T = ...
            // Body is a placeholder, will be synthesized during type checking
            val body = Ident("...")(vdecl.span)
            val vdef = ValDef(viewId, vdecl.tpe, body, mutable = false)(vdecl.span)
            vdef.addKey(ExtraFlags, Flags.View | Flags.Defer)
            vdef :: Nil

          case Some(expr) =>
            // Delegate view: create view field
            // <View> val N: T = expr
            val vdef = ValDef(viewId, vdecl.tpe, expr, mutable = false)(vdecl.span)
            vdef.addKey(ExtraFlags, Flags.View)
            vdef :: Nil

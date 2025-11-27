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
        case ddef: DataDef  => synthesizeDataDef(ddef)
        case edef: EnumDef  => synthesizeEnumDef(edef)
        case pdef: ParamDef => desugarParamDef(pdef)
        case cdef: ClassDef => desugarClassDef(cdef) :: Nil
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
    *     fun A[X, ...](x1: T1, ...): A = #A x1 ...
    *
    *     pattern A[X, ...](x1: T1, ...): A = case #A x1 ...
    *
    */
  def synthesizeDataDef(ddef: DataDef)(using Reporter, Source): List[Def] =
    val id = ddef.ident

    val tp =
      if ddef.tparams.isEmpty then id
      else AppliedType(id, ddef.tparams.map(_.ident))(id.span | ddef.tparams.last.span)

    val tagType = TagType(id, ddef.params)(ddef.span)
    val tdef = TypeDef(ddef.ident, ddef.tparams, tagType, isBound = false, preParamCount = 0)(ddef.span)

    val fdef =
      val body = Apply(Tag(id)(id.span), ddef.params.map(_.ident), Nil)(ddef.span)
      val autos = Nil
      val receiveParams = Some(Nil)
      FunDef(id, ddef.tparams, ddef.params, autos, tp, receiveParams, body, preParamCount = 0)(ddef.span)

    val pdef =
      val pat =
        val tag = Tag(id)(id.span)
        if ddef.params.isEmpty then tag
        else Apply(tag, ddef.params.map(_.ident), Nil)(ddef.span)
      val body = Case(pat, Block(Nil)(id.span))(ddef.span) :: Nil
      PatDef(id, ddef.tparams, ddef.params, tp, body, preParamCount = 0)(ddef.span)


    tdef :: fdef :: pdef :: Nil

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
  def synthesizeEnumDef(enumDef: EnumDef)(using Reporter, Source): List[Def] =
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
      val traverser = new ast.TreeOps.TypeTreeTraverser:
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
    val tdef = TypeDef(enumDef.ident, enumDef.tparams, unionType, isBound = false, preParamCount = 0)(enumDef.span)
    tdef :: dataDefs.toList


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

  /* Desugaring views and class parameters
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
   *        <Private> val N$cache: T = expr
   *        <View> def N: T = N$cache
   *
   *    where N is the name after stripping type parameters. It is an error if T
   *    is neither of the form `X` nor `F[..]`.
   *
   * 3. Class parameters
   *
   *    From
   *
   *        class A[T](x: T, y: S)
   *
   *    to
   *
   *        class A[T]
   *          val x: T
   *          val y: S
   *
   *          def A(x: T, y: S) = { x = x, y = y }
   *
   *    It is an error if a constructor (fun with the name A) alreay exists and
   *    class params are not empty. In this case, an error will be reported and
   *    the class params will be simply ignored.
   */
  def desugarClassDef(cdef: ClassDef)(using Reporter, Source): ClassDef =
    val newMembers = new mutable.ArrayBuffer[ValDef | FunDef]

    for vdecl <- cdef.views do newMembers ++= desugarView(vdecl)

    // Check if constructor already exists
    val hasConstructor = cdef.members.exists:
      case fdef: FunDef => fdef.name == cdef.name
      case _ => false

    if hasConstructor then
      if cdef.params.nonEmpty then
        Reporter.error(s"Constructor ${cdef.name} already exists, class parameters will be ignored", cdef.pos)

      if newMembers.isEmpty then return cdef

    else
      // Create val fields for each parameter
      for param <- cdef.params do
        newMembers += ValDef(param.ident, param.tpt, Block(Nil)(param.span), mutable = false)(param.span)

      // Create constructor: def A(x: T, y: S) = { x = x, y = y }
      val args = cdef.params.map: param =>
        NamedArg(param.ident, param.ident)(param.span)

      val ctorBody = RecordLit(args)(cdef.ident.span)

      val ctor = FunDef(
        cdef.ident,
        Nil,  // no type params
        cdef.params,
        Nil,  // no autos
        EmptyTypeTree()(cdef.ident.span),  // result type
        Some(Nil),  // no receives
        ctorBody,
        preParamCount = 0
      )(cdef.span)

      newMembers += ctor

    // Return new ClassDef with empty params and views (they've been desugared)
    cdef.copy(
      params = Nil,
      views = Nil,
      members = newMembers.toList ++ cdef.members
    )(cdef.span)


  def desugarView(vdecl: ViewDecl)(using Reporter, Source): List[ValDef | FunDef] =
    // Extract type name from TypeTree (strip type parameters)
    val typeNameOpt: Option[String] = vdecl.tpe match
      case id: Ident => Some(id.name)
      case AppliedType(tpeCtor: Ident, _) => Some(tpeCtor.name)
      case _ => None


    typeNameOpt match
      case None =>
        Reporter.error("View type must be an identifier or applied type", vdecl.pos)
        Nil

      case Some(name) =>
        val viewId = Ident(name)(vdecl.span)

        vdecl.rhs match
          case None =>
            // Direct view: create view accessor
            // <View> def N: T = ...
            // Body is a placeholder, will be synthesized during type checking
            val body = Ident("...")(vdecl.span)
            val fdef = FunDef(
              viewId,
              Nil,  // no type params
              Nil,  // no params
              Nil,  // no autos
              vdecl.tpe,
              Some(Nil),  // no receives
              body,
              preParamCount = 0
            )(vdecl.span)
            fdef.addKey(ExtraFlags, Flags.View | Flags.Defer)
            fdef :: Nil

          case Some(expr) =>
            // Delegate view: create cache field and accessor
            // <Synthetic> val N$cache: T = expr
            val cacheId = Ident(name + "$cache")(vdecl.span)
            val cacheVal = ValDef(cacheId, vdecl.tpe, expr, mutable = false)(vdecl.span)
            cacheVal.addKey(ExtraFlags, Flags.Synthetic)

            // <View> def N: T = N$cache
            val accessorBody = cacheId
            val accessor = FunDef(
              viewId,
              Nil,  // no type params
              Nil,  // no params
              Nil,  // no autos
              vdecl.tpe,
              Some(Nil),  // no receives
              accessorBody,
              preParamCount = 0
            )(vdecl.span)
            accessor.addKey(ExtraFlags, Flags.View)

            cacheVal :: accessor :: Nil

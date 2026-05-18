package typing

import ast.Trees.*
import ast.Positions.Source

import sast.Flags
import sast.Symbols.Symbol

import common.KeyProps
import reporting.Reporter

import scala.collection.mutable

object Desugaring:
  val ExtraFlags = new KeyProps.Key[Flags]("Desugaring.ExtraFlags")

  /** Convert an expression word to a type tree, if possible.
    *
    * Handles:
    * - `RefTree` as itself
    * - `BracketApply(RefTree, args)` as `AppliedType`
    * - `PrefixOperatorCall(op, arg)` as unary `AppliedType`
    * - union-shaped `Expr` as `UnionType`
    * - other `Expr` as `ExprType`
    */
  def toTypeTree(word: Word): Option[TypeTree] = word match
    case rt: RefTree =>
      Some(rt)

    case BracketApply(fun: RefTree, innerArgs) =>
      toTypeTrees(innerArgs).toOption.map(targs => AppliedType(fun, targs)(word.span))

    case PrefixOperatorCall(op, arg) =>
      toTypeTree(arg).map(tt => AppliedType(op, tt :: Nil)(word.span))

    case Expr(words) =>
      val isUnion =
        words.size >= 3 && (words(1) match
          case Ident("|") => true
          case _ => false)

      if isUnion then
        val branches = mutable.ArrayBuffer.empty[TypeTree]
        var i = 0

        while i < words.size do
          toTypeTree(words(i)) match
            case Some(tt) =>
              branches += tt
            case None =>
              return None

          i += 1

          if i < words.size then
            words(i) match
              case Ident("|") =>
              case _ =>
                return None
            i += 1

        Some(UnionType(branches.toList)(word.span))
      else
        toTypeTrees(words).toOption.map(tts => ExprType(tts)(word.span))

    case _ =>
      None

  /** Convert a list of expression words to type trees.
    *
    * Returns `Right(targs)` if all convert successfully, or `Left(badArg)` for
    * the first expression that cannot be interpreted as a type tree.
    */
  def toTypeTrees(args: List[Word]): Either[Word, List[TypeTree]] =
    val out = mutable.ArrayBuffer.empty[TypeTree]
    val iter = args.iterator

    while iter.hasNext do
      val arg = iter.next()
      toTypeTree(arg) match
        case Some(tt) => out += tt
        case None => return Left(arg)

    Right(out.toList)

  def synthesize(defs: List[Def])(using Reporter, Source): List[Def] =
    val defs2 =
      defs.flatMap:
        case edef: UnionDef  => synthesizeUnionDef(edef)
        case edef: ExtensionDef => desugarExtensionDef(edef)
        case pdef: ParamDef => desugarParamDef(pdef)
        case cdef: ClassDef => desugarDataClass(cdef, defs)
        case odef: ObjectDef => desugarObjectDef(odef)
        case defn => defn :: Nil

    if defs2.size != defs.size then synthesize(defs2) else defs2

  /** Desugar extension definitions into a generated type alias and a section.
    *
    * From
    *
    *     extension Name[T](it: Base)
    *       def m1(...) = ...
    *       def m2(...) = ...
    *
    * to:
    *
    *     type Name[T] = Base :+ [Name.m1, Name.m2]
    *
    *     section Name
    *       def [T](it: Name[T]) m1 (...) = ...
    *       def [T](it: Name[T]) m2 (...) = ...
    *
    * The Section pre-param uses the generated alias type (`Name[T]`) so that
    * sibling extension methods are visible on the receiver via dot syntax.
    * The TypeDef base uses the original annotation (`Base`) to avoid circularity.
    */
  def desugarExtensionDef(extDef: ExtensionDef): List[Def] =
    // Build alias type: Name or Name[T, ...]
    val aliasType: TypeTree =
      if extDef.tparams.isEmpty then
        Ident(extDef.ident.name)(extDef.ident.span)
      else
        val targs = extDef.tparams.map(tp => Ident(tp.name)(tp.ident.span))
        AppliedType(Ident(extDef.ident.name)(extDef.ident.span), targs)(extDef.ident.span)

    val aliasParam = Param(extDef.param.ident, aliasType)(extDef.param.span)

    // Section uses alias type as pre-param so sibling methods are visible on it
    val modifiedFuns =
      extDef.funs.map: fun =>
        val newParams = aliasParam :: fun.params
        val newTparams = extDef.tparams ++ fun.tparams
        fun.copy(
          tparams = newTparams,
          params = newParams,
          preParamCount = 1,
          preTypeParamCount = extDef.tparams.size
        )(fun.span)

    val section = Section(extDef.ident, modifiedFuns)(extDef.span).copyAttachments(extDef)

    // TypeDef uses original param type as base (not the alias — avoids circularity)
    val methodRefs: List[(RefTree, Boolean) | RefTree] =
      extDef.funs.map: fun =>
        Select(extDef.ident, fun.ident.name)(fun.ident.span)

    val extType = ExtensionType(extDef.param.tpt, methodRefs)(extDef.ident.span)
    val tdef = TypeDef(extDef.ident, extDef.tparams, extType, preParamCount = 0)(extDef.ident.span)
      .copyAttachments(extDef)

    tdef :: section :: Nil

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
    *     object C
    *
    * where `U, ...` is a subset of `X, ...` that appear in the branch
    * `A`. Similarly for `V, ...`.
    *
    * The classes are then processed by desugerClassDef which generates
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
    val classDefs = new mutable.ArrayBuffer[ClassDef | ObjectDef]

    for classDef <- enumDef.branches do
      val typeParamRefs = mutable.Set.empty[String]
      val traverser = new ast.TreeOps.TypeTreeTraverser:
        def apply(tpt: TypeTree): Unit =
          tpt match
            case Ident(name) =>
              if typeParams.contains(name) then
                typeParamRefs += name

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

      if classDef.params.isEmpty then
        val objDef = ObjectDef(classDef.ident, views = Nil, funs = classDef.funs)(classDef.span)
        classDefs += objDef
      else
        val updatedClassDef = classDef.copy(tparams = tparamsReferred)(classDef.span)
        classDefs += updatedClassDef
    end for

    val unionType = UnionType(branchTypes.toList)(enumDef.span)

    if enumDef.funs.nonEmpty then
      // Desugar methods via ExtensionDef with unionType as the receiver type.
      // desugarExtensionDef will:
      //   - use unionType as the TypeDef base (non-circular)
      //   - use the alias Name[T] as the Section pre-param (cross-method calls work)
      val thisParam = Param(Ident("this")(enumDef.span), unionType)(enumDef.span)
      val extDef = ExtensionDef(enumDef.ident, enumDef.tparams, thisParam, enumDef.funs)(enumDef.span)
        .copyAttachments(enumDef)

      desugarExtensionDef(extDef) ++ classDefs.toList

    else
      val tdef = TypeDef(enumDef.ident, enumDef.tparams, unionType, preParamCount = 0)(enumDef.span)
          .copyAttachments(enumDef)

      tdef :: classDefs.toList

  /** Desugar a data class definition
    *
    * Given a data class
    *
    *     class A[X, ...](x1: T1, ...)
    *
    * It will desugar to
    *
    *     class A[X, ...](x1: T1, ...)
    *
    *     def A[X, ...](x1: T1, ...): A[X, ...] = new A[X, ...](x1, ...)
    *
    *     pattern A[X, ...](x1: T1, ...): A[X, ...] = case o then x1 = o.x1, ...
    *
    * A data class is a class defined with class parameters but without fields
    * declared in class body.
    *
    * The desguaring of the class itself is delayed during type checking.
    */
  def desugarDataClass(cdef: ClassDef, defs: List[Def]): List[Def] =
    val id = cdef.ident

    val tp =
      if cdef.tparams.isEmpty then id
      else AppliedType(id, cdef.tparams.map(_.ident))(id.span | cdef.tparams.last.span)

    val isDataClass =
      cdef.params.nonEmpty &&
      cdef.vals.isEmpty &&
      // If a member constructor exists, class-parameter data-class synthesis
      // must be disabled to avoid conflicting constructor/pattern generation.
      !cdef.funs.exists(_.name == id.name)

    val hasConstructorFun = defs.exists:
      case fdef: FunDef => fdef.name == id.name
      case _ => false

    val hasPatDef = defs.exists:
      case pdef: PatDef => pdef.name == id.name
      case _ => false

    def createConstructorFun(): FunDef =
      val classType =
        if cdef.tparams.isEmpty then id
        else AppliedType(id, cdef.tparams.map(_.ident))(id.span | cdef.tparams.last.span)

      val body = New(classType, cdef.params.map(_.ident))(cdef.span)

      val autos = Nil
      val receiveParams = None

      FunDef(id, cdef.tparams, cdef.params, autos, tp, receiveParams, body, preParamCount = 0, preTypeParamCount = 0)(cdef.span)
          .copyAttachments(cdef)

    def createPatternDef(): PatDef =
      val pat =
        if cdef.params.isEmpty then
          Ident("_")(id.span)

        else
          val o = Ident("$o")(id.span)
          val assignments = cdef.params.map: param =>
            (param.ident, Select(o, param.name)(param.span))

          AssignPattern(o, assignments)(cdef.span)

      val body = Case(pat, Block(Nil)(id.span))(cdef.span) :: Nil
      PatDef(id, cdef.tparams, cdef.params, tp, body, preParamCount = 0)(cdef.span)
          .copyAttachments(cdef)

    var res: List[Def] = Nil

    // Generate standalone constructor function
    if isDataClass then
      if !hasConstructorFun then
        res = createConstructorFun() :: res

      if !hasPatDef then
        res = createPatternDef() :: res

    cdef :: res

  /** Desugar an object definition
    *
    *     object A
    *       def foo(): T = ...
    *       view I
    *     end
    *
    * desugars to:
    *
    *     def A: A = ...
    *
    *     pattern A: A = case _
    *
    *     class A
    *       def foo(): T = ...
    *       view I
    *     end
    */
  def desugarObjectDef(odef: ObjectDef)(using Reporter, Source): List[Def] =
    val id = odef.ident

    Checker.checkModifiers(odef)

    // Create the class definition (no type params, no params, no vals)
    val classDef = ClassDef(id, Nil, Nil, odef.views, Nil, odef.funs)(odef.span).copyAttachments(odef)

    classDef.addKey(ExtraFlags, Flags.Object)

    // Create singleton instance: def A: A = ...
    val objAccessor =
      val body = Ident("...")(id.span)
      val autos = Nil
      val receiveParams = None
      FunDef(id, Nil, Nil, autos, id, receiveParams, body, preParamCount = 0, preTypeParamCount = 0)(odef.span)
          .copyAttachments(odef)

    objAccessor.addKey(ExtraFlags, Flags.Object)

    // Create pattern: pattern A: A = case _
    val patternDef =
      val pat = Ident("_")(id.span)
      val body = Case(pat, Block(Nil)(id.span))(odef.span) :: Nil
      PatDef(id, Nil, Nil, id, body, preParamCount = 0)(odef.span)
          .copyAttachments(odef)

    List(objAccessor, patternDef, classDef)

  /** Desugar a class definition's structure
    *
    * - Moving class parameters to val fields
    * - Desugaring views
    * - Moving val initializers to constructor
    * - Creating or updating the constructor method
    *
    */
  def desugarClassDef(cdef: ClassDef, self: Symbol)(using Reporter, Source): ClassDef =
    val vals = new mutable.ArrayBuffer[ValDef]
    val initializers = new mutable.ArrayBuffer[Word]

    val span = cdef.ident.span
    val thisIdent = This(span)
    thisIdent.addKey(Namer.TypedWord, sast.Trees.Ident(self)(span))

    // Check if constructor already exists
    val existingCtor = cdef.funs.find(_.name == cdef.name)

    if existingCtor.isDefined && cdef.params.nonEmpty then
      val paramSpan = cdef.params.head.span | cdef.params.last.span
      Reporter.error(
        s"Constructor ${cdef.name} already exists, choose either class parameters or explicit constructor",
        paramSpan.toPos)
      // Stop class-parameter desugaring after this primary error to avoid
      // cascading diagnostics from partially desugared constructor/field state.
      return cdef.copy(params = Nil)(cdef.span)

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
        vals += vdef
        val lhs = Select(thisIdent, vdef.name)(vdef.span)
        initializers += Assign(lhs, vdef.rhs)(vdef.span)

    // Process views: desugar and move RHS to initializers
    // Direct views (without RHS) are kept in cdef.views for Namer
    // Delegate views (with RHS) are desugared to fields
    for
      vdecl <- cdef.views
      vdef <- desugarView(vdecl)
    do
      desugarValDef(vdef)

    // Keep only direct views (without RHS) in the ClassDef
    val directViews = cdef.views.filter(_.rhs.isEmpty)

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
        cdef.copy(params = Nil, views = directViews, vals = vals.toList, funs = funs2)(cdef.span)

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
          preParamCount = 0,
          preTypeParamCount = 0
        )(cdef.span)

        // Return new ClassDef with empty params, direct views preserved
        cdef.copy(params = Nil, views = directViews, vals = vals.toList, funs = ctor :: cdef.funs)(cdef.span)

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

      val fdef = FunDef(defaultId, tparams, params, autos, paramType, receives, rhs, preParamCount = 0, preTypeParamCount = 0)(pdef.span)
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
   *    Direct views are NOT desugared into fields. They remain in the ClassDef's
   *    views list so that Namer can collect them and store them in ClassInfo.directViews.
   *    This enables subtyping: C <: T for classes with direct views.
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
    vdecl.rhs match
      case None =>
        // Direct view: don't create a field, keep in ClassDef.views for Namer
        Nil

      case Some(expr) =>
        // Delegate view: create view field
        // <View> val N: T = expr

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
            val vdef = ValDef(viewId, vdecl.tpe, expr, mutable = false)(vdecl.span)
            vdef.addKey(ExtraFlags, Flags.View)
            vdef :: Nil

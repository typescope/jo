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

  def synthesize(defs: List[Def])(using Reporter, Source): List[Def] =
    val defs2 =
      defs.flatMap:
        case edef: UnionDef  => synthesizeUnionDef(edef)
        case pdef: ParamDef => desugarParamDef(pdef)
        case cdef: ClassDef => desugarDataClass(cdef, defs)
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
    * A data class is a class defined with class parameters or a class without
    * fields and methods.
    *
    * The desguaring of the class itself is delayed during type checking.
    */
  def desugarDataClass(cdef: ClassDef, defs: List[Def])(using Reporter, Source): List[Def] =
    val id = cdef.ident

    val tp =
      if cdef.tparams.isEmpty then id
      else AppliedType(id, cdef.tparams.map(_.ident))(id.span | cdef.tparams.last.span)

    val mods = cdef.modifiers.filter(_.isInstanceOf[Modifier.Private])

    val isDataClass = cdef.params.nonEmpty || cdef.vals.isEmpty && cdef.funs.isEmpty

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

      FunDef(id, cdef.tparams, cdef.params, autos, tp, receiveParams, body, preParamCount = 0)(cdef.span).withMods(mods)

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
      PatDef(id, cdef.tparams, cdef.params, tp, body, preParamCount = 0)(cdef.span).withMods(mods)

    // Check if class has a copy method
    val hasCopyMethod = cdef.funs.exists(_.name == "copy")

    // Find existing companion section
    val existingSection = defs.collectFirst:
      case sec: Section if sec.name == id.name => sec

    // Check if section has a Copy class
    val hasCopyClass = existingSection.exists: sec =>
      sec.defs.exists:
        case cdef: ClassDef => cdef.name == "Copy"
        case _ => false

    def createCopyClass(): ClassDef =
      val copyClassName = "Copy" // Constructor method name

      // Generate private var fields: private var _fieldName: Type
      val privateModifier = Modifier.Private(None)(id.span)
      val fields = cdef.params.map: param =>
        val fieldName = Ident("_" + param.name)(param.span)
        ValDef(fieldName, param.tpt, Block(Nil)(param.span), mutable = true)(param.span)
          .withMods(List(privateModifier))

      // Copy class type reference (just "Copy" with type params)
      val copyClassType =
        if cdef.tparams.isEmpty then Ident(copyClassName)(id.span)
        else AppliedType(Ident(copyClassName)(id.span), cdef.tparams.map(_.ident))(id.span)

      // Generate constructor: def ClassNameCopy(param1: T1, ...): Unit = ...
      val ctorName = Ident(copyClassName)(id.span)
      val ctorBody = Block(
        cdef.params.map: param =>
          val lhs = Select(Ident("this")(param.span), "_" + param.name)(param.span)
          Assign(lhs, param.ident)(param.span)
      )(id.span)

      val ctor = FunDef(
        ctorName,
        Nil, // no type params
        cdef.params,
        Nil, // no autos
        EmptyTypeTree()(id.span),
        None, // no receives
        ctorBody,
        preParamCount = 0
      )(id.span)

      // Generate setter methods: def fieldName(value: Type): ClassNameCopy[T, ...] = ...
      val setters = cdef.params.map: param =>
        val setterName = Ident(param.name)(param.span)
        val valueParam = Param(Ident("value")(param.span), param.tpt)(param.span)
        val setterBody = Block(List(
          Assign(
            Select(Ident("this")(param.span), "_" + param.name)(param.span),
            Ident("value")(param.span)
          )(param.span),
          Ident("this")(param.span)
        ))(param.span)

        FunDef(
          setterName,
          Nil, // no type params
          List(valueParam),
          Nil, // no autos
          copyClassType, // ClassNameCopy[T, ...]
          None, // no receives
          setterBody,
          preParamCount = 0
        )(param.span)

      // Generate done method: def done: ClassName[T, ...] = new ClassName[T, ...](_field1, _field2, ...)
      val doneIdent = Ident("done")(id.span)
      val doneArgs = cdef.params.map: param =>
        Ident("_" + param.name)(param.span)
      val doneBody = New(tp, doneArgs)(id.span)
      val doneFun = FunDef(
        doneIdent,
        Nil, // no type params
        Nil, // no params
        Nil, // no autos
        tp,
        None, // no receives
        doneBody,
        preParamCount = 0
      )(id.span)

      // Create the Copy class with type parameters from parent class
      ClassDef(
        Ident(copyClassName)(id.span),
        cdef.tparams, // inherit type params from parent class
        Nil, // no class params
        Nil, // no views
        fields,
        ctor :: setters ::: List(doneFun)
      )(id.span)

    def createCopyMethod(): FunDef =
      // def copy: ClassName.Copy[T, ...] = new ClassName.Copy[T, ...](field1, field2, ...)
      val copyBaseType = Select(id, "Copy")(id.span)
      val copyReturnType =
        if cdef.tparams.isEmpty then copyBaseType
        else AppliedType(copyBaseType, cdef.tparams.map(_.ident))(id.span)
      val copyArgs = cdef.params.map(_.ident)
      val copyBody = New(copyReturnType, copyArgs)(id.span)

      FunDef(
        Ident("copy")(id.span),
        Nil, // no type params
        Nil, // no params
        Nil, // no autos
        copyReturnType,
        None, // no receives
        copyBody,
        preParamCount = 0
      )(id.span)

    var res: List[Def] = Nil

    // Generate standalone constructor function
    if isDataClass then
      if !hasConstructorFun then
        res = createConstructorFun() :: res

      if !hasPatDef then
        res = createPatternDef() :: res

      // Generate Copy class and copy method if class has 2+ parameters
      // Skip if class has only 1 parameter (no benefit from copy)
      if cdef.params.size > 1 && !hasCopyMethod && !hasCopyClass then
        val copyClass = createCopyClass()
        val copyMethod = createCopyMethod()

        // Add copy method to the class
        val updatedClass = cdef.copy(funs = copyMethod :: cdef.funs)(cdef.span)

        // Create or update companion section
        val section = existingSection match
          case Some(sec) =>
            // Add Copy class to existing section
            sec.copy(defs = copyClass :: sec.defs)(sec.span)
          case None =>
            // Create new companion section
            Section(id, List(copyClass))(id.span)

        return updatedClass :: section :: res
    end if

    cdef :: res

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
    val thisIdent = Ident("this")(span)
    thisIdent.addKey(Namer.TypedWord, sast.Trees.Ident(self)(span))

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

  /** Desugar a for loop
    *
    * From:
    *   for expr_pattern in expr [if cond] do block
    *
    * To:
    *   val $iter = expr.iterator
    *   while $iter.hasNext do
    *     case expr_pattern = $iter.next
    *     if cond then block
    */
  def desugarFor(forLoop: For)(using Reporter, Source): Word =
    val For(pattern, iter, condOpt, body) = forLoop
    val span = forLoop.span

    // val $iter = iter.iterator
    val iterIdent = Ident("$iter")(iter.span)
    val iteratorCall = Select(iter, "iterator")(iter.span)
    val iterVal = ValDef(iterIdent, EmptyTypeTree()(iter.span), iteratorCall, mutable = false)(iter.span | iteratorCall.span)

    // Build while condition: $iter.hasNext
    val iterRef1 = Ident("$iter")(iter.span)
    val hasNext = Select(iterRef1, "hasNext")(iter.span)

    // Build while body: case pattern = $iter.next; [if cond then] body
    val iterRef2 = Ident("$iter")(iter.span)
    val next = Select(iterRef2, "next")(iter.span)
    val caseDef = CaseDef(pattern, next)(pattern.span | next.span)

    // Build the body of the while loop
    val whileBody = condOpt match
      case None =>
        Block(List(caseDef, body))(caseDef.span | body.span)
      case Some(cond) =>
        val ifStmt = If(cond, body, Block(Nil)(body.span))(cond.span | body.span)
        Block(List(caseDef, ifStmt))(caseDef.span | ifStmt.span)

    // Create while loop: while $iter.hasNext do whileBody
    val whileLoop = While(hasNext, whileBody)(hasNext.span | whileBody.span)

    // Return block with val definition followed by while loop
    Block(List(iterVal, whileLoop))(span)

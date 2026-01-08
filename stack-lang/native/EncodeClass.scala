package native

import ast.Positions.Source
import sast.*
import sast.Trees.*
import sast.Symbols.*
import sast.Types.*

import phases.Phase

import native.runtime.NativeRuntime

import scala.collection.mutable

/** The compiler phase encode class methods and fields
  *
  * A class instance is encoded as follows:
  *
  *     {
  *        cid = ...,
  *        a = ...,
  *        b = ...,
  *     }
  *
  * Note that all method calls and concrete interface calls are dispatched
  * statically.
  *
  * Class methods and concrete interface methods are lifted to top-level and
  * augmented with the `this` parameter.
  */
class EncodeClass(runtime: NativeRuntime)(using defn: Definitions) extends phases.Phase[EncodeClass.Context]:
  val contextObject = EncodeClass.CacheContext

  override def transform(nss: List[Namespace]): List[Namespace] =
    val methodToLiftedMap = mutable.Map.empty[Symbol, Symbol]
    val classIds = mutable.Map.empty[Symbol, Int]

    for ns <- nss yield
      given Context = EncodeClass.Context(methodToLiftedMap, classIds, ns.symbol)
      super.transformNamespace(ns)

  override def transformDefs(defs: List[Def])(using Context): List[Def] =
    defs.flatMap:
      case cdef: ClassDef => flattenClass(cdef)

      case idef: InterfaceDef => flattenInterface(idef)

      case defn => super.transformDef(defn) :: Nil

  override def transformFunDef(fdef: FunDef)(using ctx: Context): FunDef =
    val sym = fdef.symbol

    // transform object accessor -- accessor calls will be rewired in backend.
    if sym.is(Flags.Object) then
      val body = New(fdef.resultType)(fdef.body.span).select(Names.Constructor).appliedTo()
      given Context = EncodeClass.CacheContext.newContext(sym, ctx)
      val body2 = transform(body)
      fdef.copy(body = body2)(fdef.span)

    else if sym == runtime.Core_initObjects then
      val body = Ident(runtime.objectInitProcSym)(fdef.body.span).appliedTo()
      fdef.copy(body = body)(fdef.span)

    else
      super.transformFunDef(fdef)


  private def createLiftedFunSymbol(methodSym: Symbol): Symbol =
    val classSym = methodSym.owner
    val oldProcType = methodSym.info.asProcType
    val thisInfo = classSym.classInfo.self.info
    val paramInfos = NamedInfo("this", thisInfo)
    val funType = oldProcType.prepend(paramInfos :: Nil)

    TermSymbol.create(
      classSym.name + "$" + methodSym.name,
      funType,
      Flags.Fun | Flags.Synthetic,
      Visibility.Default,
      classSym.owner,
      methodSym.sourcePos
    )

  private def getClassId(cls: Symbol)(using ctx: Context): Int =
    ctx.classIds.get(cls) match
      case Some(id) => id
      case None =>
        val id = ctx.classIds.size
        ctx.classIds(cls) = id
        id

  private def getLiftedFunSymbol(methodSym: Symbol)(using ctx: Context): Symbol =
    ctx.methodToLiftedMap.get(methodSym) match
      case Some(liftedSym) =>
        liftedSym

      case None =>
        val liftedSym = createLiftedFunSymbol(methodSym)
        ctx.methodToLiftedMap(methodSym) = liftedSym
        liftedSym

  private def flattenInterface(idef: InterfaceDef)(using ctx: Context): List[Def] =
    val self = idef.self
    for fdef <- idef.methods if !fdef.symbol.is(Flags.Defer) yield
      val liftedSym = getLiftedFunSymbol(fdef.symbol)
      // TODO: type erasure to properly handle type parameters
      val body2 =
        given Context = EncodeClass.CacheContext.newContext(liftedSym, ctx)
        this.transform(fdef.body)

      FunDef(
        liftedSym, fdef.tparams,
        self :: fdef.params,
        fdef.autos, fdef.candidates,
        fdef.resultType,
        fdef.effectPolicy,
        body2
      )(fdef.span)

  private def flattenClass(cdef: ClassDef)(using ctx: Context): List[Def] =
    val self = cdef.self
    for fdef <- cdef.funs yield
      val liftedSym = getLiftedFunSymbol(fdef.symbol)
      // TODO: type erasure to properly handle type parameters
      val body2 =
        given Context = EncodeClass.CacheContext.newContext(liftedSym, ctx)
        this.transform(fdef.body)
      FunDef(
        liftedSym, fdef.tparams,
        self :: fdef.params,
        fdef.autos, fdef.candidates,
        fdef.resultType,
        fdef.effectPolicy,
        body2
      )(fdef.span)

  override def transformEncoded(encoded: Encoded)(using ctx: Context): Word =
    val Encoded(repr) = encoded

    // Encode closure as { apply = ..., underlying = ... }
    if encoded.tpe.isLambdaType && repr.tpe.isClassType then
      val repr2 = super.transform(repr)

      val classInfo = repr.tpe.asClassInfo

      val applySym = classInfo.classSymbol.termMember(Memory.Apply)
      val liftedApplySym = getLiftedFunSymbol(applySym)

      val underlying = Memory.Underlying -> repr2
      val apply = Memory.Apply -> Ident(liftedApplySym)(repr.span)

      val repr3 = RecordLit(apply :: underlying :: Nil)(repr.span)
      Encoded(repr3)(encoded.tpe)
    else
      super.transformEncoded(encoded)

  override def transformNew(newExpr: New)(using ctx: Context): Word =
    val classInfo = newExpr.tpe.asClassInfo
    val members = new mutable.ArrayBuffer[(String, Word)]

    val classId = getClassId(classInfo.classSymbol)
    members += Memory.ClassID -> IntLit(classId)(newExpr.span)

    for field <- classInfo.fields yield
      members += field.name -> Encoded(IntLit(0)(newExpr.span))(field.info)

    Encoded(RecordLit(members.toList)(newExpr.span))(newExpr.tpe)

  override def transformFieldAssign(assign: FieldAssign)(using Context): Word =
    val FieldAssign(lhs @ Select(qual, name), rhs) = assign
    val viewFieldType = lhs.tpe
    viewFieldType match
      case MemberRef(_, sym) if sym.isAllOf(Flags.View | Flags.Defer) =>
        // Create interface object, see Memory.encodeInterfaceType
        val classInfo = sym.owner.classInfo
        val interfaceInfo = viewFieldType.asClassInfo
        val members = new mutable.ArrayBuffer[(String, Word)]
        for meth <- interfaceInfo.methods if meth.is(Flags.Defer) do
          val memberRef =
            classInfo.getMemberSymbol(meth.name) match
              case Some(sym) => getLiftedFunSymbol(sym)
              case None => throw new Exception("Implementation missing for " + meth + " in class " + sym.owner)

          members += meth.name -> Ident(memberRef)(assign.span)
        end for

        val vtable = RecordLit(members.toList)(assign.span)
        val encoding = RecordLit(
          (Memory.VTable, vtable)
          :: (Memory.Underlying, Ident(classInfo.self)(assign.span))
          :: Nil
        )(assign.span)

        assign.copy(rhs = Encoded(encoding)(viewFieldType.widenTermRef))

      case _ =>
        super.transformFieldAssign(assign)

  override def transformApply(apply: Apply)(using ctx: Context): Word =
    val Apply(fun, args, autos) = apply

    val args2 = args.map(this.transform)
    val autos2 = autos.map(this.transform)

    /** Deferred interface calls have special semantics */
    def isAbstractInterfaceMethod(refType: Type): Boolean =
      refType match
        case MemberRef(_, sym) => sym.owner.isInterface && sym.is(Flags.Defer)
        case _ => false

    def rewriteApply(receiverRef: Word, name: String, targs: List[TypeTree]): Word =
      val memberRef = receiverRef.tpe.termMember(name).as[RefType]
      val isAbstractCall = isAbstractInterfaceMethod(memberRef)

      val procType =
        if targs.isEmpty then memberRef.asProcType
        else memberRef.asProcType.instantiate(targs.map(_.tpe))

      val liftedFun =
        if isAbstractCall then
          receiverRef.select(name)
        else
          Ident(getLiftedFunSymbol(memberRef.symbol))(fun.span)

      val liftedProcType =
        if isAbstractCall then
          // The `this` of an abstract interface method is the implementation class
          procType.prepend(NamedInfo("this", AnyType) :: Nil)

        else
          procType.prepend(NamedInfo("this", receiverRef.tpe.widen) :: Nil)

      val liftedFunEncoded = Encoded(liftedFun)(liftedProcType)

      val thisObj =
        if isAbstractCall then
          val interfaceInfo = memberRef.symbol.owner.classInfo
          val recordType = Memory.encodeInterfaceType(interfaceInfo)
          Encoded(receiverRef)(recordType).select(Memory.Underlying)
        else
          receiverRef

      Apply(liftedFunEncoded, thisObj :: args2, autos2)(apply.span)


    fun match
      case Select(qual, name) if qual.tpe.isClassInfoType =>
        // Check if this is a primitive numeric type operator
        val isPrimitiveNumeric = defn.isNumericType(qual.tpe)

        if isPrimitiveNumeric then
          // Transform to runtime primitive function call
          val section =
            if qual.tpe.isSubtype(defn.IntType) then runtime.Core_IntOps
            else if qual.tpe.isSubtype(defn.ByteType) then runtime.Core_ByteOps
            else if qual.tpe.isSubtype(defn.CharType) then runtime.Core_CharOps
            else runtime.Core_FloatOps

          // Try to find operator in section - use operator name directly
          val primitiveSym = section.termMember(name)
          val qual2 = this(qual)
          Apply(Ident(primitiveSym)(fun.span), qual2 :: args2, autos2)(apply.span)

        else
          val qual2 = this(qual)

          if qual2.isIdempotent then
            rewriteApply(qual2, name, targs = Nil)

          else
            val receiverSym =
              val owner = ctx.owner
              given Source = owner.sourcePos.source
              TermSymbol.create("o", qual2.tpe, Flags.Synthetic, Visibility.Default, owner, qual2.pos)

            val receiver = Ident(receiverSym)(qual2.span)
            val assign = Assign(Ident(receiverSym)(qual2.span), qual2)

            val apply2 = rewriteApply(receiver, name, targs = Nil)
            Block(assign :: apply2 :: Nil)(apply.span)

      case TypeApply(Select(qual, name), targs) if qual.tpe.isClassInfoType =>
        // TODO: after type erasure, the special handling here can be removed
        val qual2 = this(qual)

        if qual2.isIdempotent then
          rewriteApply(qual2, name, targs)

        else
          val receiverSym =
            val owner = ctx.owner
            given Source = owner.sourcePos.source
            TermSymbol.create("o", qual2.tpe, Flags.Synthetic, Visibility.Default, owner, qual2.pos)

          val receiver = Ident(receiverSym)(qual2.span)
          val assign = Assign(Ident(receiverSym)(qual2.span), qual2)

          val apply2 = rewriteApply(receiver, name, targs)
          Block(assign :: apply2 :: Nil)(apply.span)

      case Encoded(lambda) if lambda.tpe.isLambdaType =>
        val encodedType = Memory.encodeLambdaType(lambda.tpe.asLambdaType)

        val lambda2 = this(lambda)
        val qual2 = Encoded(lambda2)(encodedType)

        def rewriteApply(lambdaEncoded: Word): Word =
          val procType = lambdaEncoded.tpe.termMember(Memory.Apply).asProcType

          val liftedFun = lambdaEncoded.select(Memory.Apply)
          val thisObj = lambdaEncoded.select(Memory.Underlying)

          val liftedProcType =
              // The `this` of an abstract interface method is the implementation class
              procType.prepend(NamedInfo("this", AnyType) :: Nil)

          val liftedFunEncoded = Encoded(liftedFun)(liftedProcType)

          Apply(liftedFunEncoded, thisObj :: args2, autos2)(apply.span)

        if lambda2.isIdempotent then
          rewriteApply(qual2)

        else
          val receiverSym =
            val owner = ctx.owner
            given Source = owner.sourcePos.source
            TermSymbol.create("o", qual2.tpe, Flags.Synthetic, Visibility.Default, owner, qual2.pos)

          val receiver = Ident(receiverSym)(qual2.span)
          val assign = Assign(Ident(receiverSym)(qual2.span), qual2)

          val apply2 = rewriteApply(receiver)
          Block(assign :: apply2 :: Nil)(apply.span)

      case TypeApply(Ident(sym), tpt :: Nil) if sym == defn.Internal_typeTest =>
        assert(args2.size == 1, "Unexpected number of args for typeTest: " + args2)
        assert(autos2.isEmpty, "Unexpected autos for typeTest: " + autos2)

        val arg2 = args2.head

        val classIdRecordType = RecordType(NamedInfo(Memory.ClassID, defn.IntType) :: Nil)

        // Handle type test
        val classInfo = tpt.tpe.asClassInfo
        val cls = classInfo.classSymbol

        val valueClassId = Encoded(arg2)(classIdRecordType).select(Memory.ClassID)

        if cls == defn.String_String then
          // String type is represented by union type Raw | Concat
          val classId1 = IntLit(getClassId(runtime.Core_String_Raw))(tpt.span)
          val classId2 = IntLit(getClassId(runtime.Core_String_Concat))(tpt.span)
          val test1 = transform(valueClassId.isEqualTo(classId1))
          val test2 = transform(valueClassId.isEqualTo(classId2))
          Ident(defn.Bool_either)(fun.span).appliedTo(test1, test2)

        else if cls == defn.Int_Int then
          val classId = IntLit(getClassId(runtime.Core_IntBox))(tpt.span)
          transform(valueClassId.isEqualTo(classId))

        else if cls == defn.Byte_Byte then
          val classId = IntLit(getClassId(runtime.Core_ByteBox))(tpt.span)
          transform(valueClassId.isEqualTo(classId))

        else if cls == defn.Char_Char then
          val classId = IntLit(getClassId(runtime.Core_CharBox))(tpt.span)
          transform(valueClassId.isEqualTo(classId))

        else if cls == defn.Float_Float then
          val classId = IntLit(getClassId(runtime.Core_FloatBox))(tpt.span)
          transform(valueClassId.isEqualTo(classId))

        else
          val classId = IntLit(getClassId(cls))(tpt.span)
          transform(valueClassId.isEqualTo(classId))

      case _ =>
        // global function call
        Apply(fun, args2, autos2)(apply.span)

object EncodeClass:
  class Context(val methodToLiftedMap: mutable.Map[Symbol, Symbol], val classIds: mutable.Map[Symbol, Int], val owner: Symbol)
  object CacheContext extends Phase.ContextObject[Context]:
    def newContext(owner: Symbol, old: Context) = Context(old.methodToLiftedMap, old.classIds, owner)
    def newContext(namespace: Symbol) = throw new Exception("Namespace context should use global symbol map")

package phases

import sast.*
import sast.Trees.*
import sast.Symbols.*
import sast.Types.*

import ast.Positions.Source

import scala.collection.mutable

/**
  * Rewrite concrete interface method calls
  */
class MaterializeView(using defn: Definitions) extends phases.Phase[MaterializeView.Context]:
  val contextObject = MaterializeView.CacheContext

  override def transform(units: List[FileUnit]): List[FileUnit] =
    val methodToLiftedMap = mutable.Map.empty[Symbol, Symbol]
    for unit <- units yield
      given Context = MaterializeView.Context(methodToLiftedMap, unit.owner)
      super.transformFileUnit(unit)

  override def transformDefs(defs: List[Def])(using Context): List[Def] =
    defs.flatMap:
      case idef: InterfaceDef => flatten(idef)

      case defn => super.transformDef(defn) :: Nil

  private def createLiftedFunSymbol(methodSym: Symbol): Symbol =
    val interfaceSym = methodSym.owner
    val oldProcType = methodSym.info.asProcType
    val thisInfo = interfaceSym.classInfo.self.info
    val paramInfos = NamedInfo("this", thisInfo)
    val funType = oldProcType.prepend(paramInfos :: Nil)

    TermSymbol.create(
      interfaceSym.name + "$" + methodSym.name,
      funType,
      Flags.Fun | Flags.Synthetic,
      Visibility.Default,
      interfaceSym.owner,
      methodSym.sourcePos
    )

  private def getLiftedFunSymbol(methodSym: Symbol)(using ctx: Context): Symbol =
    ctx.methodToLiftedMap.get(methodSym) match
      case Some(liftedSym) =>
        liftedSym

      case None =>
        val liftedSym = createLiftedFunSymbol(methodSym)
        ctx.methodToLiftedMap(methodSym) = liftedSym
        liftedSym

  private def flatten(idef: InterfaceDef)(using ctx: Context): List[Def] =
    val self = idef.self
    for fdef <- idef.methods if !fdef.symbol.is(Flags.Defer) yield
      val liftedSym = getLiftedFunSymbol(fdef.symbol)
      // TODO: type erasure to properly handle type parameters
      val body2 =
        given Context = MaterializeView.CacheContext.newContext(liftedSym, ctx)
        this.transform(fdef.body)

      FunDef(
        liftedSym, fdef.tparams,
        self :: fdef.params,
        fdef.autos, fdef.candidates,
        fdef.resultType,
        fdef.effectPolicy,
        body2
      )(fdef.span)

  override def transformApply(apply: Apply)(using ctx: Context): Word =
    val Apply(fun, args, autos) = apply

    val args2 = args.map(this.transform)
    val autos2 = autos.map(this.transform)

    def isConcreteInterfaceMethod(refType: Type): Boolean =
      refType match
        case MemberRef(_, sym) => sym.owner.isInterface && !sym.is(Flags.Defer)
        case _ => false

    def getFunTarget(receiverRef: Word, name: String, targs: List[TypeTree]): Word =
      val memberRef = receiverRef.tpe.termMember(name).as[RefType]

      val procType =
        if targs.isEmpty then memberRef.asProcType
        else memberRef.asProcType.instantiate(targs.map(_.tpe))

      val liftedFun = Ident(getLiftedFunSymbol(memberRef.symbol))(fun.span)

      val liftedProcType = procType.prepend(NamedInfo("this", receiverRef.tpe.widen) :: Nil)

      Encoded(liftedFun)(liftedProcType)

    fun match
      case Select(qual, name) if isConcreteInterfaceMethod(fun.tpe) =>
        val qual2 = this(qual)

        if qual2.isIdempotent then
          val fun2 = getFunTarget(qual2, name, targs = Nil)
          Apply(fun2, qual2 :: args2, autos2)(apply.span)

        else
          val receiverSym =
            val owner = ctx.owner
            given Source = owner.sourcePos.source
            TermSymbol.create("o", qual2.tpe, Flags.Synthetic, Visibility.Default, owner, qual2.pos)

          val receiver = Ident(receiverSym)(qual2.span)
          val assign = Assign(Ident(receiverSym)(qual2.span), qual2)

          val fun2 = getFunTarget(receiver, name, targs = Nil)
          val apply2 = Apply(fun2, receiver :: args2, autos2)(apply.span)

          Block(assign :: apply2 :: Nil)(apply.span)

      case TypeApply(sel @ Select(qual, name), targs) if isConcreteInterfaceMethod(sel.tpe) =>
        // TODO: after type erasure, the special handling here can be removed
        val qual2 = this(qual)

        if qual2.isIdempotent then
          val fun2 = getFunTarget(qual2, name, targs)
          Apply(fun2, qual2 :: args2, autos2)(apply.span)

        else
          val receiverSym =
            val owner = ctx.owner
            given Source = owner.sourcePos.source
            TermSymbol.create("o", qual2.tpe, Flags.Synthetic, Visibility.Default, owner, qual2.pos)

          val receiver = Ident(receiverSym)(qual2.span)
          val assign = Assign(Ident(receiverSym)(qual2.span), qual2)

          val fun2 = getFunTarget(receiver, name, targs)
          val apply2 = Apply(fun2, receiver :: args2, autos2)(apply.span)

          Block(assign :: apply2 :: Nil)(apply.span)

      case _ =>
        // global function call
        Apply(fun, args2, autos2)(apply.span)

object MaterializeView:
  class Context(val methodToLiftedMap: mutable.Map[Symbol, Symbol], val owner: Symbol)
  object CacheContext extends phases.Phase.ContextObject[Context]:
    def newContext(owner: Symbol, old: Context) = Context(old.methodToLiftedMap, owner)
    def newContext(namespace: Symbol) = throw new Exception("Namespace context should use global symbol map")

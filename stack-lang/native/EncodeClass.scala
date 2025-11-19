package native

import ast.Positions.Source
import sast.*
import sast.Trees.*
import sast.Symbols.*
import sast.Types.*

import phases.Phase

import scala.collection.mutable

/** The compiler phase encode class methods and fields
  *
  * A class instance is encoded as follows:
  *
  *     {
  *        a = ...,
  *        b = ...,
  *        foo = ...,
  *        bar = ...,
  *     }
  *
  * Note that we duplicate the method table for each instance for simplicity.
  * We might allow different instances of the same class to have different
  * bindings for methods, e.g., creating an instance with overridings in
  * refinement.
  *
  *
  * Class methods are lifted to top-level and augmented with the `this`
  * parameter.
  */
class EncodeClass(using defn: Definitions) extends phases.Phase[EncodeClass.Context]:
  val contextObject = EncodeClass.CacheContext

  override def transform(nss: List[Namespace]): List[Namespace] =
    val methodToLiftedMap = mutable.Map.empty[Symbol, Symbol]

    for ns <- nss yield
      given Context = EncodeClass.Context(methodToLiftedMap, ns.symbol)
      super.transformNamespace(ns)

  override def transformDefs(defs: List[Def])(using Context): List[Def] =
    defs.flatMap:
      case cdef: ClassDef => flatten(cdef)

      case defn => super.transformDef(defn) :: Nil

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

  private def getLiftedFunSymbol(methodSym: Symbol)(using ctx: Context): Symbol =
    ctx.methodToLiftedMap.get(methodSym) match
      case Some(liftedSym) =>
        liftedSym

      case None =>
        val liftedSym = createLiftedFunSymbol(methodSym)
        ctx.methodToLiftedMap(methodSym) = liftedSym
        liftedSym

  private def flatten(cdef: ClassDef)(using ctx: Context): List[Def] =
    val self = cdef.self
    for fdef <- cdef.funs yield
      val liftedSym = getLiftedFunSymbol(fdef.symbol)
      // TODO: type erasure to properly handle type parameters
      val body2 =
        given Context = EncodeClass.CacheContext.newContext(liftedSym, ctx)
        this.transform(fdef.body)
      FunDef(
        liftedSym, fdef.tparams,
        self :: fdef.params, Nil :: fdef.adapters,
        fdef.autos, fdef.candidates,
        fdef.resultType,
        fdef.effectPolicy,
        body2
      )(fdef.span)

  override def transformNew(newExpr: New)(using ctx: Context): Word =
    val classInfo = newExpr.tpe.asClassInfo
    val members = new mutable.ArrayBuffer[(String, Word)]

    for field <- classInfo.fields yield
      members += field.name -> Encoded(IntLit(0)(newExpr.span))(field.info)

    for fun <- classInfo.allMethods do
      members += fun.name -> Ident(getLiftedFunSymbol(fun))(newExpr.span)

    Encoded(RecordLit(members.toList)(newExpr.span))(newExpr.tpe)

  override def transformApply(apply: Apply)(using ctx: Context): Word =
    val Apply(fun, args, autos) = apply

    val args2 = args.map(this.transform)
    val autos2 = autos.map(this.transform)

    fun match
      case Select(qual, name) if qual.tpe.isClassType =>
        val qual2 = this(qual)
        val memberRef = qual2.tpe.termMember(name).as[RefType]
        val procType = memberRef.asProcType

        val liftedFun = Ident(getLiftedFunSymbol(memberRef.symbol))(fun.span)
        val liftedProcType = procType.prepend(NamedInfo("this", qual2.tpe.widen) :: Nil)
        val liftedFunEncoded = Encoded(liftedFun)(liftedProcType)

        if qual2.isIdempotent then
          Apply(liftedFunEncoded, qual2 :: args2, autos2)(apply.span)

        else
          val receiverSym =
            val owner = ctx.owner
            given Source = owner.sourcePos.source
            TermSymbol.create("o", qual2.tpe, Flags.Synthetic, Visibility.Default, owner, qual2.pos)

          val receiver = Ident(receiverSym)(qual2.span)
          val assign = Assign(Ident(receiverSym)(qual2.span), qual2)
          val apply2 = Apply(liftedFunEncoded, receiver :: args2, autos2)(apply.span)
          Block(assign :: apply2 :: Nil)(apply.span)

      case TypeApply(sel @ Select(qual, name), targs) if qual.tpe.isClassType =>
        // TODO: after type erasure, the special handling here can be removed
        val qual2 = this(qual)

        val memberRef = qual2.tpe.termMember(name).as[RefType]
        val procType = memberRef.asProcType
        val funType = procType.instantiate(targs.map(_.tpe))

        val liftedFun = Ident(getLiftedFunSymbol(memberRef.symbol))(sel.span)
        val liftedProcType = funType.prepend(NamedInfo("this", qual2.tpe.widen) :: Nil)
        val liftedFunEncoded = Encoded(liftedFun)(liftedProcType)

        if qual2.isIdempotent then
          Apply(liftedFunEncoded, qual2 :: args2, autos2)(apply.span)

        else
          val receiverSym =
            val owner = ctx.owner
            given Source = owner.sourcePos.source
            TermSymbol.create("o", qual2.tpe, Flags.Synthetic, Visibility.Default, owner, qual2.pos)

          val receiver = Ident(receiverSym)(qual2.span)
          val assign = Assign(Ident(receiverSym)(qual2.span), qual2)

          val apply2 = Apply(liftedFunEncoded, receiver :: args2, autos2)(apply.span)
          Block(assign :: apply2 :: Nil)(apply.span)

      case _ =>
        // global function call
        Apply(fun, args2, autos2)(apply.span)

object EncodeClass:
  class Context(val methodToLiftedMap: mutable.Map[Symbol, Symbol], val owner: Symbol)
  object CacheContext extends Phase.ContextObject[Context]:
    def newContext(owner: Symbol, old: Context) = Context(old.methodToLiftedMap, owner)
    def newContext(namespace: Symbol) = throw new Exception("Namespace context should use global symbol map")

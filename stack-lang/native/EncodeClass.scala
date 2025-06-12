package native

import ast.Positions.Source
import sast.*
import sast.Sast.*
import sast.Symbols.*
import sast.Types.*

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
class EncodeClass(using defn: Definitions) extends phases.Phase[Symbol]:
  val contextObject = phases.Phase.OwnerContext

  override def transform(nss: List[Namespace]): List[Namespace] =
    defn.installTransform { symInfo =>
      val sym = symInfo.symbol
      if sym.isMethod && sym.owner.isClass then
        val oldProcType = symInfo.tpe.as[ProcType]
        val thisInfo = sym.owner.info.asClassInfo.self.info

        val paramInfos = NamedInfo("this", thisInfo)
        val funType = oldProcType.prepend(paramInfos :: Nil)
        SymInfo(sym, sym.enclosingContainer, funType)
      else
        symInfo
    }
    super.transform(nss)

  override def transformDefs(defs: List[Def])(using Context): List[Def] =
    defs.flatMap:
      case cdef: ClassDef => flatten(cdef)

      case defn => super.transformDef(defn) :: Nil

  private def flatten(cdef: ClassDef)(using Context): List[Def] =
    val self = cdef.self
    for fdef <- cdef.funs yield
      // TODO: type erasure to properly handle type parameters
      val body2 = this.transform(fdef.body)
      FunDef(fdef.symbol, fdef.tparams, self :: fdef.params, fdef.autos, fdef.resultType, body2)(fdef.span)

  override def transformNew(newExpr: New)(using ctx: Context): Word =
    val classInfo = newExpr.tpe.asClassInfo
    val members = new mutable.ArrayBuffer[(String, Word)]

    for field <- classInfo.fields yield
      members += field.name -> Encoded(IntLit(0)(newExpr.span))(field.info)

    for fun <- classInfo.methods do
      members += fun.name -> Ident(fun)(newExpr.span)

    val recordType = ObjectEncoding.encodeClassType(classInfo)
    Encoded(RecordLit(members.toList)(recordType, newExpr.span))(newExpr.tpe)

  override def transformApply(apply: Apply)(using ctx: Context): Word =
    val Apply(fun, args, autos) = apply

    val args2 = args.map(this.transform)
    val autos2 = autos.map(this.transform)

    // TODO: We can optimize by resolve the calls statically.
    fun match
      case Select(qual, name) if qual.tpe.isClassType =>
        val qual2 = this(qual)
        val procType = qual2.tpe.termMember(name).asProcType

        if qual2.isIdempotent then
          val proc = Select(qual2, name)(procType, fun.span)
          Apply(proc, qual2 :: args2, autos2)(apply.tpe, apply.span)

        else
          val receiverSym =
            val owner = ctx
            given Source = owner.sourcePos.source
            Symbol.createSymbol("o", qual2.tpe, Flags.Synthetic, owner, qual2.pos)

          val receiver = Ident(receiverSym)(qual2.span)
          val assign = Assign(Ident(receiverSym)(qual2.span), qual2)(qual2.span)
          val proc = Select(receiver, name)(procType, fun.span)
          val apply2 = Apply(proc, receiver :: args2, autos2)(apply.tpe, apply.span)
          Block(assign :: apply2 :: Nil)(apply.tpe, apply.span)

      case TypeApply(Select(qual, name), targs) if qual.tpe.isClassType =>
        // TODO: after type erasure, the special handling here can be removed
        val qual2 = this(qual)
        val procType = qual2.tpe.termMember(name).asProcType
        val funType = procType.instantiate(targs.map(_.tpe))
        if qual2.isIdempotent then
          val meth = Select(qual2, name)(procType, fun.span)
          val fun2 = TypeApply(meth, targs)(funType, fun.span)
          Apply(fun2, qual2 :: args2, autos2)(apply.tpe, apply.span)

        else
          val receiverSym =
            val owner = ctx
            given Source = owner.sourcePos.source
            Symbol.createSymbol("o", qual2.tpe, Flags.Synthetic, owner, qual2.pos)

          val receiver = Ident(receiverSym)(qual2.span)
          val assign = Assign(Ident(receiverSym)(qual2.span), qual2)(qual2.span)
          val meth = Select(receiver, name)(procType, fun.span)
          val fun2 = TypeApply(meth, targs)(funType, fun.span)
          val apply2 = Apply(fun2, receiver :: args2, autos2)(apply.tpe, apply.span)
          Block(assign :: apply2 :: Nil)(apply.tpe, apply.span)

      case _ =>
        // global function call
        Apply(fun, args2, autos2)(apply.tpe, apply.span)

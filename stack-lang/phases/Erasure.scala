package phases

import sast.*
import sast.Trees.*
import sast.Types.*
import sast.Denotations.*

/** Erase type parameters and make boxing/unboxing of primitive values explicit
  *
  * Optional: Add bridge methods to classes for boxing mismatch of abstract interface methods.
  *
  * @param primitiveTagged whether primitive values are tagged for the target platform (true for JS/Ruby/Python)
  * @param anyTagged       whether the erased result type Any is a tagged type for the target platform (true for Java/JS/Ruby/Python, false for reg/stac)
  * @param eraseUnion      whether union type should be erased to Any (true for Java)
  */
class Erasure(primitiveTagged: Boolean, anyTagged: Boolean, eraseUnion: Boolean)(using defn: Definitions) extends Phase:
  private val eraseTypeMap = new Erasure.EraseTypeMap(eraseUnion)

  override def initContext()(using Context): Unit =
    defn.index.installTransform: (_, denot) =>
      eraseDenotation(denot)

  def eraseDenotation(denot: Denotation): Denotation =
    denot match
      case info: ClassInfo =>
        var changed = false
        val directViews2 = info.directViews.map: tp =>
          val tp2 = eraseType(tp)
          changed = changed || tp2.ne(tp)
          tp2

        if !changed && info.tparams.isEmpty then return denot

        ClassInfo(
          info.classSymbol,
          Nil, // tparams
          info.self,
          info.fields,
          info.methods,
          if changed then directViews2 else info.directViews
        )

      case toi: TypeOperatorInfo =>
        val body2 = eraseType(toi.body)
        if toi.body `eq` body2 then toi
        else TypeOperatorInfo(toi.tparams, body2, toi.preParamCount)

      case tp: Type => eraseType(tp)

  def eraseType(tp: Type): Type = eraseTypeMap.apply(tp)(using ())

  def eraseWord(word: Word, expectedType: Type | Null, returnType: Type)(using Context): Word =
    // type adaptation and boxing/unboxing of primitive types
    //
    // Only nodes that may have a type of type paramter or primitive type need
    // adaptation
    def adapt(value: Word): Word =
      if expectedType == null then
        value
      else
        val needBoxing =
           value.tpe.isNumericOrBoolType
           && !primitiveTagged
           && (expectedType.isAnyType && anyTagged || expectedType.isUnionType)

        // backend will decide whether the cast involves unboxing
        val needCast = !Subtyping.conforms(value.tpe, expectedType)

        if needBoxing || needCast then Encoded(value)(expectedType) else value

    word match
      case Select(qual, name) =>
        val qual2 = eraseWord(qual, expectedType = null, returnType)
        val select2 = if qual2.eq(qual) then word else Select(qual2, name)(word.span)
        adapt(select2)

      case Encoded(repr) =>
        val repr2 = eraseWord(repr, expectedType = null, returnType)
        val tp2 = eraseType(word.tpe)
        // no adaptation needed for Encoded
        if repr2.eq(repr) && tp2.eq(word.tpe) then word
        else Encoded(repr2)(tp2)

      case apply @ Apply(fun, args, autos) =>
        val fun2 = fun match
          case TypeApply(fun, _) => eraseWord(fun, expectedType = null, returnType)
          case _ => eraseWord(fun, expectedType = null, returnType)

        val invokeType = fun2.tpe.asInvokableType

        var changed = fun2 `ne` fun

        val args2 = args.zip(invokeType.paramTypes).map: (arg, paramType) =>
          val arg2 = eraseWord(arg, paramType, returnType)
          changed ||= arg2 `ne` arg
          arg2

        val autos2 = autos.zip(invokeType.autoTypes).map: (auto, autoType) =>
          val auto2 = eraseWord(auto, autoType, returnType)
          changed ||= auto2 `ne` auto
          auto2

        val apply2 =
          if changed then
            Apply(fun2, args2, autos2)(apply.span, apply.isPartialApply)
          else
            apply

        adapt(apply2)

      case New(tpt) =>
        val tp2 = eraseType(tpt.tpe)
        if tp2.eq(tpt.tpe) then
          New(TypeTree(tp2)(tpt.span))(word.span)
        else
          word

      case Assign(id, rhs, isDefined) =>
        val rhs2 = eraseWord(rhs, id.symbol.tpe, returnType)
        if rhs.eq(rhs2) then word else Assign(id, rhs2, isDefined)

      case FieldAssign(select @ Select(qual, name), rhs) =>
        val qual2 = eraseWord(qual, expectedType = null, returnType)
        val select2 = if qual2.eq(qual) then select else Select(qual2, name)(word.span)
        val expectType = select2.tpe.widen
        val rhs2 = eraseWord(rhs, expectType, returnType)
        if select2.eq(select) && rhs2.eq(rhs) then word
        else FieldAssign(select2, rhs2)

      case fdef: FunDef => transformFunDef(fdef)

      case ifElse: If =>
        val If(cond, thenp, elsep) = ifElse
        val cond2 = eraseWord(cond, expectedType = defn.BoolType, returnType)
        val thenp2 = eraseWord(cond, expectedType, returnType)
        val elsep2 = eraseWord(cond, expectedType, returnType)

        val tp2 = eraseType(ifElse.tpe)

        // adaptation happens in each branch
        if cond2.eq(cond) && thenp2.eq(thenp) && elsep2.eq(elsep) && tp2.eq(ifElse.tpe) then
          ifElse
        else
          If(cond2, thenp2, elsep2)(tp2, ifElse.span)

      case whileDo: While =>
        val While(cond, body) = whileDo
        val cond2 = eraseWord(cond, expectedType = defn.BoolType, returnType)
        val body2 = eraseWord(body, expectedType = VoidType, returnType)
        if cond2.eq(cond) && body2.eq(body) then
          whileDo
        else
          While(cond2, body2)(whileDo.span)

      case Labeled(label, resultType, body) =>
        val resultType2 = eraseType(resultType)
        val body2 = eraseWord(body, resultType2, resultType2)

        val word2 =
          if body2.eq(body) && resultType2.eq(resultType) then
            word
          else
            Labeled(label, resultType2, body2)(word.span)

        adapt(word2)

      case ret @ Return(label, value) =>
        val value2 = eraseWord(ret.value, returnType, returnType)
        if value2.eq(value) then word
        else Return(label, value2)(word.span)

      case classTest @ ClassTest(value, cls) =>
        val value2 = eraseWord(value, expectedType = null, returnType)
        if value2.eq(value) then
          adapt(classTest)
        else
          adapt(ClassTest(value2, cls)(classTest.span))

      case Block(words) =>
        (words: @unchecked) match
          case Nil => word

          case init :+ last =>
            var changed = false
            val init2 = init.map: word =>
              val word2 = eraseWord(word, expectedType = VoidType, returnType)
              changed ||= word2 `ne` word
              word2

            // adaptation happens recursively
            val last2 = eraseWord(last, expectedType, returnType)
            changed ||= last2 `ne` last

            if changed then Block(init2 :+ last2)(word.span) else word

      case lambda @ Lambda(symbol, params, receives, body) =>
        val lambdaType = symbol.tpe.asLambdaType
        // Return may not cross lambda boundary
        val body2 = eraseWord(body, lambdaType.resultType, returnType = null)
        if body2 `ne` body then
          Lambda(symbol, params, receives, body2)(lambda.span)
        else
          lambda

      case _: Literal | _: Ident => adapt(word)

      case _: With | _: Allow | _: Match | _: PatValDef | _: RecordLit |
           _: PatDef | _: IsExpr | _: TypeApply =>

        throw new Exception("Unexpected tree: " + word)


  /** Leave the def tree in original info, which are harmless */
  override def transformFunDef(fdef: FunDef)(using Context): FunDef = try
    val sym = fdef.symbol

    val body2 =
      val resType = sym.tpe.asProcType.resultType
      eraseWord(this(fdef.body), expectedType = resType, returnType = resType)

    fdef.copy(body = body2)(fdef.annots, fdef.span)
  catch case ex =>
    println(fdef.symbol.tpe.show)
    println(fdef.show)
    throw ex

object Erasure:
  class EraseTypeMap(eraseUnion: Boolean)(using Definitions) extends TypeMap:
    type Context = Unit

    def apply(tp: Type)(using Context): Type =
      tp match
        case StaticRef(sym) =>
          if sym.isTypeParameter then AnyType else tp

        case UnionType(branches) =>
          if eraseUnion then
            AnyType
          else
            val branches2 =
              for branch <- branches
              yield this(branch)

            UnionType(branches2)

        case AppliedType(tctor, targs) =>
          if tctor.isOneOf(Flags.Class | Flags.Interface) then
            StaticRef(tctor)
          else
            val targs2 = for targ <- targs yield this(targ)
            AppliedType(tctor, targs2)

        case procType: ProcType =>
          val tparams2 = Nil

          val preTypeParamCount2 = 0

          val params2 =
            for param <- procType.params
            yield param.copy(info = this(param.info))

          val autos2 =
            for auto <- procType.autos
            yield auto.copy(info = this(auto.info))

          val candidates2 = procType.candidates.map(_ => Nil)

          val resType2 = this(procType.resultType)
          // DefaultValue contains no Types to map; thread defaultsFun through unchanged
          ProcType(
            tparams2, params2, autos2, candidates2, resType2, procType.receives,
            procType.preParamCount, preTypeParamCount2
          )(procType.defaultsLazy)

        case _ =>
          recur(tp)
      end match
    end apply
  end EraseTypeMap

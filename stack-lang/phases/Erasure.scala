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
  private val prevDefinitions = defn.snapshot
  private val eraseTypeMap = new Erasure.EraseTypeMap(eraseUnion)(using prevDefinitions)

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

  /** Type adaptation for boxing/unboxing of primitive types and cast
    *
    * Only nodes that may have a type of type paramter or primitive type need
    * adaptation.
    */
  def adapt(value: Word, expectedType: Type)(using Context): Word =
    if !expectedType.isValueType then
      value
    else
      val valueType = value.tpe

      val conforms = Subtyping.conforms(value.tpe, expectedType)

      // println("value.tpe = " + value.tpe.show + ", expect = " + expectedType.show)

      if primitiveTagged then
        // fast path for JS/Ruby/Python
        // Lambdas do not matter because all values are tagged
        if conforms then value else Encoded(value)(expectedType)

      else
        // assume !primitiveTagged
        def tagged(tp: Type): Boolean =
          !tp.isNumericOrBoolType && (anyTagged || !tp.isAnyType)

        def taggingConforms(valueType: Type, expectedType: Type) =
          tagged(valueType) == tagged(expectedType)

        if conforms && !expectedType.isLambdaType then
          if tagged(valueType) || !tagged(expectedType) then
            value

          else
            Encoded(value)(expectedType)

        else if !conforms && valueType.widenTermRef.isAnyType then
          // Backend will decide whether the cast involves unboxing
          Encoded(value)(expectedType)

        else
          assert(valueType.isLambdaType, "value not lambda, value type = " + valueType.show + ", expected type = " + expectedType.show + ", value = " + value.show)
          assert(expectedType.isLambdaType, "expected type not lambda: " + expectedType.show)

          val lambdaType1 @ LambdaType(paramTypes1, resType1, _) = valueType.asLambdaType
          val lambdaType2 @ LambdaType(paramTypes2, resType2, _)  = expectedType.asLambdaType

          // println("lambda1 = " + lambdaType1.show + ", lambda2 = " + lambdaType2.show)

          assert(
            paramTypes1.size == paramTypes2.size,
            "lambda arity not equal. lambda1 = " + lambdaType1.show + ", lambda2 = " + lambdaType2.show
          )

          val taggingOK =
            taggingConforms(resType1, resType2)
            && paramTypes1.zip(paramTypes2).forall((tp1, tp2) => taggingConforms(tp1, tp2))

          if taggingOK then
            if conforms then value else Encoded(value)(expectedType)

          else
            TreeOps.createLambda(lambdaType2, Phase.owner.value, value.span): paramRefs =>
              val args = paramRefs.zip(paramTypes1).map: (paramRef, paramType) =>
                adapt(paramRef, paramType)

              adapt(Apply(value, args, autos = Nil)(value.span), resType2)

  def eraseWord(word: Word, expectedType: Type, returnType: Type)(using Context): Word = common.Debug.trace("erase " + word.show, (_: Word).show, enable = false):
    word match
      case Select(qual, name) =>
        val qual2 = eraseWord(qual, expectedType = eraseType(qual.tpe), returnType)
        val select2 =
          if qual2.eq(qual) then word
          else Select(qual2, name)(word.span)
        adapt(select2, expectedType)

      case Encoded(repr) =>
        val isVoid = word.tpe.isVoidType
        repr match
          case lambda: Lambda if !isVoid =>
            // interface encoding
            assert(word.tpe.isLambdaInterface, "Non-lambda interface: " + word.tpe.show)
            val interfaceType = eraseType(word.tpe)
            val Some(lambdaType) = interfaceType.getLambdaInterfaceType.runtimeChecked
            // Return cannot cross lambda boundary
            eraseWord(lambda, expectedType = lambdaType, returnType = null) match
              case Encoded(lambda2: Lambda) => Encoded(lambda2)(interfaceType)
              case lambda2: Lambda => Encoded(lambda2)(interfaceType)
              case word => throw new Exception("Unexpected lambda interface: " + word.show)

          case _ =>
            if isVoid then
              // value drop
              assert(expectedType.isVoidType, "expected type is non-void: " + expectedType.show)
              Encoded(eraseWord(repr, expectedType = eraseType(repr.tpe), returnType))(VoidType)

            else
              // TODO: add union type assertion
              // pattern type cast, re-do the cast if needed
              val word2 = eraseWord(repr, expectedType = eraseType(repr.tpe), returnType)
              val encodedType2 = eraseType(word.tpe)
              adapt(Encoded(word2)(encodedType2), expectedType)

      case apply @ Apply(fun, args, autos) =>
        val fun2 = fun match
          case TypeApply(fun, _) => eraseWord(fun, expectedType = eraseType(fun.tpe), returnType)
          case _ => eraseWord(fun, expectedType = eraseType(fun.tpe), returnType)

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

        // TODO: type change can be achieved by mutating the type for better performance
        val apply2 =
          if changed || !Subtyping.conforms(invokeType.resultType, apply.tpe) then
            Apply(fun2, args2, autos2)(apply.span, apply.isPartialApply)
          else
            apply

        adapt(apply2, expectedType)

      case New(tpt) =>
        val tp2 = eraseType(tpt.tpe)
        // No adaptation for New
        if tp2.eq(tpt.tpe) then
          word
        else
          New(TypeTree(tp2)(tpt.span))(word.span)

      case Assign(id, rhs, isDefined) =>
        val rhs2 = eraseWord(rhs, id.symbol.tpe, returnType)
        if rhs.eq(rhs2) then word else Assign(id, rhs2, isDefined)

      case FieldAssign(select @ Select(qual, name), rhs) =>
        val qual2 = eraseWord(qual, expectedType = eraseType(qual.tpe), returnType)
        val select2 = if qual2.eq(qual) then select else Select(qual2, name)(word.span)
        val expectType = select2.tpe.widen
        val rhs2 = eraseWord(rhs, expectType, returnType)
        if select2.eq(select) && rhs2.eq(rhs) then word
        else FieldAssign(select2, rhs2)

      case fdef: FunDef => transformFunDef(fdef)

      case ifElse: If =>
        val If(cond, thenp, elsep) = ifElse
        val cond2 = eraseWord(cond, expectedType = defn.BoolType, returnType)
        val thenp2 = eraseWord(thenp, expectedType, returnType)
        val elsep2 = eraseWord(elsep, expectedType, returnType)

        // adaptation happens in each branch
        // TODO: set type to expectedType?
        If(cond2, thenp2, elsep2)(expectedType, ifElse.span)

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

        adapt(word2, expectedType)

      case ret @ Return(label, value) =>
        val value2 = eraseWord(ret.value, returnType, returnType)
        if value2.eq(value) then word
        else Return(label, value2)(word.span)

      case classTest @ ClassTest(value, cls) =>
        val value2 = eraseWord(value, expectedType = eraseType(value.tpe), returnType)
        if value2.eq(value) then
          adapt(classTest, expectedType)
        else
          adapt(ClassTest(value2, cls)(classTest.span), expectedType)

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
        // Return may not cross lambda boundary
        val body2 = eraseWord(body, eraseType(body.tpe), returnType = null)

        val paramChanged = params.exists: param =>
          val tp1 = defn.index.prevInfo(param).asType
          val tp2 = eraseType(tp1)
          tp1 `ne` tp2

        val lambda2 =
          if body2.ne(body) || paramChanged then
            Lambda(symbol, params, receives, body2)(lambda.span)
          else
            lambda

        adapt(lambda2, expectedType)

      case _: Literal | _: Ident => adapt(word, expectedType)

      case _: With | _: Allow | _: Match | _: PatValDef | _: RecordLit |
           _: PatDef | _: IsExpr | _: TypeApply =>

        throw new Exception("Unexpected tree: " + word)


  /** Leave the def tree in original info, which are harmless */
  override def transformFunDef(fdef: FunDef)(using Context): FunDef = try
    val sym = fdef.symbol

    Phase.owner.set(sym)

    val body2 =
      val resType = sym.tpe.asProcType.resultType
      eraseWord(this(fdef.body), expectedType = resType, returnType = resType)

    fdef.copy(body = body2)(fdef.annots, fdef.span)
  catch case ex =>
    println(fdef.symbol.tpe.show)
    println(fdef.show)
    throw ex

object Erasure:
  /** Erasure type parameters of classes and functions
    *
    * Type erasure should use the original type of symbols.
    */
  class EraseTypeMap(eraseUnion: Boolean)(using defn: Definitions) extends TypeMap:
    type Context = Unit

    def apply(tp: Type)(using Context): Type =
      tp match
        case StaticRef(sym) =>
          if sym.isTypeParameter then AnyType else tp

        case mref: MemberRef =>
          if mref.symbol.isField then this(mref.info)
          else mref.copy(prefix = this(mref.prefix))

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
            if tctor.isGroundType then
              tp

            else if tctor == defn.jo_Pack then
              // keep vararg mark
              AppliedType(tctor, AnyType :: Nil)

            else
              this(tp.dealias)

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

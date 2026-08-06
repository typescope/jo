package phases

import sast.*
import sast.Trees.*
import sast.Types.*

/** Inserts the boxing, unboxing and casts that make a value of one erased
  * type usable where another is expected.
  *
  * Extracted from `Erasure` because bridge methods need exactly the same
  * adaptation when forwarding an interface-shaped call to a natural-typed
  * one — see `InterfaceBridge`. Both configure it identically; they differ
  * only in `newSymbolDefn`.
  *
  * @param typing the backend's coercion policy, consulted at the leaves of
  * the lambda walk in `coercionFree`
  * @param isTagged whether values of a type are tagged for the target
  * @param allTagged whether *every* value is tagged (js/ruby/python), which
  * enables a fast path where lambdas need no structural treatment. Passed
  * explicitly rather than derived by comparing `isTagged` against a sentinel:
  * a backend supplying a structurally identical but non-identical predicate
  * would silently lose the fast path.
  * @param newSymbolDefn the `Definitions` that symbols created here (an
  * adaptation lambda's parameters) are registered in. `Erasure` passes its
  * pre-erasure snapshot, so those symbols are found un-erased while it is
  * still erasing; a phase running after erasure passes the current one.
  */
class TypeAdapter(
  typing: BackendTyping,
  isTagged: Type => Boolean,
  allTagged: Boolean,
  newSymbolDefn: Definitions
)(using defn: Definitions):
  type Context = Phase.Context

  /** Whether a value of type `tp1` can be used where `tp2` is expected with no
    * coercion. Walks lambda structure here — parameters contravariantly — and
    * asks `typing.compatible` at the leaves, so each backend decides what
    * counts as coercion-free without knowing about lambda types.
    *
    * Every branch conjoins its result check. An earlier version evaluated the
    * recursive `resultType` call as a discarded statement, so a *nested*
    * lambda's result type was ignored entirely and a mismatch there silently
    * skipped adaptation — see tests/pos/bridge-lifted-nested-lambda.jo.
    */
  def coercionFree(tp1: Type, tp2: Type): Boolean =
    if !tp1.isLambdaType && !tp2.isLambdaType then
      typing.compatible(tp1, tp2)

    else if tp1.isLambdaType && tp2.isLambdaType then
      val lambda1 = tp1.asLambdaType
      val lambda2 = tp2.asLambdaType
      lambda1.paramTypes.size == lambda2.paramTypes.size
      && coercionFree(lambda1.resultType, lambda2.resultType)
      && lambda1.paramTypes.zip(lambda2.paramTypes).forall((p1, p2) => coercionFree(p2, p1))

    else if tp1.isLambdaType then
      assert(tp2.approx.isAnyType, "tp2 = " + tp2.show)
      val lambda1 = tp1.asLambdaType
      coercionFree(lambda1.resultType, AnyType)
      && lambda1.paramTypes.forall(p1 => coercionFree(AnyType, p1))

    else
      assert(tp2.isLambdaType, "tp2 = " + tp2.show)
      assert(tp1.approx.isAnyType, "tp1 = " + tp1.show)
      val lambda2 = tp2.asLambdaType
      coercionFree(lambda2.resultType, AnyType)
      && lambda2.paramTypes.forall(p2 => coercionFree(AnyType, p2))

  /** Type adaptation for boxing/unboxing of primitive types and cast
    *
    * Only nodes that may have a type of type paramter or primitive type need
    * adaptation.
    */
  def adapt(value: Word, expectedType: Type)(using Context): Word =
    expectedType match
      case ref: RefType => assert(ref.symbol.isType, "Unexpected type = " + expectedType.show)
      case _ =>

    if !expectedType.isValueType then
      value
    else
      val valueType = value.tpe

      val conforms = Subtyping.conforms(value.tpe, expectedType)

      if allTagged then
        // fast path for JS/Ruby/Python
        // Lambdas do not matter because all values are tagged
        if conforms then value else Encoded(value)(expectedType)

      else
        if !expectedType.isLambdaType && !valueType.isLambdaType then
          if conforms then
            if isTagged(valueType) || !isTagged(expectedType) then
              value

            else
              Encoded(value)(expectedType)

          else
            assert(valueType.approx.isAnyType, "Expect Any, found = " + valueType.show + ", word = " + value.show)
            // Backend will decide whether the cast involves unboxing
            Encoded(value)(expectedType)

        else if expectedType.isLambdaType && valueType.isLambdaType then
          adaptLambdaValue(value, valueType.asLambdaType, expectedType.asLambdaType)

        else if expectedType.isLambdaType then
          if conforms then
            value
          else
            assert(valueType.approx.isAnyType, "Expect Any, found = " + valueType.show + ", word = " + value.show)
            val lambdaType2 = expectedType.asLambdaType
            val lambdaType1 = LambdaType(lambdaType2.params.map(_ => AnyType), AnyType, lambdaType2.receives)
            adaptLambdaValue(Encoded(value)(lambdaType1), lambdaType1, lambdaType2)

        else
          assert(valueType.isLambdaType, "Expect lambda type, found = " + valueType.show)
          assert(expectedType.approx.isAnyType, "Expect Any, found = " + expectedType.show)
          val lambdaType1 = valueType.asLambdaType
          val lambdaType2 = LambdaType(lambdaType1.params.map(_ => AnyType), AnyType, lambdaType1.receives)
          adaptLambdaValue(value, lambdaType1, lambdaType2)

  def adaptLambdaValue(value: Word, valueType: LambdaType, expectedType: LambdaType)(using Context): Word =
    val lambdaType1 @ LambdaType(paramTypes1, resType1, _) = valueType
    val lambdaType2 @ LambdaType(paramTypes2, resType2, _)  = expectedType

    assert(
      paramTypes1.size == paramTypes2.size,
      "lambda arity not equal. lambda1 = " + lambdaType1.show + ", lambda2 = " + lambdaType2.show
    )

    val noCoercionNeeded =
      coercionFree(resType1, resType2)
      && paramTypes1.zip(paramTypes2).forall((tp1, tp2) => coercionFree(tp2, tp1))

    if noCoercionNeeded then
      if Subtyping.conforms(valueType, expectedType) then value else Encoded(value)(expectedType)

    else
      // New symbols should go to `newSymbolDefn` — see its doc comment
      TreeOps.createLambda(lambdaType2, Phase.owner.value, value.span)(paramRefs => {
        val args = paramRefs.zip(paramTypes1).map: (paramRef, paramType) =>
          adapt(paramRef, paramType)

        adapt(Apply(value, args, autos = Nil)(value.span), resType2)
      })(using newSymbolDefn)

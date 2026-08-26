package jvm

import phases.Phase
import sast.*
import sast.Trees.*

import jvm.JVMAdaptation.Conversion

/** Makes JVM representation and ABI choices explicit in ordinary SAST before
  * instruction emission.
  *
  * Current responsibilities:
  *
  * - Replace boxing and unboxing with concretely typed Jo intrinsic calls.
  * - Box primitive values used by JVM class tests.
  * - Make lambda argument packing and result conversion explicit.
  */
final class JVMLowering(
  runtime: JVMRuntime
)(using Definitions) extends Phase:
  private def conversion(actual: Types.Type, expected: Types.Type): Conversion =
    JVMTypes.loweringConversion(actual, expected)
  private def intrinsicCall(symbol: Symbols.Symbol, value: Word): Apply =
    Apply(Ident(symbol)(value.span), value :: Nil, Nil)(value.span)

  private def boxForObject(value: Word): Word =
    conversion(value.tpe, Types.AnyType) match
      case Conversion.Box(primitive) => intrinsicCall(runtime.lowerBox(primitive), value)
      case _ => value

  private def convertFromObject(value: Word, expected: Types.Type): Word =
    conversion(Types.AnyType, expected) match
      case Conversion.Unbox(primitive) => intrinsicCall(runtime.lowerUnbox(primitive), value)
      case Conversion.CheckCast(_) => Encoded(value)(expected)
      case _ => value

  override def transformEncoded(encoded: Encoded)(using Context): Word =
    val representation = this(encoded.repr)
    val operation = conversion(representation.tpe, encoded.tpe)
    operation match
      case Conversion.Box(primitive) =>
        intrinsicCall(runtime.lowerBox(primitive), representation)
      case Conversion.Unbox(primitive) =>
        intrinsicCall(runtime.lowerUnbox(primitive), representation)
      case _ =>
        if representation.eq(encoded.repr) then encoded
        else Encoded(representation)(encoded.tpe)

  override def transformClassTest(classTest: ClassTest)(using Context): Word =
    val value = this(classTest.value)
    val boxed = boxForObject(value)
    if boxed.eq(classTest.value) then classTest
    else ClassTest(boxed, classTest.classSym)(classTest.span)

  override def transformApply(apply: Apply)(using Context): Word =
    val function = this(apply.fun)
    val arguments = apply.args.map(this.apply)
    val automaticArguments = apply.autos.map(this.apply)

    if !apply.fun.tpe.isLambdaType then
      Apply(function, arguments, automaticArguments)(apply.span, apply.isPartialApply)
    else
      val callable =
        if function.tpe.isLambdaType then function
        else Encoded(function)(apply.fun.tpe)
      val boxedCall = Apply(
        callable,
        arguments.map(boxForObject),
        automaticArguments.map(boxForObject)
      )(apply.span, apply.isPartialApply)
      val invocation = intrinsicCall(runtime.lowerInvokeLambda, boxedCall)
      convertFromObject(invocation, apply.tpe)

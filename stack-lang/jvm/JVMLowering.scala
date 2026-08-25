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
  *
  * JVM lambda ABI lowering will move into this phase next.
  */
final class JVMLowering(
  runtime: JVMRuntime,
  conversion: (Types.Type, Types.Type) => Conversion
)(using Definitions) extends Phase:
  private def intrinsicCall(symbol: Symbols.Symbol, value: Word): Apply =
    Apply(Ident(symbol)(value.span), value :: Nil, Nil)(value.span)

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
    val boxed = conversion(value.tpe, Types.AnyType) match
      case Conversion.Box(primitive) => intrinsicCall(runtime.lowerBox(primitive), value)
      case _ => value
    if boxed.eq(classTest.value) then classTest
    else ClassTest(boxed, classTest.classSym)(classTest.span)

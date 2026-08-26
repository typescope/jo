package jvm

import sast.*
import sast.Trees.*
import sast.Types.*

import jvm.ClassFile.CodeWriter
import jvm.JVMTypes.*
import jvm.JVMTypes.JType.*

/** Lowers declarative `@extern` bindings to JVM member instructions. */
final class NativeCalls(
  jvmType: Type => JType,
  operands: NativeCalls.Operands
):
  def compile(
    spec: JVMRuntime.NativeSpec, arguments: List[Word], declaredResultType: Type,
    writer: CodeWriter
  )(using ctx: MethodContext): Unit =
    def argument(word: Word, expected: JType): Unit =
      operands.compile(word)
      ValueAdaptation.emit(jvmType(word.tpe), expected, writer)

    // Every member instruction below is emitted once its operands are on the
    // stack, and each of them can throw — so put the call's own source line
    // back in effect first, in place of the last argument's.
    def atCallSite(): Unit = ctx.lines.here()

    val actual = spec.kind match
      case "static" =>
        arguments.zip(parseMethodParams(spec.desc)).foreach(argument)
        atCallSite()
        writer.invokestatic(spec.owner, spec.member, spec.desc)
        parseMethodReturn(spec.desc)

      case "virtual" | "interface" =>
        val receiver :: rest = arguments: @unchecked
        argument(receiver, Ref(ObjectDesc))
        atCallSite()
        writer.checkcast(spec.owner)
        rest.zip(parseMethodParams(spec.desc)).foreach(argument)
        atCallSite()
        if spec.kind == "virtual" then writer.invokevirtual(spec.owner, spec.member, spec.desc)
        else writer.invokeinterface(spec.owner, spec.member, spec.desc)
        parseMethodReturn(spec.desc)

      case "special" =>
        writer.newObj(spec.owner)
        writer.dup()
        arguments.zip(parseMethodParams(spec.desc)).foreach(argument)
        atCallSite()
        writer.invokespecial(spec.owner, "<init>", spec.desc)
        Ref("L" + spec.owner + ";")

      case "getstatic" =>
        writer.getstatic(spec.owner, spec.member, spec.desc)
        parseFieldDesc(spec.desc)

      case "putstatic" =>
        val fieldType = parseFieldDesc(spec.desc)
        argument(arguments.head, fieldType)
        atCallSite()
        writer.putstatic(spec.owner, spec.member, spec.desc)
        V

      case "getfield" =>
        argument(arguments.head, Ref(ObjectDesc))
        atCallSite()
        writer.checkcast(spec.owner)
        writer.getfield(spec.owner, spec.member, spec.desc)
        parseFieldDesc(spec.desc)

      case "putfield" =>
        argument(arguments.head, Ref(ObjectDesc))
        atCallSite()
        writer.checkcast(spec.owner)
        val fieldType = parseFieldDesc(spec.desc)
        argument(arguments(1), fieldType)
        atCallSite()
        writer.putfield(spec.owner, spec.member, spec.desc)
        V

      case other => throw new Exception("Unknown @extern kind: " + other)

    ValueAdaptation.emit(actual, jvmType(declaredResultType), writer)

object NativeCalls:
  trait Operands:
    def compile(word: Word)(using MethodContext): MethodBuilder.Flow

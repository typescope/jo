package jvm

import sast.*
import sast.Trees.*
import sast.Types.*

import jvm.ClassFile.CodeWriter
import jvm.JVMTypes.*
import jvm.JVMTypes.JType.*

/** Lowers declarative `@extern` bindings to JVM member instructions. */
final class JVMNativeCallCompiler(
  jvmType: Type => JType,
  operands: JVMNativeCallCompiler.Operands
):
  def compile(
    spec: JVMRuntime.NativeSpec, arguments: List[Word], declaredResultType: Type,
    writer: CodeWriter
  )(using JVMMethodContext): Unit =
    def argument(word: Word, expected: JType): Unit =
      operands.compile(word)
      JVMAdaptation.emit(jvmType(word.tpe), expected, writer)

    val actual = spec.kind match
      case "static" =>
        arguments.zip(parseMethodParams(spec.desc)).foreach(argument)
        writer.invokestatic(spec.owner, spec.member, spec.desc)
        parseMethodReturn(spec.desc)

      case "virtual" | "interface" =>
        val receiver :: rest = arguments: @unchecked
        argument(receiver, Ref(ObjectDesc))
        writer.checkcast(spec.owner)
        rest.zip(parseMethodParams(spec.desc)).foreach(argument)
        if spec.kind == "virtual" then writer.invokevirtual(spec.owner, spec.member, spec.desc)
        else writer.invokeinterface(spec.owner, spec.member, spec.desc)
        parseMethodReturn(spec.desc)

      case "special" =>
        writer.newObj(spec.owner)
        writer.dup()
        arguments.zip(parseMethodParams(spec.desc)).foreach(argument)
        writer.invokespecial(spec.owner, "<init>", spec.desc)
        Ref("L" + spec.owner + ";")

      case "getstatic" =>
        writer.getstatic(spec.owner, spec.member, spec.desc)
        parseFieldDesc(spec.desc)

      case "putstatic" =>
        val fieldType = parseFieldDesc(spec.desc)
        argument(arguments.head, fieldType)
        writer.putstatic(spec.owner, spec.member, spec.desc)
        V

      case "getfield" =>
        argument(arguments.head, Ref(ObjectDesc))
        writer.checkcast(spec.owner)
        writer.getfield(spec.owner, spec.member, spec.desc)
        parseFieldDesc(spec.desc)

      case "putfield" =>
        argument(arguments.head, Ref(ObjectDesc))
        writer.checkcast(spec.owner)
        val fieldType = parseFieldDesc(spec.desc)
        argument(arguments(1), fieldType)
        writer.putfield(spec.owner, spec.member, spec.desc)
        V

      case other => throw new Exception("Unknown @extern kind: " + other)

    JVMAdaptation.emit(actual, jvmType(declaredResultType), writer)

object JVMNativeCallCompiler:
  trait Operands:
    def compile(word: Word)(using JVMMethodContext): JVMMethodCompiler.Flow

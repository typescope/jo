package jvm

import sast.*
import sast.Trees.*
import sast.Symbols.*
import sast.Types.*

import jvm.ClassFile.*
import jvm.JVMTypes.*
import jvm.JVMTypes.JType.*

/** Assembles complete JVM methods around expression lowering. */
final class JVMMethodCompiler(
  backend: JVMBackendContext,
  expressions: JVMMethodCompiler.Expressions,
  jvmType: Type => JType,
  methodDesc: (List[Type], Type) => String,
  lambdaABI: JVMLambdaABI
)(using Definitions) extends JVMClassCompiler.MethodCompiler:

  def compileTopLevel(fdef: FunDef, constants: ConstantPool): MethodOut =
    val procType = fdef.symbol.tpe.asProcType
    val writer = new CodeWriter(constants)
    val slots = new JVMMethodSlots
    val self =
      if fdef.symbol.owner.isInterface then Some(backend.interfaceDef(fdef.symbol.owner).self)
      else None
    self.foreach(symbol => slots.bind(symbol, Ref(ObjectDesc)))
    fdef.allParams.foreach(param => slots.bind(param, jvmType(param.tpe)))
    touchAllocatedSlots(slots, writer)
    val locals = fdef.locals.map(local => local -> slots.bind(local, jvmType(local.tpe)))
    val resultType = jvmType(procType.resultType)
    given JVMMethodContext = new JVMMethodContext(writer, slots, resultType, selfSym = self)

    expressions.initializeLocals(locals, writer)
    expressions.compile(fdef.body)
    if !expressions.isTerminal(fdef.body) then expressions.emitReturn(resultType, writer)

    val (code, maxStack, maxLocals) = writer.finish()
    val parameterTypes = procType.paramTypes ++ procType.autoTypes
    val selfDescriptor = if self.isDefined then ObjectDesc else ""
    val descriptor =
      "(" + selfDescriptor + parameterTypes.map(t => descOf(jvmType(t))).mkString + ")" +
        descOf(jvmType(procType.resultType))
    MethodOut(
      AccessFlags.Public | AccessFlags.Static,
      backend.topLevelName(fdef.symbol), descriptor,
      Some((code, maxStack, maxLocals))
    )

  override def compileConstructor(fdef: FunDef, owner: ClassDef, constants: ConstantPool): MethodOut =
    val writer = new CodeWriter(constants)
    val slots = new JVMMethodSlots
    slots.bind(owner.self, Ref(ObjectDesc))
    val parameters = fdef.params
    parameters.foreach(param => slots.bind(param, jvmType(param.tpe)))
    val locals = fdef.locals.map(local => local -> slots.bind(local, jvmType(local.tpe)))
    touchAllocatedSlots(slots, writer)

    writer.aload(0)
    writer.invokespecial(ObjectClass, Names.Constructor, "()V")
    expressions.initializeLocals(locals, writer)

    def compileInitializer(word: Word): Unit = word match
      case Block(words) => words.foreach(compileInitializer)
      case _: Ident => () // the trailing Jo constructor result is `self`
      case other => expressions.compileInline(other, slots, writer, owner.self)

    compileInitializer(fdef.body)
    writer.returnVoid()
    val (code, maxStack, maxLocals) = writer.finish()
    val descriptor = "(" + parameters.map(p => descOf(jvmType(p.tpe))).mkString + ")V"
    MethodOut(AccessFlags.Public, Names.Constructor, descriptor, Some((code, maxStack, maxLocals)))

  override def compileInstanceMethod(fdef: FunDef, self: Symbol, constants: ConstantPool): MethodOut =
    val procType = fdef.symbol.tpe.asProcType
    val writer = new CodeWriter(constants)
    val slots = new JVMMethodSlots
    slots.bind(self, Ref(ObjectDesc))
    fdef.allParams.foreach(param => slots.bind(param, jvmType(param.tpe)))
    touchAllocatedSlots(slots, writer)
    val locals = fdef.locals.map(local => local -> slots.bind(local, jvmType(local.tpe)))
    val resultType = jvmType(procType.resultType)
    given JVMMethodContext = new JVMMethodContext(writer, slots, resultType, selfSym = Some(self))

    expressions.initializeLocals(locals, writer)
    expressions.compile(fdef.body)
    if !expressions.isTerminal(fdef.body) then expressions.emitReturn(resultType, writer)

    val (code, maxStack, maxLocals) = writer.finish()
    val descriptor = methodDesc(procType.paramTypes ++ procType.autoTypes, procType.resultType)
    val bytecodeName = fdef.symbol.name.stripSuffix(Names.BridgeSuffix)
    MethodOut(AccessFlags.Public, bytecodeName, descriptor, Some((code, maxStack, maxLocals)))

  override def compileLambdaApply(fdef: FunDef, owner: ClassDef, constants: ConstantPool): MethodOut =
    val writer = new CodeWriter(constants)
    val slots = new JVMMethodSlots
    slots.bind(owner.self, Ref(ObjectDesc))
    val argumentsSlot = 1
    writer.touchLocal(argumentsSlot)
    val resultType = jvmType(fdef.resultType.tpe)
    given JVMMethodContext = new JVMMethodContext(
      writer, slots, resultType, selfSym = Some(owner.self),
      argsArraySlot = Some(argumentsSlot)
    )

    lambdaABI.unpackParameters(
      fdef.params, slots, argumentsSlot, jvmType, expressions.adaptTo, writer
    )
    val locals = fdef.locals.map(local => local -> slots.bind(local, jvmType(local.tpe)))
    expressions.initializeLocals(locals, writer)
    expressions.compile(fdef.body)
    if resultType == V then writer.aconstNull()
    else expressions.adaptTo(resultType, Ref(ObjectDesc), writer)
    writer.areturn()

    val (code, maxStack, maxLocals) = writer.finish()
    MethodOut(
      AccessFlags.Public, lambdaABI.applyName, lambdaABI.applyDescriptor,
      Some((code, maxStack, maxLocals))
    )

  private def touchAllocatedSlots(slots: JVMMethodSlots, writer: CodeWriter): Unit =
    if slots.used > 0 then writer.touchLocal(slots.used - 1)

object JVMMethodCompiler:
  /** Expression-lowering service required by method assembly. */
  trait Expressions:
    def compile(word: Word)(using JVMMethodContext): Unit
    def compileInline(word: Word, slots: JVMMethodSlots, writer: CodeWriter, self: Symbol): Unit
    def isTerminal(word: Word): Boolean
    def emitReturn(tpe: JType, writer: CodeWriter): Unit
    def initializeLocals(locals: List[(Symbol, Int)], writer: CodeWriter): Unit
    def adaptTo(actual: JType, expected: JType, writer: CodeWriter): Unit
    def storeLocal(tpe: JType, slot: Int, writer: CodeWriter): Unit

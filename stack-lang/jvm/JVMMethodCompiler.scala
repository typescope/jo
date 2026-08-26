package jvm

import sast.*
import sast.Trees.*
import sast.Symbols.*
import sast.Types.*

import jvm.ClassFile.*
import jvm.JVMTypes.*
import jvm.JVMTypes.JType.*
import jvm.JVMInstructionEmitter

/** Assembles complete JVM methods around expression lowering. */
final class JVMMethodCompiler(
  expressions: JVMMethodCompiler.Expressions
)(using defn: Definitions, context: JVMContext) extends JVMClassCompiler.MethodCompiler:
  import JVMMethodCompiler.Flow

  def compileTopLevel(fdef: FunDef, constants: ConstantPool): MethodOut =
    val procType = fdef.symbol.tpe.asProcType
    val writer = new CodeWriter(constants)
    val slots = new JVMMethodSlots
    val self =
      if fdef.symbol.owner.isInterface then Some(context.interfaceDef(fdef.symbol.owner).self)
      else None
    self.foreach(symbol => slots.bind(symbol, Ref(ObjectDesc)))
    fdef.allParams.foreach(param => slots.bind(param, JVMTypes.typeOf(param.tpe)))
    touchAllocatedSlots(slots, writer)
    val locals = fdef.locals.map(local => local -> slots.bind(local, JVMTypes.typeOf(local.tpe)))
    val resultType = JVMTypes.typeOf(procType.resultType)
    given JVMMethodContext = new JVMMethodContext(writer, slots, resultType, selfSym = self)

    expressions.initializeLocals(locals, writer)
    if expressions.compile(fdef.body) == Flow.FallsThrough then
      expressions.emitReturn(resultType, writer)

    val (code, maxStack, maxLocals) = writer.finish()
    val parameterTypes = procType.paramTypes ++ procType.autoTypes
    val selfDescriptor = if self.isDefined then ObjectDesc else ""
    val descriptor =
      "(" + selfDescriptor + parameterTypes.map(JVMTypes.descriptorOf).mkString + ")" +
        JVMTypes.descriptorOf(procType.resultType)
    MethodOut(
      AccessFlags.Public | AccessFlags.Static,
      context.topLevelName(fdef.symbol), descriptor,
      Some((code, maxStack, maxLocals))
    )

  override def compileConstructor(fdef: FunDef, owner: ClassDef, constants: ConstantPool): MethodOut =
    val writer = new CodeWriter(constants)
    val slots = new JVMMethodSlots
    slots.bind(owner.self, Ref(ObjectDesc))
    val parameters = fdef.params
    parameters.foreach(param => slots.bind(param, JVMTypes.typeOf(param.tpe)))
    val locals = fdef.locals.map(local => local -> slots.bind(local, JVMTypes.typeOf(local.tpe)))
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
    val descriptor = "(" + parameters.map(p => JVMTypes.descriptorOf(p.tpe)).mkString + ")V"
    MethodOut(AccessFlags.Public, Names.Constructor, descriptor, Some((code, maxStack, maxLocals)))

  override def compileInstanceMethod(fdef: FunDef, self: Symbol, constants: ConstantPool): MethodOut =
    val procType = fdef.symbol.tpe.asProcType
    val writer = new CodeWriter(constants)
    val slots = new JVMMethodSlots
    slots.bind(self, Ref(ObjectDesc))
    fdef.allParams.foreach(param => slots.bind(param, JVMTypes.typeOf(param.tpe)))
    touchAllocatedSlots(slots, writer)
    val locals = fdef.locals.map(local => local -> slots.bind(local, JVMTypes.typeOf(local.tpe)))
    val resultType = JVMTypes.typeOf(procType.resultType)
    given JVMMethodContext = new JVMMethodContext(writer, slots, resultType, selfSym = Some(self))

    expressions.initializeLocals(locals, writer)
    if expressions.compile(fdef.body) == Flow.FallsThrough then
      expressions.emitReturn(resultType, writer)

    val (code, maxStack, maxLocals) = writer.finish()
    val descriptor = JVMTypes.methodDescriptor(procType.paramTypes ++ procType.autoTypes, procType.resultType)
    val bytecodeName = fdef.symbol.name.stripSuffix(Names.BridgeSuffix)
    MethodOut(AccessFlags.Public, bytecodeName, descriptor, Some((code, maxStack, maxLocals)))

  override def compileLambdaApply(fdef: FunDef, owner: ClassDef, constants: ConstantPool): MethodOut =
    val writer = new CodeWriter(constants)
    val slots = new JVMMethodSlots
    slots.bind(owner.self, Ref(ObjectDesc))
    val argumentsSlot = 1
    writer.touchLocal(argumentsSlot)
    val resultType = JVMTypes.typeOf(fdef.resultType.tpe)
    given JVMMethodContext = new JVMMethodContext(
      writer, slots, resultType, selfSym = Some(owner.self),
      argsArraySlot = Some(argumentsSlot)
    )

    JVMLambdaABI.unpackParameters(
      fdef.params, slots, argumentsSlot, JVMTypes.typeOf, writer
    )
    val locals = fdef.locals.map(local => local -> slots.bind(local, JVMTypes.typeOf(local.tpe)))
    expressions.initializeLocals(locals, writer)
    if expressions.compile(fdef.body) == Flow.FallsThrough then
      if resultType == V then writer.aconstNull()
      else JVMAdaptation.emit(resultType, Ref(ObjectDesc), writer)
      writer.areturn()

    val (code, maxStack, maxLocals) = writer.finish()
    MethodOut(
      AccessFlags.Public, JVMLambdaABI.applyName, JVMLambdaABI.applyDescriptor,
      Some((code, maxStack, maxLocals))
    )

  private def touchAllocatedSlots(slots: JVMMethodSlots, writer: JVMInstructionEmitter): Unit =
    if slots.used > 0 then writer.touchLocal(slots.used - 1)

object JVMMethodCompiler:
  enum Flow:
    case FallsThrough, Terminal

  /** Expression-lowering service required by method assembly. */
  trait Expressions:
    def compile(word: Word)(using JVMMethodContext): Flow
    def compileInline(word: Word, slots: JVMMethodSlots, writer: JVMInstructionEmitter, self: Symbol): Unit
    def emitReturn(tpe: JType, writer: JVMInstructionEmitter): Unit
    def initializeLocals(locals: List[(Symbol, Int)], writer: JVMInstructionEmitter): Unit
    def storeLocal(tpe: JType, slot: Int, writer: JVMInstructionEmitter): Unit

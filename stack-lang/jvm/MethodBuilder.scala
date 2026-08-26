package jvm

import sast.*
import sast.Trees.*
import sast.Symbols.*
import sast.Types.*

import jvm.ClassFile.*
import jvm.JVMTypes.*
import jvm.JVMTypes.JType.*

/** Assembles complete JVM methods around expression lowering. */
final class MethodBuilder(
  expressions: MethodBuilder.Expressions
)(using defn: Definitions, context: JVMContext) extends ClassBuilder.MethodBodies:
  import MethodBuilder.Flow

  def compileTopLevel(fdef: FunDef): MethodOut =
    val procType = fdef.symbol.tpe.asProcType
    val writer = new CodeWriter
    val slots = new MethodSlots
    val self =
      if fdef.symbol.owner.isInterface then Some(context.interfaceDef(fdef.symbol.owner).self)
      else None
    self.foreach(symbol => slots.bind(symbol, Ref(ObjectDesc)))
    fdef.allParams.foreach(param => slots.bind(param, JVMTypes.typeOf(param.tpe)))
    val locals = fdef.locals.map(local => local -> slots.bind(local, JVMTypes.typeOf(local.tpe)))
    val resultType = JVMTypes.typeOf(procType.resultType)
    given MethodContext = new MethodContext(writer, slots, resultType, selfSym = self)

    writer.lineNumber(fdef.symbol.sourcePos.startLine + 1)
    expressions.initializeLocals(locals, writer)
    if expressions.compile(fdef.body) == Flow.FallsThrough then
      expressions.emitReturn(resultType, writer)

    val parameterTypes = procType.paramTypes ++ procType.autoTypes
    val selfDescriptor = if self.isDefined then ObjectDesc else ""
    val descriptor =
      "(" + selfDescriptor + parameterTypes.map(JVMTypes.descriptorOf).mkString + ")" +
        JVMTypes.descriptorOf(procType.resultType)
    val location = context.topLevelLocation(fdef.symbol)
    MethodOut(
      AccessFlags.Public | AccessFlags.Static,
      location.name, descriptor,
      Some(writer)
    )

  override def compileConstructor(fdef: FunDef, owner: ClassDef): MethodOut =
    val writer = new CodeWriter
    val slots = new MethodSlots
    slots.bind(owner.self, Ref(ObjectDesc))
    val parameters = fdef.params
    parameters.foreach(param => slots.bind(param, JVMTypes.typeOf(param.tpe)))
    val locals = fdef.locals.map(local => local -> slots.bind(local, JVMTypes.typeOf(local.tpe)))

    writer.aload(0)
    writer.invokespecial(ObjectClass, Names.Constructor, "()V")
    writer.lineNumber(fdef.symbol.sourcePos.startLine + 1)
    expressions.initializeLocals(locals, writer)

    def compileInitializer(word: Word): Unit = word match
      case Block(words) => words.foreach(compileInitializer)
      case _: Ident => () // the trailing Jo constructor result is `self`
      case other => expressions.compileInline(other, slots, writer, owner.self)

    compileInitializer(fdef.body)
    writer.returnVoid()
    val descriptor = "(" + parameters.map(p => JVMTypes.descriptorOf(p.tpe)).mkString + ")V"
    MethodOut(AccessFlags.Public, Names.Constructor, descriptor, Some(writer))

  override def compileInstanceMethod(fdef: FunDef, self: Symbol): MethodOut =
    val procType = fdef.symbol.tpe.asProcType
    val writer = new CodeWriter
    val slots = new MethodSlots
    slots.bind(self, Ref(ObjectDesc))
    fdef.allParams.foreach(param => slots.bind(param, JVMTypes.typeOf(param.tpe)))
    val locals = fdef.locals.map(local => local -> slots.bind(local, JVMTypes.typeOf(local.tpe)))
    val resultType = JVMTypes.typeOf(procType.resultType)
    given MethodContext = new MethodContext(writer, slots, resultType, selfSym = Some(self))

    writer.lineNumber(fdef.symbol.sourcePos.startLine + 1)
    expressions.initializeLocals(locals, writer)
    if expressions.compile(fdef.body) == Flow.FallsThrough then
      expressions.emitReturn(resultType, writer)

    val descriptor = JVMTypes.methodDescriptor(procType.paramTypes ++ procType.autoTypes, procType.resultType)
    val bytecodeName = fdef.symbol.name.stripSuffix(Names.BridgeSuffix)
    MethodOut(AccessFlags.Public, bytecodeName, descriptor, Some(writer))

  override def compileLambdaApply(fdef: FunDef, owner: ClassDef): MethodOut =
    val writer = new CodeWriter
    val slots = new MethodSlots
    slots.bind(owner.self, Ref(ObjectDesc))
    val argumentsSlot = 1
    val resultType = JVMTypes.typeOf(fdef.resultType.tpe)
    given MethodContext = new MethodContext(
      writer, slots, resultType, selfSym = Some(owner.self),
      argsArraySlot = Some(argumentsSlot)
    )

    writer.lineNumber(fdef.symbol.sourcePos.startLine + 1)
    LambdaABI.unpackParameters(
      fdef.params, slots, argumentsSlot, JVMTypes.typeOf, writer
    )
    val locals = fdef.locals.map(local => local -> slots.bind(local, JVMTypes.typeOf(local.tpe)))
    expressions.initializeLocals(locals, writer)
    if expressions.compile(fdef.body) == Flow.FallsThrough then
      if resultType == V then writer.aconstNull()
      else ValueAdaptation.emit(resultType, Ref(ObjectDesc), writer)
      writer.areturn()

    MethodOut(
      AccessFlags.Public, LambdaABI.applyName, LambdaABI.applyDescriptor,
      Some(writer)
    )

object MethodBuilder:
  enum Flow:
    case FallsThrough, Terminal

  /** Expression-lowering service required by method assembly. */
  trait Expressions:
    def compile(word: Word)(using MethodContext): Flow
    def compileInline(word: Word, slots: MethodSlots, writer: CodeWriter, self: Symbol): Unit
    def emitReturn(tpe: JType, writer: CodeWriter): Unit
    def initializeLocals(locals: List[(Symbol, Int)], writer: CodeWriter): Unit
    def storeLocal(tpe: JType, slot: Int, writer: CodeWriter): Unit

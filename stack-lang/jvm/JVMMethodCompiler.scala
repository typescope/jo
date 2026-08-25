package jvm

import sast.*
import sast.Trees.*
import sast.Symbols.*
import sast.Types.*

import jvm.ClassFile.*
import jvm.JVMTypes.*
import jvm.JVMTypes.JType.*

/** Transitional expression-lowering interface.
  *
  * It makes the remaining dependency explicit while method assembly moves
  * out of `JVMCodeGen`. The next extraction moves this implementation into
  * `JVMMethodCompiler` and removes the interface.
  */
trait JVMExpressionCompilation:
  def compile(word: Word)(using JVMMethodContext): Unit
  def compileInline(word: Word, slots: JVMMethodSlots, writer: CodeWriter, self: Symbol): Unit
  def isTerminal(word: Word): Boolean
  def emitReturn(tpe: JType, writer: CodeWriter): Unit
  def initializeLocals(locals: List[(Symbol, Int)], writer: CodeWriter): Unit
  def adaptTo(actual: JType, expected: JType, writer: CodeWriter): Unit
  def storeLocal(tpe: JType, slot: Int, writer: CodeWriter): Unit

/** Assembles complete JVM methods around expression lowering. */
final class JVMMethodCompiler(
  expressions: JVMExpressionCompilation,
  jvmType: Type => JType,
  methodDesc: (List[Type], Type) => String
)(using Definitions) extends JVMMethodCompilation:

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

    slots.reserveUpTo(2)
    fdef.params.zipWithIndex.foreach { (param, index) =>
      val parameterType = jvmType(param.tpe)
      val slot = slots.bind(param, parameterType)
      writer.aload(argumentsSlot)
      writer.iconst(index)
      writer.aaload()
      expressions.adaptTo(Ref(ObjectDesc), parameterType, writer)
      expressions.storeLocal(parameterType, slot, writer)
    }
    val locals = fdef.locals.map(local => local -> slots.bind(local, jvmType(local.tpe)))
    expressions.initializeLocals(locals, writer)
    expressions.compile(fdef.body)
    if resultType == V then writer.aconstNull()
    else expressions.adaptTo(resultType, Ref(ObjectDesc), writer)
    writer.areturn()

    val (code, maxStack, maxLocals) = writer.finish()
    MethodOut(
      AccessFlags.Public, "apply", "([Ljava/lang/Object;)Ljava/lang/Object;",
      Some((code, maxStack, maxLocals))
    )

  private def touchAllocatedSlots(slots: JVMMethodSlots, writer: CodeWriter): Unit =
    if slots.used > 0 then writer.touchLocal(slots.used - 1)

package jvm

import sast.*
import sast.Trees.*
import sast.Symbols.*

import jvm.ClassFile.*
import jvm.JVMTypes.*
import jvm.JVMTypes.JType.*

import scala.collection.mutable

/** Coordinates reachability, class layout, method construction, and class-file
  * emission for the JVM backend.
  *
  * Representation choices live in [[JVMTypes]], expression emission in
  * [[ExpressionEmitter]], and class-file encoding in [[ClassFile]].
  */
class JVMCodeGen(runtime: JVMRuntime, rewire: Map[Symbol, Symbol])(using defn: Definitions):
  import JVMCodeGen.*
  private given context: JVMContext = new JVMContext(rewire)
  private val expressionEmitter = new ExpressionEmitter(runtime)
  private val methodBuilder = new MethodBuilder(expressionEmitter)
  private val classBuilder = new ClassBuilder(methodBuilder)

  private val classFiles = mutable.LinkedHashMap.empty[String, Array[Byte]]

  /** @return output class files, keyed by (JVM internal) class name, plus the
    *         name of the class holding `public static void main`.
    */
  def generate(units: List[FileUnit]): (Map[String, Array[Byte]], String) =
    context.index(units)

    // Finish reachability and bucket assignment before lowering any body.
    // Static owners and generated class names are therefore stable inputs to
    // lowering rather than side effects of it.
    val live = new Universe(runtime.start, rewire, runtime.intrinsicDeps).run()
    context.populate(live)

    context.buckets.foreach: bucket =>
      bucket.classes.foreach(cdef => addClassFile(classBuilder.compileClass(cdef)))
      bucket.interfaces.foreach(idef => addClassFile(classBuilder.compileInterface(idef)))
      val methods = bucket.methods.iterator
        .filterNot(fdef => isNativeOrIntrinsic(fdef.symbol))
        .map(methodBuilder.compileTopLevel).toList
      if methods.nonEmpty then
        classFiles(bucket.className) = ClassFile.write(
          bucket.className, ObjectClass, Nil, Nil, methods,
          sourceFile = Some(bucket.sourceName)
        )

    // Keep a tiny conventional Java launcher; Jo code lives in source buckets.
    val mainBytes = ClassFile.write(MainClassName, ObjectClass, Nil, Nil, buildJavaMain() :: Nil)
    (classFiles.toMap + (MainClassName -> mainBytes), MainClassName)

  private def addClassFile(compiled: (String, Array[Byte])): Unit =
    classFiles(compiled._1) = compiled._2

  private def buildJavaMain(): MethodOut =
    val startSym = context.resolve(runtime.start)
    val startProcType = startSym.tpe.asProcType
    val startDesc = JVMTypes.methodDescriptor(
      startProcType.paramTypes ++ startProcType.autoTypes,
      startProcType.resultType
    )
    val cw = new CodeWriter
    val start = context.topLevelLocation(startSym)
    cw.invokestatic(start.owner, start.name, startDesc)
    // `start`'s Jo-level return type is `Unit`, which — like any other Jo
    // value type — now erases to `Ref(Object)` (see `JVMTypes`),
    // comment), not `V`; the real Java `main` truly is `void`, so discard it.
    if JVMTypes.typeOf(startProcType.resultType) != V then cw.pop()
    cw.returnVoid()
    MethodOut(AccessFlags.Public | AccessFlags.Static, "main", "([Ljava/lang/String;)V", Some(cw))

  private def isNativeOrIntrinsic(sym: Symbol): Boolean =
    runtime.nativeSpec(sym).isDefined || sym.hasAnnotation(defn.intrinsic)

end JVMCodeGen

object JVMCodeGen:
  val MainClassName = "Main"

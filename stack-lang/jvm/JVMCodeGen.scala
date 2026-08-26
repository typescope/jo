package jvm

import sast.*
import sast.Trees.*
import sast.Symbols.*

import jvm.ClassFile.*
import jvm.JVMTypes.*
import jvm.JVMTypes.JType.*

import scala.collection.mutable

/** Translates Jo SAST to JVM class files.
  *
  * Scope of this prototype: enough of the SAST is handled to compile
  * `tests/pos/fact.jo` end to end (top-level functions, Int/Bool arithmetic,
  * `if`/`while`, recursion, non-capturing lambdas, and the small amount of
  * runtime plumbing `Predef.println` and context parameters need). See
  * docs/jips/jvm-backend.md for what is deliberately out of scope and how a
  * production version would extend this.
  *
  * Design in one paragraph: every non-primitive Jo type erases to
  * `java.lang.Object` (see `JVMTypes`); `Int`/`Bool`/`Byte`/`Char`/`Float`/
  * `Long` erase to genuine JVM primitives. `compile(word)` always leaves
  * exactly the value implied by `JVMTypes.typeOf(word.tpe)` on the operand stack
  * (or nothing, for `Unit`) — every call site relies on that postcondition
  * instead of threading an expected-type parameter through recursion.
  */
class JVMCodeGen(runtime: JVMRuntime, rewire: Map[Symbol, Symbol])(using defn: Definitions):
  import JVMCodeGen.*
  import JVMContext.Pending
  private given context: JVMContext = new JVMContext(rewire)
  private val expressionCompiler = new JVMExpressionCompiler(runtime)
  private val methodCompiler = new JVMMethodCompiler(expressionCompiler)
  private val classCompiler = new JVMClassCompiler(methodCompiler)

  //----------------------------------------------------------------------------
  // Program-wide state: reachability worklists, name assignment
  //----------------------------------------------------------------------------

  private val classFiles = mutable.LinkedHashMap.empty[String, Array[Byte]]

  //----------------------------------------------------------------------------
  // Entry point
  //----------------------------------------------------------------------------

  /** @return output class files, keyed by (JVM internal) class name, plus the
    *         name of the class holding `public static void main`.
    */
  def generate(units: List[FileUnit]): (Map[String, Array[Byte]], String) =
    context.index(units)

    context.requireTopLevel(runtime.start)
    val mainClassMethods = new mutable.ArrayBuffer[MethodOut]()
    val cp = new ConstantPool

    // A single interleaved loop, not "drain topLevelWork, then drain
    // classWork": compiling a class's methods (e.g. a lifted lambda's
    // `apply`) can discover new top-level function calls — reaching a
    // context parameter pulls in whatever function provides it, say — and
    // compiling a top-level function can likewise discover new classes via
    // `New`. Draining the two queues in sequence would silently drop
    // whichever queue gained new work after its own pass already finished.
    var pending = context.nextPending()
    while pending.nonEmpty do
      pending.get match
        case Pending.TopLevel(fdef) =>
          if !isNativeOrIntrinsic(fdef.symbol) then
            mainClassMethods += methodCompiler.compileTopLevel(fdef, cp)
        case Pending.Class(cdef) => addClassFile(classCompiler.compileClass(cdef, cp))
        case Pending.Interface(idef) => addClassFile(classCompiler.compileInterface(idef, cp))
      pending = context.nextPending()

    // Synthetic entry point: `public static void main(String[] args)`
    mainClassMethods += buildJavaMain(cp)

    val mainBytes = ClassFile.write(cp, MainClassName, ObjectClass, Nil, Nil, mainClassMethods.toList)
    (classFiles.toMap + (MainClassName -> mainBytes), MainClassName)

  private def addClassFile(compiled: (String, Array[Byte])): Unit =
    classFiles(compiled._1) = compiled._2

  private def buildJavaMain(cp: ConstantPool): MethodOut =
    val startSym = context.resolve(runtime.start)
    val startProcType = startSym.tpe.asProcType
    val startDesc = JVMTypes.methodDescriptor(
      startProcType.paramTypes ++ startProcType.autoTypes,
      startProcType.resultType
    )
    val cw = new CodeWriter(cp)
    cw.touchLocal(0) // String[] args
    cw.invokestatic(MainClassName, context.topLevelName(startSym), startDesc)
    // `start`'s Jo-level return type is `Unit`, which — like any other Jo
    // value type — now erases to `Ref(Object)` (see `JVMTypes`),
    // comment), not `V`; the real Java `main` truly is `void`, so discard it.
    if JVMTypes.typeOf(startProcType.resultType) != V then cw.pop()
    cw.returnVoid()
    val (code, ms, ml) = cw.finish()
    MethodOut(AccessFlags.Public | AccessFlags.Static, "main", "([Ljava/lang/String;)V", Some((code, ms, ml)))

  private def isNativeOrIntrinsic(sym: Symbol): Boolean =
    runtime.nativeSpec(sym).isDefined || sym.hasAnnotation(defn.intrinsic)

end JVMCodeGen

object JVMCodeGen:
  val MainClassName = "Main"

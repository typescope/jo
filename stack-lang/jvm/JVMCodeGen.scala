package jvm

import sast.*
import sast.Trees.*
import sast.Symbols.*
import sast.Types.*

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
  * `java.lang.Object` (see `jvmType`); `Int`/`Bool`/`Byte`/`Char`/`Float`/
  * `Long` erase to genuine JVM primitives. `compile(word)` always leaves
  * exactly the value implied by `jvmType(word.tpe)` on the operand stack
  * (or nothing, for `Unit`) — every call site relies on that postcondition
  * instead of threading an expected-type parameter through recursion.
  */
class JVMCodeGen(runtime: JVMRuntime, rewire: Map[Symbol, Symbol])(using defn: Definitions):
  import JVMCodeGen.*
  import JVMBackendContext.Pending
  private val backend = new JVMBackendContext(rewire)
  private val lambdaABI = new JVMLambdaABI
  private val expressionCompiler = new JVMExpressionCompiler(backend, runtime, jvmType, methodDesc, lambdaABI)
  private val methodCompiler = new JVMMethodCompiler(backend, expressionCompiler, jvmType, methodDesc, lambdaABI)
  private val classCompiler = new JVMClassCompiler(backend, methodCompiler, jvmType, methodDesc, lambdaABI)

  def jvmType(tp: Type): JType =
    // Only the SAST's *internal* `VoidType` marker (statement-context/
    // dropped-value typing: `Assign`/`While`'s own `.tpe`, and what
    // `dropValue` wraps a discarded statement's type as) means "truly zero
    // bytecode representation, nothing is ever produced." Jo's user-visible
    // `Unit` (a real nominal type with a value, `jo_pass`) and `Bottom` are
    // both genuine *value* types (`Type.isValueType` holds for both) — they
    // fall through to the catch-all `Ref(ObjectDesc)` below like any other
    // non-primitive type, with `null` as their runtime representation.
    // `jo_pass()` compiles to `aconst_null` accordingly. A `Bottom`-typed
    // subexpression's "result" is skipped rather than adapted wherever its
    // *compiled form* is already guaranteed to leave nothing behind — see
    // `isTerminal`, which answers that as a code-generation fact instead of
    // asking this erased type (a `Bottom`-typed value and a compiled form
    // that's actually terminal are related but distinct: an ordinary call to
    // a `Bottom`-declared function, e.g. `abort(...)`, is semantically
    // non-returning but compiles to a plain `invokestatic` the verifier
    // still expects a value from — see `isTerminal`'s doc comment).
    JVMTypes.lower(JVMTypes.representationOf(tp), backend.requireClass)

  def methodDesc(paramTypes: List[Type], resultType: Type): String =
    "(" + paramTypes.map(t => descOf(jvmType(t))).mkString + ")" + descOf(jvmType(resultType))

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
    backend.index(units)

    backend.requireTopLevel(runtime.start)
    val mainClassMethods = new mutable.ArrayBuffer[MethodOut]()
    val cp = new ConstantPool

    // A single interleaved loop, not "drain topLevelWork, then drain
    // classWork": compiling a class's methods (e.g. a lifted lambda's
    // `apply`) can discover new top-level function calls — reaching a
    // context parameter pulls in whatever function provides it, say — and
    // compiling a top-level function can likewise discover new classes via
    // `New`. Draining the two queues in sequence would silently drop
    // whichever queue gained new work after its own pass already finished.
    var pending = backend.nextPending()
    while pending.nonEmpty do
      pending.get match
        case Pending.TopLevel(fdef) =>
          if !isNativeOrIntrinsic(fdef.symbol) then
            mainClassMethods += methodCompiler.compileTopLevel(fdef, cp)
        case Pending.Class(cdef) => addClassFile(classCompiler.compileClass(cdef, cp))
        case Pending.Interface(idef) => addClassFile(classCompiler.compileInterface(idef, cp))
      pending = backend.nextPending()

    // Synthetic entry point: `public static void main(String[] args)`
    mainClassMethods += buildJavaMain(cp)

    val mainBytes = ClassFile.write(cp, MainClassName, ObjectClass, Nil, Nil, mainClassMethods.toList)
    (classFiles.toMap + (MainClassName -> mainBytes), MainClassName)

  private def addClassFile(compiled: (String, Array[Byte])): Unit =
    classFiles(compiled._1) = compiled._2

  private def buildJavaMain(cp: ConstantPool): MethodOut =
    val startSym = backend.resolve(runtime.start)
    val startProcType = startSym.tpe.asProcType
    val startDesc = methodDesc(startProcType.paramTypes ++ startProcType.autoTypes, startProcType.resultType)
    val cw = new CodeWriter(cp)
    cw.touchLocal(0) // String[] args
    cw.invokestatic(MainClassName, backend.topLevelName(startSym), startDesc)
    // `start`'s Jo-level return type is `Unit`, which — like any other Jo
    // value type — now erases to `Ref(Object)` (see the `jvmType` doc
    // comment), not `V`; the real Java `main` truly is `void`, so discard it.
    if jvmType(startProcType.resultType) != V then cw.pop()
    cw.returnVoid()
    val (code, ms, ml) = cw.finish()
    MethodOut(AccessFlags.Public | AccessFlags.Static, "main", "([Ljava/lang/String;)V", Some((code, ms, ml)))

  private def isNativeOrIntrinsic(sym: Symbol): Boolean =
    runtime.nativeSpec(sym).isDefined || sym.hasAnnotation(defn.intrinsic)

end JVMCodeGen

object JVMCodeGen:
  val MainClassName = "Main"

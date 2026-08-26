package jvm

import common.IO

import sast.*
import sast.Types.*
import sast.Trees.FileUnit
import phases.*

import reporting.Reporter
import reporting.Reporter.Step
import reporting.Config

import scala.language.implicitConversions

/***********************************************************************
 *
 * Main entry point for the JVM backend.
 *
 * See docs/jips/jvm-backend.md for the architecture this implements and
 * what is deliberately left out of scope.
 *
 ***********************************************************************/
object Compiler:
  // Default link mappings for the JVM runtime.
  val defaultLinkMappings = Map(
    "jo.abort" -> "jo.jvm.runtime.abort",
    "jo.Array.create" -> "jo.jvm.runtime.RefArray.create",

    // Regex engine hooks, delegating to java.util.regex; see runtime/jvm/Runtime.jo
    "jo.regex.Engine.compilePattern" -> "jo.jvm.runtime.RegexEngine.compilePattern",
    "jo.regex.Engine.execPatternAt"  -> "jo.jvm.runtime.RegexEngine.execPatternAt",
  )

  def main(args: Array[String]): Unit =
    given Reporter = Reporter.createReporter()

    val (config, sources) = cli.OptionParser.parseConfig(args, Config.appOptions)

    given Config = config

    if sources.isEmpty && Config.linkMap.value.isEmpty then
      println("Expect source file as input")
      System.exit(1)

    Reporter.monitor():
      val outDir = Config.outFilePath.value.getOrElse("out")

      val rootNameTable = new NameTable

      given lazyDefn: Definitions.Lazy = Definitions.Lazy(rootNameTable)

      val defaultRuntimePackages = Config.JvmRuntimePath :: Nil

      val units = FrontEnd.run(defaultRuntimePackages, sources, defaultLinkMappings, "jo.jvm.runtime.RefArray") <| "Frontend"

      locally {
        given defn: Definitions = lazyDefn.value

        val jvmRuntime = new JVMRuntime
        val contextParamsLower = new LowerContextParams(jvmRuntime.ParamSupport)

        // Untagged (real JVM primitive/String-equivalent) representation —
        // everything else, including generic type parameters and unresolved
        // `Any`, erases to `Object` with explicit box/unbox `Encoded` nodes
        // inserted wherever tagging actually differs (mirrors native's own
        // `untaggedTypes`, plus `Long_type` since this backend gives Long a
        // real primitive representation too).
        val untaggedTypes = Set(
          defn.Bool_type, defn.Byte_type, defn.Char_type,
          defn.Int_type, defn.Float_type, defn.Long_type,
        )
        // `Bottom` erases to `AnyType` here (unlike every other backend,
        // which defaults to leaving it as `BottomType`) so a `Bottom`-typed
        // value participates in the ordinary Any-erased cast/unbox-at-use
        // scheme — see `Erasure`'s own doc comment for why this backend
        // specifically needs that. Bridge comparison uses the same pure,
        // name-independent representation model as code generation.
        val erasure = new Erasure(
          Erasure.untaggedTypes(untaggedTypes), AnyType,
          bridgeRepresentationMatches = (a, b) => JVMTypes.representationOf(a) == JVMTypes.representationOf(b)
        )
        val closureConvert = new ElimCapture
        val rewire = FrontEnd.rewireMap.value
        val codeGen = new JVMCodeGen(jvmRuntime, rewire)
        // Runs after `closureConvert`, not immediately after `erasure` like
        // `native`'s (see its own `Compiler.scala`) — this backend's whole
        // reason for a separate `InterfaceBridge` phase in the first place
        // is to also cover the SAM-implementing classes `ElimCapture` lifts
        // lambda literals into, which don't exist yet when `erasure` runs.
        // See `InterfaceBridge`'s doc comment.
        val interfaceBridge = new InterfaceBridge(erasure.bridges)
        val jvmLowering = new Lowering(jvmRuntime)

        val backend: Step[List[FileUnit], Unit] =
          Step("Backend", (units: List[FileUnit]) => writeClassFiles(codeGen.generate(units), outDir))

        units               |>
        contextParamsLower  |>
        erasure             |>
        closureConvert      |>
        interfaceBridge     |>
        jvmLowering         |>
        backend
      } <| "Backend"

  private def writeClassFiles(result: (Map[String, Array[Byte]], String), outDir: String): Unit =
    val (generated, mainClass) = result
    val (nodeName, nodeBytes) = RuntimeClasses.nodeClass()
    val (lambdaName, lambdaBytes) = RuntimeClasses.lambdaInterface()
    val allFiles = generated + (nodeName -> nodeBytes) + (lambdaName -> lambdaBytes)

    IO.ensureExists(outDir)
    for (name, bytes) <- allFiles do
      val path = java.nio.file.Paths.get(outDir, name + ".class")
      Option(path.getParent).foreach(java.nio.file.Files.createDirectories(_))
      java.nio.file.Files.write(path, bytes)

    println(s"Wrote ${allFiles.size} class file(s) to $outDir (entry point: $mainClass)")

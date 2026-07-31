package jvm

import common.IO

import sast.*
import sast.Trees.FileUnit
import phases.*

import reporting.Reporter
import reporting.Reporter.Step
import reporting.Config

import scala.language.implicitConversions

/***********************************************************************
 *
 * Main entry point for the JVM compiler prototype.
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
        given Definitions = lazyDefn.value

        val jvmRuntime = new JVMRuntime
        val contextParamsLower = new LowerContextParams(jvmRuntime.ParamSupport)
        val closureConvert = new ElimCapture
        val rewire = FrontEnd.rewireMap.value
        val codeGen = new JVMCodeGen(jvmRuntime, rewire)

        val backend: Step[List[FileUnit], Unit] =
          Step("Backend", (units: List[FileUnit]) => writeClassFiles(codeGen.generate(units), outDir))

        units               |>
        contextParamsLower  |>
        closureConvert      |>
        backend
      } <| "Backend"

  private def writeClassFiles(result: (Map[String, Array[Byte]], String), outDir: String): Unit =
    val (generated, mainClass) = result
    val (nodeName, nodeBytes) = JVMRuntimeClasses.nodeClass()
    val (lambdaName, lambdaBytes) = JVMRuntimeClasses.lambdaInterface()
    val allFiles = generated + (nodeName -> nodeBytes) + (lambdaName -> lambdaBytes)

    IO.ensureExists(outDir)
    for (name, bytes) <- allFiles do
      val path = java.nio.file.Paths.get(outDir, name + ".class")
      java.nio.file.Files.write(path, bytes)

    println(s"Wrote ${allFiles.size} class file(s) to $outDir (entry point: $mainClass)")

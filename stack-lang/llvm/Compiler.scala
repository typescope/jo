package llvm

import sast.*
import phases.*

import reporting.Reporter
import reporting.Reporter.Step
import reporting.Config

import common.IO

import scala.language.implicitConversions
import scala.sys.process.*

/***********************************************************************
 *
 * Main entry point of the LLVM backend
 *
 ***********************************************************************/
object Compiler:
  val keepLL: Config.BooleanSetting = Config.BooleanSetting("--keep-ll", false, "keep the generated LLVM IR file")
  val clang: Config.StringSetting = Config.StringSetting("--clang", "clang", "the clang executable")
  val optLevel: Config.StringSetting = Config.StringSetting("--opt", "2", "clang optimization level")

  // Default link mappings for the runtime
  val defaultLinkMappings = Map(
    "jo.abortImpl"      -> "jo.runtime.native.abortImpl",

    "jo.Array.create" -> "jo.runtime.native.RefArray.create",
    "jo.Array.clear"  -> "jo.runtime.native.RefArray.clear",

    "jo.Bytes.size"     -> "jo.runtime.native.RawBytes.size",
    "jo.Bytes.get"      -> "jo.runtime.native.RawBytes.get",
    "jo.Bytes.slice"    -> "jo.runtime.native.RawBytes.slice",
    "jo.Bytes.toBase64" -> "jo.runtime.native.RawBytes.toBase64",
    "jo.Bytes.fill"     -> "jo.runtime.native.RawBytes.fill",

    // Regex engine hooks
    "jo.regex.Engine.compilePattern" -> "jo.runtime.native.regex.Regex.compilePattern",
    "jo.regex.Engine.execPatternAt"  -> "jo.runtime.native.regex.Regex.execPatternAt",

    // Lists sharing an array
    "jo.ListImpl.newClaim"  -> "jo.runtime.native.ListClaim.newClaim",
    "jo.ListImpl.tryExtend" -> "jo.runtime.native.ListClaim.tryExtend",

    // GC API wiring
    "jo.runtime.native.GC.init"  -> "jo.runtime.native.ChunkAllocator.init",
    "jo.runtime.native.GC.alloc" -> "jo.runtime.native.ChunkAllocator.alloc",
  )

  // Runtime definitions for the frontend
  val runtimeConfig = FrontEnd.RuntimeConfig(
    arrayOpsSection = "jo.runtime.native.RefArray",
    isLambdaValue   = "jo.runtime.native.isLambdaValue",
  )

  def main(args: Array[String]): Unit =
    given Reporter = Reporter.createReporter()

    val options = keepLL :: clang :: optLevel :: Config.appOptions
    val (config, sources) = cli.OptionParser.parseConfig(args, options)

    given Config = config

    // Zero source files is allowed when an entry point is linked in from
    // libraries (e.g. an app that only pulls in packages and links jo.main).
    if sources.isEmpty && Config.linkMap.value.isEmpty then
      println("Expect source file as input")
      System.exit(1)

    Config.useRuntimeApi.value match
      case Some(runtime) =>
        Reporter.error(s"the LLVM backend does not support --use-runtime-api $runtime")
      case _ =>

    Reporter.monitor():
      val outFile = Config.outFilePath.value.getOrElse {
        if sources.size == 1 then
          IO.fileNameNoExt(sources.head)
        else
          "out"
      }

      val rootNameTable = new NameTable
      given lazyDefn: Definitions.Lazy = Definitions.Lazy(rootNameTable)

      val runtimePackages = Config.LLVMRuntimePath :: Nil
      val namespacesSAST = FrontEnd.run(runtimePackages, sources, defaultLinkMappings, runtimeConfig) <| "Frontend"

      locally {
        given defn: Definitions = lazyDefn.value

        val runtime = new LLVMRuntime(FrontEnd.rewireMap.value)

        val untaggedTypes =
          Set(
            defn.Bool_type,
            defn.Byte_type,
            defn.Char_type,
            defn.Int_type,
            defn.Long_type,
            defn.Float_type,
          )
        val contextParamsLower = new phases.LowerContextParams(runtime.ParamSupport)
        val erasure = new Erasure(Erasure.untaggedTypes(untaggedTypes))
        val closureConvert = new ElimCapture
        val boxing = new Boxing(runtime)
        val encodeClass = new EncodeClass(runtime)

        val codegen = Step("codegen", (units: List[sast.Trees.FileUnit]) => new CodeGen(runtime).compile(units))
        val clangStep = Step("clang", (ir: String) => link(ir, outFile))

        namespacesSAST     |>
        contextParamsLower |>
        erasure            |>
        closureConvert     |>
        boxing             |>
        encodeClass        |>
        codegen            |>
        clangStep
      } <| "Backend"

  /** Compile the IR with clang into an executable */
  def link(ir: String, outFile: String)(using Config, Reporter): Unit =
    val llFile = outFile + ".ll"
    IO.writeFile(llFile, ir.getBytes("UTF-8"))

    val cc = clang.value
    val version = clangMajorVersion(cc)

    // clang 14 reads opaque pointers only behind a flag, and its optimizer
    // crashes on them. Unoptimized builds work.
    if version < 15 && optLevel.value != "0" then
      Reporter.error(s"The LLVM backend needs clang 15 or newer for optimized builds, found clang $version. Install a newer clang or pass --opt 0.")
      return

    val opaquePointers =
      if version < 15 then List("-mllvm", "-opaque-pointers") else Nil

    val cmd = List(cc, "-O" + optLevel.value, "-Wno-override-module") ++ opaquePointers ++ List(llFile, "-o", outFile)
    val output = new StringBuilder
    val logger = ProcessLogger(line => output ++= line += '\n', line => output ++= line += '\n')
    val status = cmd.!(logger)

    if !keepLL.value then
      java.io.File(llFile).delete()

    if status != 0 then
      Reporter.error(s"clang failed with exit code $status:\n$output")

  def clangMajorVersion(cc: String): Int =
    val version = List(cc, "--version").!!
    val pattern = raw"version (\d+)\.".r
    pattern.findFirstMatchIn(version) match
      case Some(m) => m.group(1).toInt
      case None => 0
end Compiler

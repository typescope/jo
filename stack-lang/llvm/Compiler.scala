package llvm

import sast.*
import sast.Symbols.Symbol
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
  val regionFunctions: Config.MultiStringSetting = Config.MultiStringSetting("--region", "make a function open a memory region, e.g., --region app.process")

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
    "jo.runtime.native.GC.init"  -> "jo.runtime.native.Regions.init",
    "jo.runtime.native.GC.alloc" -> "jo.runtime.native.Regions.alloc",
  )

  // Runtime definitions for the frontend
  val runtimeConfig = FrontEnd.RuntimeConfig(
    arrayOpsSection = "jo.runtime.native.RefArray",
    isLambdaValue   = "jo.runtime.native.isLambdaValue",
  )

  def main(args: Array[String]): Unit =
    given Reporter = Reporter.createReporter()

    // `--use-runtime-api native` names the 32-bit runtime. This backend has
    // its own runtime with the same API, so check against that one instead.
    val runtimeApi = args.indexOf("--use-runtime-api")
    val useNativeApi = runtimeApi >= 0 && args.lift(runtimeApi + 1).contains("native")
    val args1 =
      if useNativeApi then args.patch(runtimeApi, Array("--lib", Config.LLVMRuntimePath), 2)
      else args

    val options = keepLL :: clang :: optLevel :: regionFunctions :: Config.appOptions
    val (config, sources) = cli.OptionParser.parseConfig(args1, options)

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

      val runtimePackages = if useNativeApi then Nil else Config.LLVMRuntimePath :: Nil
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

        val regions = regionFunctions.value.toSet.flatMap(resolveRegion)

        val codegen = Step("codegen", (units: List[sast.Trees.FileUnit]) =>
          new CodeGen(runtime, regions).compile(units)
        )
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

  /** The top-level function named by `--region name` */
  def resolveRegion(name: String)(using defn: Definitions, rp: Reporter): Option[Symbol] =
    defn.resolveTermOpt(name) match
      // The runtime manages the regions, so it cannot open one
      case Some(sym) if sym.fullName.startsWith("jo.runtime.") =>
        Reporter.error(s"--region $name: a runtime function cannot be a region function")
        None

      case Some(sym) if sym.is(Flags.Fun) && sym.isTopLevel =>
        Some(sym)

      case _ =>
        Reporter.error(s"--region $name: no top-level function of this name")
        None

  /** Compile the IR with clang into an executable */
  def link(ir: String, outFile: String)(using Config, Reporter): Unit =
    val cc = clang.value
    val version = clangMajorVersion(cc) match
      case Some(version) => version
      case None =>
        Reporter.error(s"Cannot run $cc. The LLVM backend needs clang 15 or newer, which --clang selects.")
        return

    // clang 14 reads opaque pointers only behind a flag, and its optimizer
    // crashes on them. Unoptimized builds work.
    if version < 15 && optLevel.value != "0" then
      Reporter.error(s"The LLVM backend needs clang 15 or newer for optimized builds, found clang $version. Install a newer clang or pass --opt 0.")
      return

    val llFile = outFile + ".ll"
    IO.writeFile(llFile, ir.getBytes("UTF-8"))

    val opaquePointers =
      if version < 15 then List("-mllvm", "-opaque-pointers") else Nil

    // libm provides `floor`, which `llvm.floor.f64` lowers to
    val cmd = List(cc, "-O" + optLevel.value, "-Wno-override-module") ++ opaquePointers ++ List(llFile, "-lm", "-o", outFile)
    val output = new StringBuilder
    val logger = ProcessLogger(line => output ++= line += '\n', line => output ++= line += '\n')
    val status = cmd.!(logger)

    if !keepLL.value then
      java.io.File(llFile).delete()

    if status != 0 then
      Reporter.error(s"clang failed with exit code $status:\n$output")

  /** The major version of clang, or `None` if it cannot be run */
  def clangMajorVersion(cc: String): Option[Int] =
    try
      val version = List(cc, "--version").!!
      val pattern = raw"version (\d+)\.".r
      pattern.findFirstMatchIn(version) match
        case Some(m) => Some(m.group(1).toInt)
        case None => Some(0)
    catch
      case _: Exception => None
end Compiler

package doc

import sast.*

import typing.Typer
import reporting.Reporter
import reporting.Config

import java.io.{FileOutputStream, OutputStreamWriter, PrintWriter}
import java.nio.file.{Files, Path, Paths}
import java.nio.charset.StandardCharsets

object Compiler:
  // Doc-specific options
  val outputDir: Config.StringSetting = Config.StringSetting("-d", "docs", "output directory")
  val title: Config.StringSetting = Config.StringSetting("-title", "API Documentation", "project title")
  val includePrivate: Config.BooleanSetting = Config.BooleanSetting("-include-private", false, "include private symbols")
  val includeSource: Config.BooleanSetting = Config.BooleanSetting("-include-source", false, "embed source code")

  val docOptions: List[cli.OptionParser.Setting[?]] =
    outputDir :: title :: includePrivate :: includeSource :: Config.commonOptions

  def main(args: Array[String]): Unit =
    given Reporter = Reporter.createReporter()

    val (config, sources) = cli.OptionParser.parseConfig(args, docOptions)

    if sources.isEmpty then
      println("Usage: jo doc <sources...> [options]")
      println()
      println("Options:")
      println("  -d <dir>           Output directory (default: docs)")
      println("  -title <name>      Project title for documentation")
      println("  -include-private   Include private symbols")
      println("  -include-source    Embed source code in output")
      println()
      println("Examples:")
      println("  jo doc lib/Core.jo lib/List.jo -d site/api")
      println("  jo doc src/main.jo -d docs -title MyProject")
      return

    given Config = config

    Reporter.monitor():
      compile(sources)

  /** Generate documentation for source files */
  def compile(sources: List[String])(using rp: Reporter, config: Config): Unit =
    val rootNameTable = new NameTable
    given lazyDefn: Definitions.Lazy = Definitions.Lazy(rootNameTable)

    // Parse and type check
    val (units, _) = sources |> Typer.parseStep |> Typer.typeStep

    if rp.hasErrors then
      println("Errors occurred during type checking. Documentation not generated.")
      return

    given Definitions = lazyDefn.value

    val outputPath = Paths.get(outputDir.value)
    val includePrivateVal = includePrivate.value

    // Create output directories
    Files.createDirectories(outputPath.resolve("data/symbols"))
    Files.createDirectories(outputPath.resolve("assets"))

    // Emit meta.json
    withWriter(outputPath.resolve("data/meta.json")): out =>
      JsonEmitter.emitMeta(title.value, out)

    // Emit nav.json
    withWriter(outputPath.resolve("data/nav.json")): out =>
      JsonEmitter.emitNav(units, out)

    // Emit search.json
    withWriter(outputPath.resolve("data/search.json")): out =>
      JsonEmitter.emitSearch(units, includePrivateVal, out)

    // Emit symbol files for each namespace
    for unit <- units do
      val fileName = unit.symbol.fullName + ".json"
      withWriter(outputPath.resolve(s"data/symbols/$fileName")): out =>
        JsonEmitter.emitFileUnit(unit, includePrivateVal, out)

    // Emit symbol files for each section
    val allSections = JsonEmitter.collectAllSections(units)
    for sec <- allSections do
      val fileName = sec.symbol.fullName + ".json"
      withWriter(outputPath.resolve(s"data/symbols/$fileName")): out =>
        JsonEmitter.emitSection(sec, includePrivateVal, out)

    // Copy static assets from assets/doc/
    copyAssets(outputPath)

    println(s"Documentation generated in ${outputDir.value}/")
    println(s"  - ${namespaces.size} namespace(s) documented")
    println(s"  - ${allSections.size} section(s) documented")
    println(s"  - Open ${outputDir.value}/index.html in a browser to view")

  /** Helper: create parent dirs, open writer, call block, close */
  private def withWriter(path: Path)(block: PrintWriter => Unit): Unit =
    Files.createDirectories(path.getParent)
    val writer = new PrintWriter(
      new OutputStreamWriter(new FileOutputStream(path.toFile), StandardCharsets.UTF_8)
    )
    try
      block(writer)
    finally
      writer.close()

  /** Copy static assets from assets/doc/ to output directory */
  private def copyAssets(outputDir: Path): Unit =
    val assetsDir = Paths.get(Config.rootDir, "assets", "doc")

    // Copy index.html
    copyFile(assetsDir.resolve("index.html"), outputDir.resolve("index.html"))

    // Copy style.css
    copyFile(assetsDir.resolve("style.css"), outputDir.resolve("assets/style.css"))

    // Copy app.js
    copyFile(assetsDir.resolve("app.js"), outputDir.resolve("assets/app.js"))

    // Copy syntax highlighting files
    copyFile(assetsDir.resolve("marked.min.js"), outputDir.resolve("assets/marked.min.js"))
    copyFile(assetsDir.resolve("highlight.min.js"), outputDir.resolve("assets/highlight.min.js"))
    copyFile(assetsDir.resolve("jo.js"), outputDir.resolve("assets/jo.js"))
    copyFile(assetsDir.resolve("highlight-modern.css"), outputDir.resolve("assets/highlight-modern.css"))

  private def copyFile(source: Path, target: Path): Unit =
    Files.createDirectories(target.getParent)
    if Files.exists(source) then
      Files.copy(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    else
      System.err.println(s"Warning: Asset file not found: $source")

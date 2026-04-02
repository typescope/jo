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
  val outputDir: Config.StringSetting = Config.StringSetting("--out", "docs", "output directory")
  val title: Config.StringSetting = Config.StringSetting("--title", "API Documentation", "project title")
  val readme: Config.StringSetting = Config.StringSetting("--readme", "", "markdown file to use as home page")
  val includePrivate: Config.BooleanSetting = Config.BooleanSetting("--include-private", false, "include private symbols")
  val includeSource: Config.BooleanSetting = Config.BooleanSetting("--include-source", false, "embed source code")

  val docOptions: List[cli.OptionParser.Setting[?]] =
    outputDir :: title :: readme :: includePrivate :: includeSource :: Config.commonOptions

  def main(args: Array[String]): Unit =
    given Reporter = Reporter.createReporter()

    val (config, sources) = cli.OptionParser.parseConfig(args, docOptions)

    if sources.isEmpty then
      println("Usage: jo doc <sources...> [options]")
      println()
      println("Options:")
      println("  --out <dir>            Output directory (default: docs)")
      println("  --title <name>         Project title for documentation")
      println("  --include-private      Include private symbols")
      println("  --include-source       Embed source code in output")
      println()
      println("Examples:")
      println("  jo doc lib/Core.jo lib/List.jo --out site/api")
      println("  jo doc src/main.jo --out docs --title MyProject")
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
    Files.createDirectories(outputPath.resolve("assets"))

    // Collect all symbols to emit
    val groupedUnits = units.groupBy(_.owner).toList.sortBy(_._1.fullName)
    val allSections  = JsonEmitter.collectAllSections(units, includePrivateVal)

    // Emit a single data.js containing all doc data as a JS variable.
    // This avoids fetch() calls so the docs can be opened directly via file://.
    val homeMarkdown: Option[String] =
      val path = readme.value
      if path.nonEmpty then Some(new String(Files.readAllBytes(Paths.get(path)), java.nio.charset.StandardCharsets.UTF_8))
      else None

    withWriter(outputPath.resolve("data.js")): out =>
      out.print("var JO_DOC_DATA={\"meta\":")
      JsonEmitter.emitMeta(title.value, out)

      homeMarkdown match
        case Some(md) =>
          out.print(",\"home\":")
          JsonEmitter.emitString(md, out)
        case None =>

      out.print(",\"nav\":")
      JsonEmitter.emitNav(units, includePrivateVal, out)

      out.print(",\"search\":")
      JsonEmitter.emitSearch(units, includePrivateVal, out)

      out.print(",\"symbols\":{")

      var first = true

      for (sym, groupUnits) <- groupedUnits do
        if !first then out.print(",")
        first = false
        out.print(s"\"${sym.fullName}\":")
        JsonEmitter.emitNamespace(groupUnits, includePrivateVal, out)

      for sec <- allSections do
        if !first then out.print(",")
        first = false
        out.print(s"\"${sec.symbol.fullName}\":")
        JsonEmitter.emitSection(sec, includePrivateVal, out)

      out.print("}}")

    // Copy static assets from assets/doc/
    copyAssets(outputPath)

    println(s"Documentation generated in ${outputDir.value}/")
    println(s"  - ${groupedUnits.size} namespace(s) documented")
    println(s"  - ${allSections.size} section(s) documented")
    println(s"  Open ${outputDir.value}/index.html directly in a browser to view")

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
    if !Files.isDirectory(assetsDir) then
      Reporter.abortInternal(
        s"Documentation assets not found: $assetsDir. This Jo installation is incomplete."
      )

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
      Reporter.abortInternal(
        s"Documentation asset not found: $source. This Jo installation is incomplete."
      )

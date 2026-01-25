package doc

import sast.*

import typing.Typer
import reporting.Reporter
import reporting.Config

import java.io.{FileOutputStream, OutputStreamWriter, PrintWriter}
import java.nio.file.{Files, Path, Paths}
import java.nio.charset.StandardCharsets

object Compiler:
  def main(args: Array[String]): Unit =
    given Reporter = Reporter.createReporter()

    // Parse doc-specific options first, then pass the rest to Config parser
    var sources = List.empty[String]
    var outputDir = "docs"
    var title = "API Documentation"
    var includePrivate = false
    var includeSource = false
    val remainingArgs = scala.collection.mutable.ArrayBuffer[String]()

    var i = 0
    while i < args.length do
      args(i) match
        case "-d" if i + 1 < args.length =>
          outputDir = args(i + 1)
          i += 2
        case "--title" if i + 1 < args.length =>
          title = args(i + 1)
          i += 2
        case "--include-private" =>
          includePrivate = true
          i += 1
        case "--include-source" =>
          includeSource = true
          i += 1
        case arg if !arg.startsWith("-") =>
          sources = sources :+ arg
          i += 1
        case arg =>
          // Pass through to standard option parser
          remainingArgs += arg
          i += 1

    if sources.isEmpty then
      println("Usage: jo doc <sources...> -d <output-dir> [options]")
      println()
      println("Options:")
      println("  -d <dir>           Output directory (default: docs)")
      println("  --title <name>     Project title for documentation")
      println("  --include-private  Include private symbols")
      println("  --include-source   Embed source code in output")
      println()
      println("Examples:")
      println("  jo doc lib/Core.jo lib/List.jo -d site/api")
      println("  jo doc src/main.jo -d docs --title \"My Project\"")
      return

    val (config, _) = cli.OptionParser.parseConfig(remainingArgs.toArray, Config.commonOptions)
    given Config = config

    Reporter.monitor():
      compile(sources, title, outputDir, includePrivate, includeSource)

  /** Generate documentation for source files */
  def compile(sources: List[String], title: String, outputDir: String,
              includePrivate: Boolean, includeSource: Boolean)
             (using rp: Reporter, config: Config): Unit =

    val rootNameTable = new NameTable
    given lazyDefn: Definitions.Lazy = Definitions.Lazy(rootNameTable)

    // Parse and type check
    val (namespaces, _) = sources |> Typer.parseStep |> Typer.typeStep

    if rp.hasErrors then
      println("Errors occurred during type checking. Documentation not generated.")
      return

    given Definitions = lazyDefn.value

    val outputPath = Paths.get(outputDir)

    // Create output directories
    Files.createDirectories(outputPath.resolve("data/symbols"))
    Files.createDirectories(outputPath.resolve("assets"))

    // Emit meta.json
    withWriter(outputPath.resolve("data/meta.json")): out =>
      JsonEmitter.emitMeta(title, out)

    // Emit nav.json
    withWriter(outputPath.resolve("data/nav.json")): out =>
      JsonEmitter.emitNav(namespaces, out)

    // Emit search.json
    withWriter(outputPath.resolve("data/search.json")): out =>
      JsonEmitter.emitSearch(namespaces, includePrivate, out)

    // Emit symbol files for each namespace
    for ns <- namespaces do
      val fileName = ns.symbol.fullName + ".json"
      withWriter(outputPath.resolve(s"data/symbols/$fileName")): out =>
        JsonEmitter.emitLeafNamespace(ns, includePrivate, includeSource, out)

    // Copy static assets from assets/doc/
    copyAssets(outputPath)

    println(s"Documentation generated in $outputDir/")
    println(s"  - ${namespaces.size} namespace(s) documented")
    println(s"  - Open $outputDir/index.html in a browser to view")

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

  private def copyFile(source: Path, target: Path): Unit =
    Files.createDirectories(target.getParent)
    if Files.exists(source) then
      Files.copy(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    else
      System.err.println(s"Warning: Asset file not found: $source")

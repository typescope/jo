package query

import sast.*
import sast.Trees.FileUnit

import typing.Typer
import reporting.{Config, Reporter}

import java.io.{OutputStreamWriter, PrintWriter}
import java.nio.charset.StandardCharsets

object Compiler:
  val selectors: Config.StringSetting =
    Config.StringSetting("--query", "", "comma-separated documentation selectors")

  val fields: Config.StringSetting =
    Config.StringSetting("--fields", "name,signature,doc", "comma-separated query output fields")

  val queryOptions: List[cli.OptionParser.Setting[?]] =
    selectors :: fields :: Config.commonOptions

  def main(args: Array[String]): Unit =
    given Reporter = Reporter.createReporter()

    val (config, sources) = cli.OptionParser.parseConfig(args, queryOptions)
    given Config = config

    val queryText = selectors.value.trim
    if queryText.isEmpty then
      println("Usage: jo compile --query <selectors> [files...] [options]")
      println()
      println("Options:")
      println("  --query <selectors>    Select symbols or source files, e.g. jo.List.map,file:Byte.jo")
      println("  --fields <fields>      Select output fields (default: name,signature,doc)")
      System.exit(1)

    Reporter.monitor():
      val selectedFields = Query.parseFields(fields.value)
      if !summon[Reporter].hasErrors then compile(queryText, selectedFields, sources)

  def compile(queryText: String, fields: Set[String], sources: List[String])(using rp: Reporter, config: Config): Unit =
    val rootNameTable = new NameTable
    given lazyDefn: Definitions.Lazy = Definitions.Lazy(rootNameTable)

    if sources.isEmpty && Config.libPaths.value.isEmpty then
      Query.reportNoMatches(queryText)
      return

    val (units, delayedUnits) = sources |> Typer.parseStep |> Typer.typeStep
    if rp.hasErrors then return

    given defn: Definitions = lazyDefn.value
    val filter = Query.parse(queryText, defn.rootNameTable)
    if rp.hasErrors then return

    delayedUnits.forceIf: unit =>
      filter.selectsFile(unit.sourceFile) ||
      filter.symbols.exists: querySymbol =>
        unit.owner.containedIn(querySymbol) || querySymbol.containedIn(unit.owner)

    val libraryUnits = delayedUnits.force()
    val filteredUnits = Query.filterUnits(units, libraryUnits, filter)
    if rp.hasErrors then return

    writeJson(filteredUnits, filter, fields)

  private def writeJson(units: List[FileUnit], filter: Query.Filter, fields: Set[String])(using Reporter, Definitions): Unit =
    val out = new PrintWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8))
    Query.emitJson(units, filter, fields, false, out)
    out.flush()

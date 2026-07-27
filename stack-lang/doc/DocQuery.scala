package doc

import reporting.Reporter
import sast.Symbols.*

import java.nio.file.Paths
import scala.collection.mutable

object DocQuery:
  enum Selector:
    case SymbolSelector(name: String)
    case FileSelector(raw: String, file: String)

  def select(index: DocIndex, rawQuery: String)(using Reporter): List[DocEntry] =
    select(index, parse(rawQuery))

  def select(index: DocIndex, selectors: List[Selector])(using Reporter): List[DocEntry] =
    val selected =
      if selectors.isEmpty then index.sourceRoots
      else selectors.flatMap(resolveSelector(index, _))

    DocModel.sortEntries(pruneDescendants(selected).distinct)

  private def resolveSelector(index: DocIndex, selector: Selector)(using Reporter): List[DocEntry] =
    selector match
      case Selector.FileSelector(raw, file) =>
        val matches = index.entries.filter(entry => matchesFile(entry, file))
        if matches.isEmpty then Reporter.error(s"No documentation entries match file selector `$raw`")
        matches

      case Selector.SymbolSelector(name) =>
        val matches = resolveSymbol(index, name)
        if matches.isEmpty then Reporter.error(s"No documentation entries match symbol selector `$name`")
        matches

  private def resolveSymbol(index: DocIndex, selector: String): List[DocEntry] =
    val exactNames =
      if selector.contains(".") then List(selector)
      else List(selector, "jo." + selector)

    val exact = index.entries.filter(entry => exactNames.contains(entry.name))
    if exact.nonEmpty then exact
    else if selector.contains(".") then Nil
    else index.entries.filter(_.symbol.name == selector)

  def parse(rawQuery: String): List[Selector] =
    rawQuery.split(",").map(_.trim).filter(_.nonEmpty).map { selector =>
      if selector.startsWith("file:") then
        Selector.FileSelector(selector, selector.stripPrefix("file:"))
      else
        val name =
          if selector.endsWith(".*") then selector.stripSuffix(".*")
          else selector
        Selector.SymbolSelector(name)
    }.toList

  private def matchesFile(entry: DocEntry, rawFile: String): Boolean =
    entry.source.exists: source =>
      val rawNormalized = normalize(rawFile)
      val sourceNormalized = normalize(source.file)
      rawFile == source.file ||
      rawNormalized == sourceNormalized ||
      absolute(rawFile) == absolute(source.file)

  private def normalize(path: String): String =
    Paths.get(path).normalize().toString

  private def absolute(path: String): String =
    Paths.get(path).toAbsolutePath.normalize().toString

  private def pruneDescendants(entries: List[DocEntry]): List[DocEntry] =
    val selected = mutable.HashSet.empty[Symbol]
    selected ++= entries.map(_.symbol)

    entries.filterNot: entry =>
      entry.symbol.ownersIterator.exists(selected.contains)

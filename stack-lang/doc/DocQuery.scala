package doc

import reporting.Reporter
import sast.Symbols.Symbol

import java.nio.file.Paths
import scala.collection.mutable

object DocQuery:
  def select(index: DocIndex, rawQuery: String)(using Reporter): List[DocEntry] =
    val selectors = parseSelectors(rawQuery)
    val selected =
      if selectors.isEmpty then index.sourceRoots
      else selectors.flatMap(resolveSelector(index, _))

    DocModel.sortEntries(pruneDescendants(DocModel.distinctEntries(selected)))

  private def parseSelectors(rawQuery: String): List[String] =
    rawQuery.split(",").map(_.trim).filter(_.nonEmpty).toList

  private def resolveSelector(index: DocIndex, selector: String)(using Reporter): List[DocEntry] =
    if selector.startsWith("file:") then
      val file = selector.stripPrefix("file:")
      val matches = index.entries.filter(entry => matchesFile(entry, file))
      if matches.isEmpty then Reporter.error(s"No documentation entries match file selector `$selector`")
      matches
    else
      val symbolSelector =
        if selector.endsWith(".*") then selector.stripSuffix(".*")
        else selector

      val matches = resolveSymbol(index, symbolSelector)
      if matches.isEmpty then Reporter.error(s"No documentation entries match symbol selector `$selector`")
      matches

  private def resolveSymbol(index: DocIndex, selector: String): List[DocEntry] =
    val exactNames =
      if selector.contains(".") then List(selector)
      else List(selector, "jo." + selector)

    val exact = index.entries.filter(entry => exactNames.contains(entry.name))
    if exact.nonEmpty then exact
    else if selector.contains(".") then Nil
    else index.entries.filter(_.symbol.name == selector)

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

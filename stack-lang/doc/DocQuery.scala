package doc

import reporting.Reporter
import sast.*
import sast.Denotations.*
import sast.Symbols.*

import java.nio.file.Paths
import scala.collection.mutable

object DocQuery:
  enum Selector:
    case SymbolSelector(raw: String, name: String)
    case FileSelector(raw: String, file: String)

  def select(index: DocIndex, rawQuery: String)(using Reporter): List[DocEntry] =
    select(index, parse(rawQuery))

  def select(index: DocIndex, selectors: List[Selector])(using Reporter): List[DocEntry] =
    val selected =
      if selectors.isEmpty then index.sourceRoots
      else selectors.flatMap(resolveSelector(index, _))

    DocModel.sortEntries(pruneDescendants(DocModel.distinctEntries(selected)))

  def parse(rawQuery: String): List[Selector] =
    rawQuery.split(",").map(_.trim).filter(_.nonEmpty).map { selector =>
      if selector.startsWith("file:") then
        Selector.FileSelector(selector, selector.stripPrefix("file:"))
      else
        val name =
          if selector.endsWith(".*") then selector.stripSuffix(".*")
          else selector
        Selector.SymbolSelector(selector, name)
    }.toList

  def forceSymbols(selectors: List[Selector])(using Definitions): Unit =
    val seen = mutable.HashSet.empty[Symbol]
    for selector <- selectors do
      selector match
        case Selector.SymbolSelector(_, name) =>
          resolveSastSymbols(name).foreach(forceSymbol(_, seen))

        case Selector.FileSelector(_, _) =>
          ()

  private def resolveSelector(index: DocIndex, selector: Selector)(using Reporter): List[DocEntry] =
    selector match
      case Selector.FileSelector(raw, file) =>
        val matches = index.entries.filter(entry => matchesFile(entry, file))
        if matches.isEmpty then Reporter.error(s"No documentation entries match file selector `$raw`")
        matches

      case Selector.SymbolSelector(raw, name) =>
        val matches = resolveSymbol(index, name)
        if matches.isEmpty then Reporter.error(s"No documentation entries match symbol selector `$raw`")
        matches

  private def resolveSymbol(index: DocIndex, selector: String): List[DocEntry] =
    val exactNames =
      if selector.contains(".") then List(selector)
      else List(selector, "jo." + selector)

    val exact = index.entries.filter(entry => exactNames.contains(entry.name))
    if exact.nonEmpty then exact
    else if selector.contains(".") then Nil
    else index.entries.filter(_.symbol.name == selector)

  private def resolveSastSymbols(selector: String)(using defn: Definitions): List[Symbol] =
    val paths =
      if selector.contains(".") then List(selector)
      else List(selector, "jo." + selector)

    distinctSymbols(paths.flatMap(path => resolvePath(path.split('.').filter(_.nonEmpty).toList)))

  private def resolvePath(parts: List[String])(using defn: Definitions): List[Symbol] =
    def resolveInNameTable(table: NameTable, name: String): List[Symbol] =
      table.resolve(name) ++ table.resolveAnnotation(name)

    def resolveFromSymbol(sym: Symbol, rest: List[String]): List[Symbol] =
      rest match
        case Nil => List(sym)
        case name :: tail =>
          val members =
            if sym.isContainer then
              resolveInNameTable(sym.nameTable, name)
            else
              sym.info match
                case classInfo: ClassInfo => classInfo.getMemberSymbol(name).toList
                case _ => Nil
          members.flatMap(resolveFromSymbol(_, tail))

    parts match
      case Nil => Nil
      case name :: tail =>
        resolveInNameTable(defn.rootNameTable, name).flatMap(resolveFromSymbol(_, tail))

  private def forceSymbol(sym: Symbol, seen: mutable.HashSet[Symbol])(using Definitions): Unit =
    if !seen.contains(sym) then
      seen += sym
      if sym.isContainer then
        sym.annotations
        forceNameTable(sym.nameTable, seen)
      else
        sym.info
        sym.annotations
        if sym.isClass || sym.isInterface then
          val classInfo = sym.classInfo
          classInfo.fields.foreach(forceSymbol(_, seen))
          classInfo.methods.foreach(forceSymbol(_, seen))

  private def forceNameTable(table: NameTable, seen: mutable.HashSet[Symbol])(using Definitions): Unit =
    val symbols = table.terms ++ table.types ++ table.patterns ++ table.containers
    symbols.foreach(forceSymbol(_, seen))

  private def distinctSymbols(symbols: List[Symbol]): List[Symbol] =
    val seen = mutable.HashSet.empty[Symbol]
    symbols.filter: sym =>
      if seen.contains(sym) then false
      else
        seen += sym
        true

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

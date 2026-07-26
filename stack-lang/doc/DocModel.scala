package doc

import sast.*
import sast.Symbols.*
import sast.Trees.*
import sast.Types.*
import sast.Denotations.*
import sast.Flags

import scala.collection.mutable

case class SourceLoc(file: String, line: Int)

case class DocAnnotation(name: String, args: List[Constant])

case class DocEntry(
  symbol: Symbol,
  name: String,
  kind: String,
  signature: String,
  source: Option[SourceLoc],
  visibility: String,
  flags: List[String],
  annotations: List[DocAnnotation],
  doc: Option[String],
  members: List[DocEntry] = Nil,
  views: List[String] = Nil,
):
  def isAggregate: Boolean =
    kind == "namespace" || kind == "section" || kind == "class"

case class DocIndex(entries: List[DocEntry], sourceRoots: List[DocEntry])

object DocModel:
  def build(sourceUnits: List[FileUnit], libraryUnits: List[FileUnit], includePrivate: Boolean)(using Definitions): DocIndex =
    val allUnits = sourceUnits ++ libraryUnits
    val cache = mutable.HashMap.empty[Symbol, Option[DocEntry]]

    def visible(sym: Symbol): Boolean =
      includePrivate || !sym.isPrivate

    def sourceLoc(sym: Symbol): Option[SourceLoc] =
      if sym.sourcePos == null then None
      else Some(SourceLoc(sym.source.file, sym.sourcePos.startLine + 1))

    def visibility(sym: Symbol): String =
      sym.visibility match
        case Visibility.Default => "public"
        case Visibility.Private(within) =>
          if within == sym.owner then "private"
          else s"private[${within.fullName}]"

    def docs(sym: Symbol): Option[String] =
      val lines = summon[Definitions].index.docComment(sym)
      if lines.isEmpty then None else Some(lines.mkString("\n"))

    def annotations(sym: Symbol): List[DocAnnotation] =
      sym.annotations.map: annot =>
        DocAnnotation(annot.symbol.fullName, annot.args)

    def flags(sym: Symbol): List[String] =
      Flags.flagStrings(sym.flags)

    def typeParams(params: List[Symbol]): String =
      if params.isEmpty then "" else params.map(_.name).mkString("[", ", ", "]")

    def symbolBase(sym: Symbol, kind: String, signature: String, members: List[DocEntry] = Nil, views: List[String] = Nil): DocEntry =
      DocEntry(
        symbol = sym,
        name = sym.fullName,
        kind = kind,
        signature = signature,
        source = sourceLoc(sym),
        visibility = visibility(sym),
        flags = flags(sym),
        annotations = annotations(sym),
        doc = docs(sym),
        members = sortEntries(members),
        views = views,
      )

    def procSignature(sym: Symbol): String =
      sym.tpe.asProcType.show

    def funSignature(fd: FunDef): String =
      val sym = fd.symbol
      if sym.is(Flags.Annotation) then
        "annotation " + sym.name + procSignature(sym).stripSuffix(": void receives none").stripSuffix(": void")
      else if sym.is(Flags.Constructor) then
        "constructor" + procSignature(sym)
      else
        "def " + sym.name + procSignature(sym)

    def patternSignature(pd: PatDef): String =
      "pattern " + pd.symbol.name + procSignature(pd.symbol)

    def typeSignature(td: TypeDef): String =
      val sym = td.symbol
      val info = sym.info
      val rhs =
        if sym.is(Flags.Defer) then ""
        else
          info match
            case TypeOperatorInfo(_, body, _) => " = " + body.show
            case tp: Type => " = " + tp.show
            case other => " = " + other.toString
      "type " + sym.name + typeParams(td.tparams) + rhs

    def classSignature(cd: ClassDef): String =
      val sym = cd.symbol
      val prefix =
        if sym.is(Flags.Object) then "object"
        else if sym.isInterface then "interface"
        else "class"
      prefix + " " + sym.name + typeParams(cd.tparams)

    def interfaceSignature(id: InterfaceDef): String =
      "interface " + id.symbol.name + typeParams(id.tparams)

    def fieldSignature(field: FieldDecl): String =
      val sym = field.symbol
      val prefix = if sym.isMutable then "var " else "val "
      prefix + sym.name + ": " + field.tpt.tpe.show

    def entryForField(field: FieldDecl): Option[DocEntry] =
      val sym = field.symbol
      if !visible(sym) then None
      else Some(symbolBase(sym, "field", fieldSignature(field)))

    def entryForFun(fd: FunDef): Option[DocEntry] =
      val sym = fd.symbol
      if !visible(sym) || sym.is(Flags.Object) then None
      else
        val kind = if sym.is(Flags.Constructor) then "constructor" else "def"
        Some(symbolBase(sym, kind, funSignature(fd)))

    def entryForDef(defn: Def): Option[DocEntry] =
      cache.getOrElseUpdate(defn.symbol,
        if !visible(defn.symbol) then None
        else
          defn match
            case pd: ParamDef =>
              Some(symbolBase(pd.symbol, "param", "param " + pd.name + ": " + pd.tpt.tpe.show))

            case td: TypeDef =>
              Some(symbolBase(td.symbol, "type", typeSignature(td)))

            case fd: FunDef =>
              entryForFun(fd)

            case pd: PatDef =>
              if pd.resultType.tpe.isSingletonObjectType then None
              else Some(symbolBase(pd.symbol, "pattern", patternSignature(pd)))

            case cd: ClassDef =>
              val fields = cd.vals.flatMap(entryForField)
              val funs = cd.funs.flatMap(entryForFun)
              val views = cd.views.map(_.tpe.show)
              Some(symbolBase(cd.symbol, "class", classSignature(cd), fields ++ funs, views))

            case id: InterfaceDef =>
              val methods = id.methods.flatMap(entryForFun)
              Some(symbolBase(id.symbol, "class", interfaceSignature(id), methods))

            case sec: Section =>
              val members = sec.defs.flatMap(entryForDef)
              Some(symbolBase(sec.symbol, "section", "section " + sec.symbol.name, members))
      )

    def namespaceEntry(sym: Symbol, units: List[FileUnit]): Option[DocEntry] =
      if !visible(sym) then None
      else
        val members = units.flatMap(_.defs.flatMap(entryForDef))
        Some(symbolBase(sym, "namespace", "namespace " + sym.fullName, members))

    val namespaceEntries =
      allUnits.groupBy(_.owner).toList.sortBy(_._1.fullName).flatMap(namespaceEntry)

    val sourceRoots =
      sourceUnits.flatMap(unit => unit.defs.flatMap(entryForDef))

    val entries =
      distinctEntries(namespaceEntries.flatMap(flattenEntry) ++ sourceRoots.flatMap(flattenEntry))

    DocIndex(entries, sortEntries(sourceRoots))

  def flattenEntry(entry: DocEntry): List[DocEntry] =
    entry :: entry.members.flatMap(flattenEntry)

  def distinctEntries(entries: List[DocEntry]): List[DocEntry] =
    val seen = mutable.HashSet.empty[Symbol]
    entries.filter: entry =>
      if seen.contains(entry.symbol) then false
      else
        seen += entry.symbol
        true

  def sortEntries(entries: List[DocEntry]): List[DocEntry] =
    entries.map(sortEntry).sortBy(sortKey)

  private def sortEntry(entry: DocEntry): DocEntry =
    entry.copy(members = sortEntries(entry.members))

  private def sortKey(entry: DocEntry): (String, Int, String, String) =
    val file = entry.source.map(_.file).getOrElse("")
    val line = entry.source.map(_.line).getOrElse(0)
    (file, line, entry.kind, entry.name)

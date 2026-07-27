package doc

import reporting.Reporter
import sast.*
import sast.Symbols.*
import sast.Trees.*
import sast.Types.*
import sast.Denotations.*
import sast.Flags

import java.io.PrintWriter
import java.nio.file.Paths
import scala.collection.mutable

object DocQuery:
  enum Selector:
    case SymbolSelector(name: String)
    case FileSelector(raw: String, file: String)

  enum ResolvedSelector:
    case SymbolSelector(name: String, symbols: List[Symbol])
    case FileSelector(raw: String, file: String)

  enum DocTarget:
    case Namespace(sym: Symbol, units: List[FileUnit])
    case Definition(defn: Def)
    case Field(field: FieldDecl)

    def symbol: Symbol =
      this match
        case Namespace(sym, _) => sym
        case Definition(defn)  => defn.symbol
        case Field(field)      => field.symbol

  def resolveSelectors(nameTable: NameTable, selectors: List[Selector])(using Definitions): List[ResolvedSelector] =
    selectors.map:
      case Selector.SymbolSelector(path) =>
        ResolvedSelector.SymbolSelector(path, resolveSymbol(nameTable, path.split('.').filter(_.nonEmpty).toList))

      case Selector.FileSelector(raw, file) =>
        ResolvedSelector.FileSelector(raw, file)

  def querySymbols(selectors: List[ResolvedSelector]): List[Symbol] =
    selectors.flatMap:
      case ResolvedSelector.SymbolSelector(_, symbols) => symbols
      case ResolvedSelector.FileSelector(_, _) => Nil

  def resolveSymbol(nameTable: NameTable, parts: List[String])(using Definitions): List[Symbol] =
    parts match
      case Nil => Nil

      case name :: Nil =>
        nameTable.resolve(name)

      case name :: rest =>
        nameTable.resolveContainer(name) match
          case Some(sym) =>
            resolveSymbol(sym.nameTable, rest)

          case None =>
            rest match
              case memberName :: Nil =>
                nameTable.resolveType(name) match
                  case Some(sym) if sym.isOneOf(Flags.Class | Flags.Interface) =>
                    sym.classInfo.getMemberSymbol(memberName).toList

                  case _ => Nil

              case _ => Nil

  def filterUnits(sourceUnits: List[FileUnit], libraryUnits: List[FileUnit], selectors: List[ResolvedSelector])(using Reporter): List[FileUnit] =
    if selectors.isEmpty then
      sourceUnits
    else
      val allUnits = sourceUnits ++ libraryUnits
      val selected = selectors.flatMap:
        case ResolvedSelector.FileSelector(raw, file) =>
          val matches = allUnits.filter(unit => matchesFile(unit.source.file, file))
          if matches.isEmpty then Reporter.error(s"No documentation entries match file selector `$raw`")
          matches

        case ResolvedSelector.SymbolSelector(_, symbols) =>
          allUnits.filter: unit =>
            symbols.exists: sym =>
              unit.owner.containedIn(sym) || sym.containedIn(unit.owner)

      distinctUnits(selected)

  def select(units: List[FileUnit], selectors: List[ResolvedSelector], includePrivate: Boolean)(using Reporter, Definitions): List[DocTarget] =
    val selected =
      if selectors.isEmpty then
        sourceTargets(units, includePrivate)
      else
        selectors.flatMap(resolveSelector(units, _, includePrivate))

    pruneDescendants(mergeTargets(selected))

  def reportNoMatches(selectors: List[Selector])(using Reporter): Unit =
    selectors.foreach:
      case Selector.FileSelector(raw, _) =>
        Reporter.error(s"No documentation entries match file selector `$raw`")

      case Selector.SymbolSelector(name) =>
        Reporter.error(s"No documentation entries match symbol selector `$name`")

  private def resolveSelector(units: List[FileUnit], selector: ResolvedSelector, includePrivate: Boolean)(using Reporter, Definitions): List[DocTarget] =
    selector match
      case ResolvedSelector.FileSelector(raw, file) =>
        val matches = units.filter(unit => matchesFile(unit.source.file, file))
        val targets = namespaceTargets(matches)
        if targets.isEmpty then Reporter.error(s"No documentation entries match file selector `$raw`")
        targets

      case ResolvedSelector.SymbolSelector(name, symbols) =>
        val targets = symbols.flatMap(sym => targetForSymbol(units, sym, includePrivate))
        if targets.isEmpty then Reporter.error(s"No documentation entries match symbol selector `$name`")
        targets

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

  private def matchesFile(sourceFile: String, rawFile: String): Boolean =
    val rawNormalized = normalize(rawFile)
    val sourceNormalized = normalize(sourceFile)
    rawFile == sourceFile ||
    rawNormalized == sourceNormalized ||
    absolute(rawFile) == absolute(sourceFile)

  private def normalize(path: String): String =
    Paths.get(path).normalize().toString

  private def absolute(path: String): String =
    Paths.get(path).toAbsolutePath.normalize().toString

  private def mergeTargets(targets: List[DocTarget]): List[DocTarget] =
    val namespaceUnits = mutable.LinkedHashMap.empty[Symbol, mutable.ArrayBuffer[FileUnit]]
    val otherTargets = mutable.LinkedHashMap.empty[Symbol, DocTarget]

    for target <- targets do
      target match
        case DocTarget.Namespace(sym, units) =>
          val existing = namespaceUnits.getOrElseUpdate(sym, mutable.ArrayBuffer.empty)
          for unit <- units do
            if !existing.exists(_ eq unit) then existing += unit

        case _ =>
          otherTargets.getOrElseUpdate(target.symbol, target)

    val namespaces = namespaceUnits.toList.map:
      case (sym, units) => DocTarget.Namespace(sym, units.toList)

    namespaces ++ otherTargets.values

  private def pruneDescendants(targets: List[DocTarget]): List[DocTarget] =
    val selected = mutable.HashSet.empty[Symbol]
    selected ++= targets.map(_.symbol)

    targets.filterNot: target =>
      target.symbol.ownersIterator.exists(selected.contains)

  private def distinctUnits(units: List[FileUnit]): List[FileUnit] =
    val seen = mutable.HashSet.empty[FileUnit]
    units.filter: unit =>
      if seen.contains(unit) then false
      else
        seen += unit
        true

  def emitJson(targets: List[DocTarget], includePrivate: Boolean, out: PrintWriter)(using Definitions): Unit =
    val sortedTargets = sortTargets(targets)
    out.println("[")
    emitTargetList(sortedTargets, includePrivate, out, "  ")
    if sortedTargets.nonEmpty then out.println()
    out.println("]")

  def sourceTargets(units: List[FileUnit], includePrivate: Boolean)(using Definitions): List[DocTarget] =
    units.flatMap(unit => unit.defs.flatMap(targetForDef(_, includePrivate)))

  def namespaceTargets(units: List[FileUnit]): List[DocTarget] =
    units.groupBy(_.owner).toList.sortBy(_._1.fullName).map:
      case (owner, ownerUnits) => DocTarget.Namespace(owner, ownerUnits.sortBy(_.source.file))

  def targetForSymbol(units: List[FileUnit], sym: Symbol, includePrivate: Boolean)(using Definitions): List[DocTarget] =
    val namespaceUnits = units.filter(_.owner == sym)
    if namespaceUnits.nonEmpty then
      List(DocTarget.Namespace(sym, namespaceUnits))
    else
      symbolTargets(units, includePrivate).get(sym).toList

  def targetForDef(defn: Def, includePrivate: Boolean)(using Definitions): Option[DocTarget] =
    if !visible(defn.symbol, includePrivate) then None
    else
      defn match
        case fd: FunDef if fd.symbol.is(Flags.Object) =>
          None

        case pd: PatDef if pd.resultType.tpe.isSingletonObjectType =>
          None

        case _ =>
          Some(DocTarget.Definition(defn))

  def targetForField(field: FieldDecl, includePrivate: Boolean): Option[DocTarget] =
    if visible(field.symbol, includePrivate) then Some(DocTarget.Field(field))
    else None

  def visible(sym: Symbol, includePrivate: Boolean): Boolean =
    includePrivate || !sym.isPrivate

  def sortTargets(targets: List[DocTarget]): List[DocTarget] =
    targets.map(sortTarget).sortBy(sortKey)

  private def symbolTargets(units: List[FileUnit], includePrivate: Boolean)(using Definitions): Map[Symbol, DocTarget] =
    val targets = mutable.LinkedHashMap.empty[Symbol, DocTarget]

    def add(target: DocTarget): Unit =
      targets.getOrElseUpdate(target.symbol, target)

    def addDef(defn: Def): Unit =
      targetForDef(defn, includePrivate).foreach(add)

      defn match
        case cd: ClassDef =>
          cd.vals.flatMap(targetForField(_, includePrivate)).foreach(add)
          cd.funs.foreach(addDef)

        case id: InterfaceDef =>
          id.methods.foreach(addDef)

        case sec: Section =>
          sec.defs.foreach(addDef)

        case _ =>

    units.foreach: unit =>
      unit.defs.foreach(addDef)

    targets.toMap

  private def sortTarget(target: DocTarget): DocTarget =
    target match
      case DocTarget.Namespace(sym, units) =>
        DocTarget.Namespace(sym, units.sortBy(_.source.file))

      case _ =>
        target

  private def sortKey(target: DocTarget): (String, Int, String, String) =
    val source = sourceLoc(target.symbol)
    val file = source.map(_.file).getOrElse("")
    val line = source.map(_.line).getOrElse(0)
    (file, line, kind(target), target.symbol.fullName)

  private def emitTargetList(targets: List[DocTarget], includePrivate: Boolean, out: PrintWriter, indent: String)(using Definitions): Unit =
    var first = true
    for target <- targets do
      if !first then out.println(",")
      first = false
      emitTarget(target, includePrivate, out, indent)

  private def emitTarget(target: DocTarget, includePrivate: Boolean, out: PrintWriter, indent: String)(using Definitions): Unit =
    val sym = target.symbol
    val members = memberTargets(target, includePrivate)
    val views = target match
      case DocTarget.Definition(cd: ClassDef) => cd.views.map(_.tpe.show)
      case _ => Nil

    val next = indent + "  "
    out.println(indent + "{")
    emitField("name", JsonUtil.string(sym.fullName), out, next)
    emitField("kind", JsonUtil.string(kind(target)), out, next)
    emitField("signature", JsonUtil.string(signature(target)), out, next)
    emitField("source", sourceJson(sourceLoc(sym)), out, next)
    emitField("visibility", JsonUtil.string(visibility(sym)), out, next)
    emitField("flags", stringArray(Flags.flagStrings(sym.flags)), out, next)
    emitField("annotations", annotationsJson(sym), out, next)
    emitField("doc", docs(sym).map(JsonUtil.string).getOrElse("null"), out, next, comma = views.nonEmpty || members.nonEmpty)

    if views.nonEmpty then
      emitField("views", stringArray(views), out, next, comma = members.nonEmpty)

    if members.nonEmpty then
      out.println(next + JsonUtil.string("members") + ": [")
      emitTargetList(members, includePrivate, out, next + "  ")
      out.println()
      out.println(next + "]")

    out.print(indent + "}")

  private def memberTargets(target: DocTarget, includePrivate: Boolean)(using Definitions): List[DocTarget] =
    val members =
      target match
        case DocTarget.Namespace(_, units) =>
          units.flatMap(unit => unit.defs.flatMap(targetForDef(_, includePrivate)))

        case DocTarget.Definition(sec: Section) =>
          sec.defs.flatMap(targetForDef(_, includePrivate))

        case DocTarget.Definition(cd: ClassDef) =>
          cd.vals.flatMap(targetForField(_, includePrivate)) ++
            cd.funs.flatMap(targetForDef(_, includePrivate))

        case DocTarget.Definition(id: InterfaceDef) =>
          id.methods.flatMap(targetForDef(_, includePrivate))

        case _ =>
          Nil

    sortTargets(members)

  private def kind(target: DocTarget): String =
    target match
      case DocTarget.Namespace(_, _) =>
        "namespace"

      case DocTarget.Field(_) =>
        "field"

      case DocTarget.Definition(_: ParamDef) =>
        "param"

      case DocTarget.Definition(_: TypeDef) =>
        "type"

      case DocTarget.Definition(fd: FunDef) =>
        if fd.symbol.is(Flags.Constructor) then "constructor" else "def"

      case DocTarget.Definition(_: PatDef) =>
        "pattern"

      case DocTarget.Definition(_: ClassDef | _: InterfaceDef) =>
        "class"

      case DocTarget.Definition(_: Section) =>
        "section"

  private def signature(target: DocTarget)(using Definitions): String =
    target match
      case DocTarget.Namespace(sym, _) =>
        "namespace " + sym.fullName

      case DocTarget.Field(field) =>
        fieldSignature(field)

      case DocTarget.Definition(pd: ParamDef) =>
        "param " + pd.name + ": " + pd.tpt.tpe.show

      case DocTarget.Definition(td: TypeDef) =>
        typeSignature(td)

      case DocTarget.Definition(fd: FunDef) =>
        funSignature(fd)

      case DocTarget.Definition(pd: PatDef) =>
        patternSignature(pd)

      case DocTarget.Definition(cd: ClassDef) =>
        classSignature(cd)

      case DocTarget.Definition(id: InterfaceDef) =>
        interfaceSignature(id)

      case DocTarget.Definition(sec: Section) =>
        "section " + sec.symbol.name

  private def sourceLoc(sym: Symbol): Option[SourceLoc] =
    if sym.sourcePos == null then None
    else Some(SourceLoc(sym.source.file, sym.sourcePos.startLine + 1))

  private case class SourceLoc(file: String, line: Int)

  private def sourceJson(source: Option[SourceLoc]): String =
    source match
      case Some(SourceLoc(file, line)) =>
        s"""{ "file": ${JsonUtil.string(file)}, "line": $line }"""
      case None =>
        "null"

  private def visibility(sym: Symbol): String =
    sym.visibility match
      case Visibility.Default => "public"
      case Visibility.Private(within) =>
        if within == sym.owner then "private"
        else s"private[${within.fullName}]"

  private def docs(sym: Symbol)(using Definitions): Option[String] =
    val lines = summon[Definitions].index.docComment(sym)
    if lines.isEmpty then None else Some(lines.mkString("\n"))

  private def annotationsJson(sym: Symbol)(using Definitions): String =
    sym.annotations.map { annot =>
      s"""{ "name": ${JsonUtil.string(annot.symbol.fullName)}, "args": ${valuesJson(annot.args)} }"""
    }.mkString("[", ", ", "]")

  private def valuesJson(values: List[Constant]): String =
    values.map(constantJson).mkString("[", ", ", "]")

  private def constantJson(value: Constant): String =
    value match
      case Constant.String(v) => JsonUtil.string(v)
      case Constant.Int(v)    => v.toString
      case Constant.Float(v)  => v.toString
      case Constant.Bool(v)   => v.toString

  private def stringArray(values: List[String]): String =
    values.map(JsonUtil.string).mkString("[", ", ", "]")

  private def emitField(name: String, value: String, out: PrintWriter, indent: String, comma: Boolean = true): Unit =
    out.print(indent)
    out.print(JsonUtil.string(name))
    out.print(": ")
    out.print(value)
    if comma then out.print(",")
    out.println()

  private def typeParams(params: List[Symbol]): String =
    if params.isEmpty then "" else params.map(_.name).mkString("[", ", ", "]")

  private def defaultValue(value: DefaultValue): String =
    value match
      case DefaultValue.Lit(const) => const match
        case Constant.Bool(v)   => v.toString
        case Constant.Int(v)    => v.toString
        case Constant.Float(v)  => v.toString
        case Constant.String(v) => JsonUtil.string(v)
      case DefaultValue.Ref(sym) => sym.name

  private def paramSignature(sym: Symbol, default: Option[DefaultValue] = None)(using Definitions): String =
    val base = sym.name + ": " + sym.tpe.show
    default match
      case Some(value) => base + " = " + defaultValue(value)
      case None => base

  private def autoCandidateSignature(candidate: AutoCandidate)(using Definitions): String =
    candidate.show

  private def autoSignature(sym: Symbol, candidates: List[AutoCandidate])(using Definitions): String =
    val candidateText =
      if candidates.isEmpty then ""
      else " with [" + candidates.map(autoCandidateSignature).mkString(", ") + "]"
    paramSignature(sym) + candidateText

  private def receivesSignature(procType: ProcType)(using Definitions): String =
    def showEffects(effects: List[Symbol]): String =
      if effects.isEmpty then " receives none"
      else " receives " + effects.map(_.name).mkString(", ")

    procType.receivesInfo match
      case sym: Symbol =>
        summon[Definitions].index.effectEngine.getKnownEffects(sym) match
          case Some(effects) => showEffects(effects)
          case None => ""
      case effects: List[Symbol] =>
        showEffects(effects)

  private def procSignature(fd: FunDef)(using Definitions): String =
    val procType = fd.symbol.tpe.asProcType
    val tparamText =
      if fd.tparams.isEmpty then ""
      else fd.tparams.map(_.name).mkString("[", ", ", "]")

    val preParamText =
      if procType.preParamCount == 0 then ""
      else fd.params.take(procType.preParamCount).map(paramSignature(_)).mkString("(", ", ", ")")

    val postParams = fd.params.drop(procType.preParamCount)
    val defaultCount = procType.defaults.size
    val postParamText =
      val split = postParams.size - defaultCount
      val withoutDefaults = postParams.take(split).map(paramSignature(_))
      val withDefaults = postParams.drop(split).zip(procType.defaults).map:
        case (param, default) => paramSignature(param, Some(default))
      (withoutDefaults ++ withDefaults).mkString("(", ", ", ")")

    val autoText =
      if fd.autos.isEmpty then ""
      else fd.autos.zip(fd.candidates).map(autoSignature).mkString("(auto ", ", ", ")")

    tparamText + preParamText + postParamText + autoText + ": " + fd.resultType.tpe.show + receivesSignature(procType)

  private def funSignature(fd: FunDef)(using Definitions): String =
    val sym = fd.symbol
    if sym.is(Flags.Annotation) then
      "annotation " + sym.name + procSignature(fd).stripSuffix(": void receives none").stripSuffix(": void")
    else if sym.is(Flags.Constructor) then
      "constructor" + procSignature(fd)
    else
      "def " + sym.name + procSignature(fd)

  private def patternSignature(pd: PatDef)(using Definitions): String =
    "pattern " + pd.symbol.name + pd.symbol.tpe.asProcType.show

  private def typeSignature(td: TypeDef)(using Definitions): String =
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

  private def classSignature(cd: ClassDef): String =
    val sym = cd.symbol
    val prefix =
      if sym.is(Flags.Object) then "object"
      else if sym.isInterface then "interface"
      else "class"
    prefix + " " + sym.name + typeParams(cd.tparams)

  private def interfaceSignature(id: InterfaceDef): String =
    "interface " + id.symbol.name + typeParams(id.tparams)

  private def fieldSignature(field: FieldDecl)(using Definitions): String =
    val sym = field.symbol
    val prefix = if sym.isMutable then "var " else "val "
    prefix + sym.name + ": " + field.tpt.tpe.show

package query

import reporting.Reporter
import sast.*
import sast.Symbols.*
import sast.Trees.*
import sast.Types.*
import sast.Denotations.*
import sast.Flags
import ast.Positions.Span

import java.io.PrintWriter
import java.nio.file.Paths
import scala.collection.mutable

object Query:
  private val outputFields =
    List("name", "kind", "signature", "source", "visibility", "flags", "annotations", "doc")

  private val availableFields = outputFields.mkString(",")

  def parseFields(rawFields: String)(using Reporter): Set[String] =
    val fields = rawFields.split(",").map(_.trim).filter(_.nonEmpty).toSet
    if fields.isEmpty then
      Reporter.error(s"Option --fields requires at least one field. Available fields: $availableFields")

    for field <- fields.diff(outputFields.toSet).toList.sorted do
      Reporter.error(s"Unknown query field: $field. Available fields: $availableFields")

    fields

  case class Filter(files: List[String], symbols: List[Symbol]):
    def isEmpty: Boolean =
      files.isEmpty && symbols.isEmpty

    def selectsFile(sourceFile: String): Boolean =
      files.exists(file => matchesFile(sourceFile, file))

  def resolveSymbol(nameTable: NameTable, parts: List[String])(using Definitions): List[Symbol] =
    parts match
      case Nil => Nil

      case name :: Nil =>
        nameTable.resolve(name)

      case name :: rest =>
        val containerMatches =
          nameTable.resolveContainer(name).toList.flatMap: sym =>
            resolveSymbol(sym.nameTable, rest)

        val memberMatches =
          rest match
            case memberName :: Nil =>
              nameTable.resolveType(name).toList.flatMap: sym =>
                if sym.isOneOf(Flags.Class | Flags.Interface) then
                  sym.classInfo.getMemberSymbol(memberName).toList
                else Nil

            case _ => Nil

        (containerMatches ++ memberMatches).distinct

  def filterUnits(sourceUnits: List[FileUnit], libraryUnits: List[FileUnit], filter: Filter)(using Reporter): List[FileUnit] =
    if filter.isEmpty then
      sourceUnits
    else
      val allUnits = sourceUnits ++ libraryUnits
      val fileUnits = filter.files.flatMap: file =>
        val matches = allUnits.filter(unit => matchesFile(unit.source.file, file))
        if matches.isEmpty then Reporter.error(s"No documentation entries match file selector `file:$file`")
        matches

      val symbolUnits =
        allUnits.filter: unit =>
          filter.symbols.exists: sym =>
            unit.owner.containedIn(sym) || sym.containedIn(unit.owner)

      distinctUnits(fileUnits ++ symbolUnits)

  def reportNoMatches(rawQuery: String)(using Reporter): Unit =
    rawQuery.split(",").map(_.trim).filter(_.nonEmpty).foreach: selector =>
      if selector.startsWith("file:") then
        Reporter.error(s"No documentation entries match file selector `$selector`")
      else
        Reporter.error(s"No documentation entries match symbol selector `${symbolName(selector)}`")

  def parse(rawQuery: String, nameTable: NameTable)(using Reporter, Definitions): Filter =
    val files = mutable.ArrayBuffer.empty[String]
    val symbols = mutable.ArrayBuffer.empty[Symbol]

    rawQuery.split(",").map(_.trim).filter(_.nonEmpty).foreach: selector =>
      if selector.startsWith("file:") then
        files += selector.stripPrefix("file:")
      else
        val name = symbolName(selector)
        val resolved = resolveSymbol(nameTable, name.split('.').toList)
        if resolved.isEmpty then Reporter.error(s"No documentation entries match symbol selector `$name`")
        else symbols ++= resolved

    Filter(files.toList, symbols.toList)

  private def symbolName(selector: String): String =
    if selector.endsWith(".*") then selector.stripSuffix(".*")
    else selector

  private def matchesFile(sourceFile: String, rawFile: String): Boolean =
    val rawNormalized = normalize(rawFile)
    val sourceNormalized = normalize(sourceFile)
    rawFile == sourceFile ||
    rawNormalized == sourceNormalized ||
    (Paths.get(rawFile).getParent == null &&
      Paths.get(sourceFile).getFileName == Paths.get(rawFile).getFileName) ||
    absolute(rawFile) == absolute(sourceFile)

  private def normalize(path: String): String =
    Paths.get(path).normalize().toString

  private def absolute(path: String): String =
    Paths.get(path).toAbsolutePath.normalize().toString

  private def distinctUnits(units: List[FileUnit]): List[FileUnit] =
    val seen = mutable.HashSet.empty[FileUnit]
    units.filter: unit =>
      if seen.contains(unit) then false
      else
        seen += unit
        true

  def emitJson(
    units: List[FileUnit],
    filter: Filter,
    fields: Set[String],
    includePrivate: Boolean,
    out: PrintWriter,
  )(using Reporter, Definitions): Unit =
    val trimmedUnits = trimUnits(units, filter, includePrivate)
    val sortedTargets = sortRoots(jsonRoots(trimmedUnits, filter))
    out.println("[")
    emitRootList(sortedTargets, fields, out, "  ")
    if sortedTargets.nonEmpty then out.println()
    out.println("]")

  private def trimUnits(units: List[FileUnit], filter: Filter, includePrivate: Boolean)(using Reporter, Definitions): List[FileUnit] =
    if filter.isEmpty then
      return units.flatMap: unit =>
        val defs = visibleDefs(unit.defs, includePrivate)
        if defs.isEmpty then None else Some(unit.copy(defs = defs))

    val requested = filter.symbols.toSet
    val matched = mutable.HashSet.empty[Symbol]
    val trimmed = units.flatMap: unit =>
      if emitsNamespace(unit, filter) then
        if requested.contains(unit.owner) then matched += unit.owner
        val defs = visibleDefs(unit.defs, includePrivate)
        markMatchesInDefs(defs, requested, includePrivate, matched)
        Some(unit.copy(defs = defs))
      else
        val defs = trimDefs(unit.defs, requested, includePrivate, matched)
        if defs.isEmpty then None else Some(unit.copy(defs = defs))

    for sym <- filter.symbols do
      val matchedByAncestor =
        sym.ownersIterator.exists(matched.contains)
      if !matched.contains(sym) && !matchedByAncestor then
        Reporter.error(s"No documentation entries match symbol selector `${sym.fullName}`")

    distinctUnits(trimmed)

  private def trimDefs(
    defs: List[Def],
    requested: Set[Symbol],
    includePrivate: Boolean,
    matched: mutable.Set[Symbol],
  )(using Definitions): List[Def] =
    defs.flatMap: defn =>
      docSymbolForDef(defn, includePrivate) match
        case Some(sym) if requested.contains(sym) =>
          matched += sym
          visibleDef(defn, includePrivate).toList

        case Some(_) =>
          defn match
            case cd: ClassDef =>
              trimClass(cd, requested, includePrivate, matched)

            case id: InterfaceDef =>
              trimInterface(id, requested, includePrivate, matched)

            case sec: Section =>
              trimSection(sec, requested, includePrivate, matched)

            case _ =>
              Nil

        case None =>
          Nil

  private def trimClass(
    cd: ClassDef,
    requested: Set[Symbol],
    includePrivate: Boolean,
    matched: mutable.Set[Symbol],
  )(using Definitions): List[Def] =
    val fields = cd.vals.filter: field =>
      val keep = requested.contains(field.symbol) && visible(field.symbol, includePrivate)
      if keep then matched += field.symbol
      keep

    val methods = cd.funs.filter: fun =>
      docSymbolForDef(fun, includePrivate) match
        case Some(sym) if requested.contains(sym) =>
          matched += sym
          true
        case _ =>
          false

    if fields.isEmpty && methods.isEmpty then
      Nil
    else if fields.isEmpty && methods.size == 1 then
      methods
    else
      List(ClassDef(cd.symbol, cd.self, cd.tparams, fields, methods, cd.views)(cd.annots, cd.span))

  private def trimInterface(
    id: InterfaceDef,
    requested: Set[Symbol],
    includePrivate: Boolean,
    matched: mutable.Set[Symbol],
  )(using Definitions): List[Def] =
    val methods = id.methods.filter: fun =>
      docSymbolForDef(fun, includePrivate) match
        case Some(sym) if requested.contains(sym) =>
          matched += sym
          true
        case _ =>
          false

    if methods.isEmpty then
      Nil
    else if methods.size == 1 then
      methods
    else
      List(InterfaceDef(id.symbol, id.self, id.tparams, methods)(id.annots, id.span))

  private def trimSection(
    sec: Section,
    requested: Set[Symbol],
    includePrivate: Boolean,
    matched: mutable.Set[Symbol],
  )(using Definitions): List[Def] =
    val defs = trimDefs(sec.defs, requested, includePrivate, matched)
    if defs.isEmpty then
      Nil
    else if defs.size == 1 then
      defs
    else
      List(Section(sec.symbol, defs)(sec.annots, sec.span))

  private def visibleDefs(defs: List[Def], includePrivate: Boolean)(using Definitions): List[Def] =
    defs.flatMap(visibleDef(_, includePrivate))

  private def visibleDef(defn: Def, includePrivate: Boolean)(using Definitions): Option[Def] =
    docSymbolForDef(defn, includePrivate).map: _ =>
      defn match
        case cd: ClassDef =>
          val fields = cd.vals.filter(field => visible(field.symbol, includePrivate))
          val methods = visibleFunDefs(cd.funs, includePrivate)
          ClassDef(cd.symbol, cd.self, cd.tparams, fields, methods, cd.views)(cd.annots, cd.span)

        case id: InterfaceDef =>
          val methods = visibleFunDefs(id.methods, includePrivate)
          InterfaceDef(id.symbol, id.self, id.tparams, methods)(id.annots, id.span)

        case sec: Section =>
          Section(sec.symbol, visibleDefs(sec.defs, includePrivate))(sec.annots, sec.span)

        case _ =>
          defn

  private def visibleFunDefs(defs: List[FunDef], includePrivate: Boolean)(using Definitions): List[FunDef] =
    defs.filter(fun => docSymbolForDef(fun, includePrivate).isDefined)

  private def markMatchesInDefs(
    defs: List[Def],
    requested: Set[Symbol],
    includePrivate: Boolean,
    matched: mutable.Set[Symbol],
  )(using Definitions): Unit =
    defs.foreach: defn =>
      docSymbolForDef(defn, includePrivate).foreach: sym =>
        if requested.contains(sym) then matched += sym

      defn match
        case cd: ClassDef =>
          cd.vals.foreach: field =>
            if requested.contains(field.symbol) && visible(field.symbol, includePrivate) then
              matched += field.symbol
          markMatchesInDefs(cd.funs, requested, includePrivate, matched)

        case id: InterfaceDef =>
          markMatchesInDefs(id.methods, requested, includePrivate, matched)

        case sec: Section =>
          markMatchesInDefs(sec.defs, requested, includePrivate, matched)

        case _ =>

  private def docSymbolForDef(defn: Def, includePrivate: Boolean)(using Definitions): Option[Symbol] =
    if !visible(defn.symbol, includePrivate) then None
    else
      defn match
        case fd: FunDef if fd.symbol.is(Flags.Object) =>
          None

        case pd: PatDef if pd.resultType.tpe.isSingletonObjectType =>
          None

        case _ =>
          Some(defn.symbol)

  def visible(sym: Symbol, includePrivate: Boolean): Boolean =
    includePrivate || !sym.isPrivate

  private def emitsNamespace(unit: FileUnit, filter: Filter): Boolean =
    !filter.isEmpty && (
      filter.symbols.contains(unit.owner) ||
      filter.files.exists(file => matchesFile(unit.source.file, file))
    )

  private def jsonRoots(units: List[FileUnit], filter: Filter): List[FileUnit | Def] =
    if filter.isEmpty then
      units.flatMap(_.defs)
    else
      val namespaces = mutable.LinkedHashMap.empty[Symbol, mutable.ArrayBuffer[FileUnit]]
      val defs = mutable.ArrayBuffer.empty[Def]

      for unit <- units do
        if emitsNamespace(unit, filter) then
          namespaces.getOrElseUpdate(unit.owner, mutable.ArrayBuffer.empty) += unit
        else
          defs ++= unit.defs

      val namespaceRoots: List[FileUnit | Def] = namespaces.toList.map:
        case (owner, ownerUnits) =>
          val sorted = ownerUnits.toList.sortBy(_.source.file)
          FileUnit(owner, sorted.flatMap(_.imports).distinct, sorted.flatMap(_.defs), sorted.head.source)

      namespaceRoots ++ defs.toList.map(defn => defn: FileUnit | Def)

  private def sortRoots(roots: List[FileUnit | Def]): List[FileUnit | Def] =
    roots.sortBy:
      case unit: FileUnit => sortKey(unit.owner, "namespace")
      case defn: Def      => sortKey(defn.symbol, kind(defn))

  private def sortMembers(members: List[Def | FieldDecl]): List[Def | FieldDecl] =
    members.sortBy:
      case defn: Def        => sortKey(defn.symbol, kind(defn))
      case field: FieldDecl => sortKey(field.symbol, "field")

  private def sortKey(sym: Symbol, kind: String): (String, Int, String, String) =
    val source = sourceLoc(sym)
    val file = source.map(_.file).getOrElse("")
    val line = source.map(_.line).getOrElse(0)
    (file, line, kind, sym.fullName)

  private def emitRootList(
    roots: List[FileUnit | Def],
    fields: Set[String],
    out: PrintWriter,
    indent: String,
  )(using Definitions): Unit =
    var first = true
    for root <- roots do
      if !first then out.println(",")
      first = false
      root match
        case unit: FileUnit => emitNamespace(unit, fields, out, indent)
        case defn: Def      => emitDef(defn, fields, out, indent)

  private def emitMemberList(
    members: List[Def | FieldDecl],
    fields: Set[String],
    out: PrintWriter,
    indent: String,
  )(using Definitions): Unit =
    var first = true
    for member <- members do
      if !first then out.println(",")
      first = false
      member match
        case defn: Def        => emitDef(defn, fields, out, indent)
        case field: FieldDecl => emitFieldDecl(field, fields, out, indent)

  private def emitNamespace(unit: FileUnit, fields: Set[String], out: PrintWriter, indent: String)(using Definitions): Unit =
    val members = sortMembers(unit.defs.map(defn => defn: Def | FieldDecl))
    emitSymbol(unit.owner, "namespace", "namespace " + unit.owner.fullName, Nil, members, sourceLoc(unit.owner), fields, out, indent)

  private def emitDef(defn: Def, fields: Set[String], out: PrintWriter, indent: String)(using Definitions): Unit =
    val members = sortMembers(memberNodes(defn))
    val views = defn match
      case cd: ClassDef => cd.views.map(_.tpe.show)
      case _ => Nil
    emitSymbol(defn.symbol, kind(defn), signature(defn), views, members, sourceLoc(defn.symbol, defn.span), fields, out, indent)

  private def emitFieldDecl(field: FieldDecl, fields: Set[String], out: PrintWriter, indent: String)(using Definitions): Unit =
    emitSymbol(field.symbol, "field", fieldSignature(field), Nil, Nil, sourceLoc(field.symbol, field.span), fields, out, indent)

  private def emitSymbol(
    sym: Symbol,
    kind: String,
    signature: String,
    views: List[String],
    members: List[Def | FieldDecl],
    source: Option[SourceLoc],
    fields: Set[String],
    out: PrintWriter,
    indent: String,
  )(using Definitions): Unit =
    val next = indent + "  "
    out.println(indent + "{")
    val entries = mutable.ArrayBuffer.empty[(String, String)]
    if fields.contains("name") then entries += "name" -> JsonUtil.string(sym.fullName)
    if fields.contains("kind") then entries += "kind" -> JsonUtil.string(kind)
    if fields.contains("signature") then entries += "signature" -> JsonUtil.string(signature)
    if fields.contains("source") then entries += "source" -> sourceJson(source)
    if fields.contains("visibility") then entries += "visibility" -> JsonUtil.string(visibility(sym))
    if fields.contains("flags") then entries += "flags" -> stringArray(Flags.flagStrings(sym.flags))
    if fields.contains("annotations") then entries += "annotations" -> annotationsJson(sym)
    if fields.contains("doc") then entries += "doc" -> docs(sym).map(JsonUtil.string).getOrElse("null")
    if views.nonEmpty then entries += "views" -> stringArray(views)

    for ((name, value), index) <- entries.zipWithIndex do
      emitField(name, value, out, next, comma = index < entries.size - 1 || members.nonEmpty)

    if members.nonEmpty then
      out.println(next + JsonUtil.string("members") + ": [")
      emitMemberList(members, fields, out, next + "  ")
      out.println()
      out.println(next + "]")

    out.print(indent + "}")

  private def memberNodes(defn: Def): List[Def | FieldDecl] =
    defn match
      case sec: Section =>
        sec.defs.map(defn => defn: Def | FieldDecl)

      case cd: ClassDef =>
        cd.vals.map(field => field: Def | FieldDecl) ++
          cd.funs.map(fun => fun: Def | FieldDecl)

      case id: InterfaceDef =>
        id.methods.map(fun => fun: Def | FieldDecl)

      case _ =>
        Nil

  private def kind(defn: Def): String =
    defn match
      case _: ParamDef =>
        "param"

      case _: TypeDef =>
        "type"

      case fd: FunDef =>
        if fd.symbol.is(Flags.Constructor) then "constructor" else "def"

      case _: PatDef =>
        "pattern"

      case _: ClassDef | _: InterfaceDef =>
        "class"

      case _: Section =>
        "section"

  private def signature(defn: Def)(using Definitions): String =
    defn match
      case pd: ParamDef =>
        "param " + pd.name + ": " + pd.tpt.tpe.show

      case td: TypeDef =>
        typeSignature(td)

      case fd: FunDef =>
        funSignature(fd)

      case pd: PatDef =>
        patternSignature(pd)

      case cd: ClassDef =>
        classSignature(cd)

      case id: InterfaceDef =>
        interfaceSignature(id)

      case sec: Section =>
        "section " + sec.symbol.name

  private def sourceLoc(sym: Symbol): Option[SourceLoc] =
    if sym.sourcePos == null then None
    else Some(SourceLoc(sym.source.file, sym.sourcePos.startLine + 1, sym.sourcePos.endLine + 1))

  private def sourceLoc(sym: Symbol, span: Span): Option[SourceLoc] =
    if sym.sourcePos == null then None
    else
      val pos = span.toPos(using sym.source)
      Some(SourceLoc(sym.source.file, pos.startLine + 1, pos.endLine + 1))

  private case class SourceLoc(file: String, line: Int, end: Int)

  private def sourceJson(source: Option[SourceLoc]): String =
    source match
      case Some(SourceLoc(file, line, end)) =>
        s"""{ "file": ${JsonUtil.string(file)}, "line": $line, "end": $end }"""
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

  private def emitField(name: String, value: String, out: PrintWriter, indent: String, comma: Boolean): Unit =
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

package doc

import sast.Definitions
import sast.Symbols.*
import sast.Trees.*
import sast.Types.*
import sast.Flags

import scala.collection.mutable
import java.io.PrintWriter

object JsonEmitter:

  /** Emit meta.json - project metadata */
  def emitMeta(title: String, out: PrintWriter): Unit =
    val timestamp = java.time.Instant.now().toString
    out.println("{")
    out.println(s"""  "title": ${jsonString(title)},""")
    out.println(s"""  "generatedAt": "$timestamp"""")
    out.println("}")

  /** Emit nav.json - navigation tree with branch/leaf structure */
  def emitNav(namespaces: List[Namespace], out: PrintWriter)(using Definitions): Unit =
    out.println("{")
    out.println("""  "children": [""")

    var first = true
    for ns <- namespaces.sortBy(_.symbol.fullName) do
      if !first then out.println(",")
      first = false
      emitNavNode(ns, out, "    ")

    out.println()
    out.println("  ]")
    out.println("}")

  private def emitNavNode(ns: Namespace, out: PrintWriter, indent: String)(using Definitions): Unit =
    val sym = ns.symbol
    val name = sym.name
    val fullName = sym.fullName

    // Collect member names for the nav entry (deduplicated, with all kinds)
    val members = collectNavMembers(ns)

    out.print(s"""$indent{ "name": ${jsonString(name)}, "fullName": ${jsonString(fullName)}, "kind": "leaf"""")

    if members.nonEmpty then
      out.print(""", "members": [""")
      var first = true
      for (mname, mfull, kinds) <- members do
        if !first then out.print(", ")
        first = false
        val kindsJson = kinds.map(jsonString).mkString("[", ", ", "]")
        out.print(s"""{ "name": ${jsonString(mname)}, "fullName": ${jsonString(mfull)}, "kinds": $kindsJson }""")
      out.print("]")

    out.print(" }")

  private def collectNavMembers(ns: Namespace)(using defn: Definitions): List[(String, String, List[String])] =
    // Use a map to collect all kinds for each name
    val membersByName = mutable.LinkedHashMap[String, (String, String, List[String])]()

    def addMember(name: String, fullName: String, kind: String): Unit =
      membersByName.get(name) match
        case Some((_, _, kinds)) if !kinds.contains(kind) =>
          membersByName(name) = (name, fullName, kinds :+ kind)
        case None =>
          membersByName(name) = (name, fullName, List(kind))
        case _ => () // kind already present

    def addDef(d: Def): Unit =
      d match
        case cd: ClassDef if !cd.symbol.isPrivate =>
          val kind =
            if cd.symbol.is(Flags.Object) then "object"
            else if cd.symbol.isInterface then "interface"
            else "class"
          addMember(cd.symbol.name, cd.symbol.fullName, kind)

        case id: InterfaceDef if !id.symbol.isPrivate =>
          addMember(id.symbol.name, id.symbol.fullName, "interface")

        // Skip singleton accessor functions (they have Flags.Object)
        case fd: FunDef if !fd.symbol.isPrivate && !fd.symbol.isMethod && !fd.symbol.is(Flags.Object) =>
          addMember(fd.symbol.name, fd.symbol.fullName, "function")

        case pd: PatDef if !pd.symbol.isPrivate && !pd.resultType.tpe.isSingletonObjectType =>
          addMember(pd.symbol.name, pd.symbol.fullName, "pattern")

        case td: TypeDef if !td.symbol.isPrivate =>
          val kind = td.symbol.info match
            case _: UnionType => "union"
            case _ if td.symbol.is(Flags.Alias) => "alias"
            case _ => "abstract"
          addMember(td.symbol.name, td.symbol.fullName, kind)

        case sec: Section if !sec.symbol.isPrivate =>
          addMember(sec.symbol.name, sec.symbol.fullName, "section")

        case pdef: ParamDef if pdef.symbol.is(Flags.Context) && !pdef.symbol.isPrivate =>
          addMember(pdef.symbol.name, pdef.symbol.fullName, "context")

        case _ => ()

    ns.defs.foreach(addDef)
    membersByName.values.toList

  /** Emit search.json - flat list of all searchable symbols */
  def emitSearch(namespaces: List[Namespace], includePrivate: Boolean, out: PrintWriter)(using Definitions): Unit =
    out.println("[")

    var first = true

    def emitSymbol(sym: Symbol, kind: String, summary: Option[String]): Unit =
      if includePrivate || !sym.isPrivate then
        if !first then out.println(",")
        first = false
        val summaryJson = summary.map(s => s""", "summary": ${jsonString(s)}""").getOrElse("")
        out.print(s"""  { "name": ${jsonString(sym.name)}, "fullName": ${jsonString(sym.fullName)}, "kind": ${jsonString(kind)}$summaryJson }""")

    def processNamespace(ns: Namespace): Unit =
      val defn = summon[Definitions]

      def processDef(d: Def): Unit =
        d match
          case cd: ClassDef =>
            val kind =
              if cd.symbol.is(Flags.Object) then "object"
              else if cd.symbol.isInterface then "interface"
              else "class"
            val doc = defn.docComment(cd.symbol).headOption
            emitSymbol(cd.symbol, kind, doc)

            // Also add methods
            for meth <- cd.funs if includePrivate || !meth.symbol.isPrivate do
              val methodDoc = defn.docComment(meth.symbol).headOption
              emitSymbol(meth.symbol, "method", methodDoc)

          case id: InterfaceDef =>
            val doc = defn.docComment(id.symbol).headOption
            emitSymbol(id.symbol, "interface", doc)

            // Also add methods
            for meth <- id.methods if includePrivate || !meth.symbol.isPrivate do
              val methodDoc = defn.docComment(meth.symbol).headOption
              emitSymbol(meth.symbol, "method", methodDoc)

          // Skip singleton accessor functions (they have Flags.Object)
          case fd: FunDef if !fd.symbol.isMethod && !fd.symbol.is(Flags.Object) =>
            val doc = defn.docComment(fd.symbol).headOption
            emitSymbol(fd.symbol, "function", doc)

          case pd: PatDef if !pd.resultType.tpe.isSingletonObjectType =>
            val doc = defn.docComment(pd.symbol).headOption
            emitSymbol(pd.symbol, "pattern", doc)

          case td: TypeDef =>
            val kind = td.symbol.info match
              case _: UnionType => "union"
              case _ if td.symbol.is(Flags.Alias) => "alias"
              case _ => "abstract"
            val doc = defn.docComment(td.symbol).headOption
            emitSymbol(td.symbol, kind, doc)

          case sec: Section =>
            sec.defs.foreach(processDef)

          case pdef: ParamDef if pdef.symbol.is(Flags.Context) =>
            val doc = defn.docComment(pdef.symbol).headOption
            emitSymbol(pdef.symbol, "context", doc)

          case _ => ()

      ns.defs.foreach(processDef)

    for ns <- namespaces.sortBy(_.symbol.fullName) do
      processNamespace(ns)

    out.println()
    out.println("]")

  /** Collect all leaf namespace symbols from the namespace tree */
  def collectLeafNamespaces(namespaces: List[Namespace]): List[Symbol] =
    namespaces.map(_.symbol)

  /** Collect all sections from namespaces (recursively) */
  def collectAllSections(namespaces: List[Namespace]): List[Section] =
    val sections = mutable.ArrayBuffer[Section]()

    def collectFromDefs(defs: List[Def]): Unit =
      for d <- defs do
        d match
          case sec: Section =>
            sections += sec
            collectFromDefs(sec.defs)
          case _ => ()

    for ns <- namespaces do
      collectFromDefs(ns.defs)

    sections.toList

  /** Emit symbols/<fullName>.json for a section */
  def emitSection(sec: Section, includePrivate: Boolean, includeSource: Boolean, out: PrintWriter)(using Definitions): Unit =
    val sym = sec.symbol
    val defn = summon[Definitions]

    out.println("{")
    out.println(s"""  "name": ${jsonString(sym.name)},""")
    out.println(s"""  "fullName": ${jsonString(sym.fullName)},""")

    val docLines = defn.docComment(sym)
    if docLines.nonEmpty then
      out.println(s"""  "doc": ${jsonString(docLines.mkString("\n"))},""")
    else
      out.println("""  "doc": null,""")

    out.println(s"""  "source": { "file": ${jsonString(sym.source.file)}, "line": ${sym.sourcePos.startLine + 1} },""")

    // Collect types, functions, patterns, sections, contexts
    val types = mutable.ArrayBuffer[Def]()
    val functions = mutable.ArrayBuffer[FunDef]()
    val patterns = mutable.ArrayBuffer[PatDef]()
    val nestedSections = mutable.ArrayBuffer[Section]()
    val contexts = mutable.ArrayBuffer[ParamDef]()

    for d <- sec.defs do
      if includePrivate || !d.symbol.isPrivate then
        d match
          case cd: ClassDef => types += cd
          case id: InterfaceDef => types += id
          case td: TypeDef => types += td
          case fd: FunDef if !fd.symbol.isMethod && !fd.symbol.is(Flags.Object) => functions += fd
          case pd: PatDef if !pd.resultType.tpe.isSingletonObjectType  => patterns += pd
          case s: Section => nestedSections += s
          case pdef: ParamDef if pdef.symbol.is(Flags.Context) => contexts += pdef
          case _ => ()

    // Emit types
    out.println("""  "types": [""")
    emitTypes(types.toList, includePrivate, includeSource, out, "    ")
    out.println("""  ],""")

    // Emit objects (empty for now)
    out.println("""  "objects": [],""")

    // Emit functions
    out.println("""  "functions": [""")
    emitFunctions(functions.toList, includeSource, out, "    ")
    out.println("""  ],""")

    // Emit patterns
    out.println("""  "patterns": [""")
    emitPatterns(patterns.toList, includeSource, out, "    ")
    out.println("""  ],""")

    // Emit contexts
    out.println("""  "contexts": [""")
    emitContexts(contexts.toList, includeSource, out, "    ")
    out.println("""  ],""")

    // Emit section references (full content is in separate JSON files)
    out.println("""  "sections": [""")
    emitSectionRefs(nestedSections.toList, includePrivate, out, "    ")
    out.println("  ]")

    out.println("}")

  /** Emit symbols/<fullName>.json for a leaf namespace */
  def emitLeafNamespace(ns: Namespace, includePrivate: Boolean, includeSource: Boolean, out: PrintWriter)(using Definitions): Unit =
    val sym = ns.symbol
    val defn = summon[Definitions]

    out.println("{")
    out.println(s"""  "name": ${jsonString(sym.name)},""")
    out.println(s"""  "fullName": ${jsonString(sym.fullName)},""")

    val docLines = defn.docComment(sym)
    if docLines.nonEmpty then
      out.println(s"""  "doc": ${jsonString(docLines.mkString("\n"))},""")
    else
      out.println("""  "doc": null,""")

    out.println(s"""  "source": { "file": ${jsonString(ns.source)}, "line": ${sym.sourcePos.startLine + 1} },""")

    // Collect types, functions, patterns, sections, contexts
    val types = mutable.ArrayBuffer[Def]()
    val functions = mutable.ArrayBuffer[FunDef]()
    val patterns = mutable.ArrayBuffer[PatDef]()
    val sections = mutable.ArrayBuffer[Section]()
    val contexts = mutable.ArrayBuffer[ParamDef]()

    for d <- ns.defs do
      if includePrivate || !d.symbol.isPrivate then
        d match
          case cd: ClassDef => types += cd
          case id: InterfaceDef => types += id
          case td: TypeDef => types += td
          case fd: FunDef if !fd.symbol.isMethod && !fd.symbol.is(Flags.Object) => functions += fd
          case pd: PatDef if !pd.resultType.tpe.isSingletonObjectType => patterns += pd
          case sec: Section => sections += sec
          case pdef: ParamDef if pdef.symbol.is(Flags.Context) => contexts += pdef
          case _ => ()

    // Emit types
    out.println("""  "types": [""")
    emitTypes(types.toList, includePrivate, includeSource, out, "    ")
    out.println("""  ],""")

    // Emit objects (empty for now, as Jo doesn't have standalone objects like Scala)
    out.println("""  "objects": [],""")

    // Emit functions
    out.println("""  "functions": [""")
    emitFunctions(functions.toList, includeSource, out, "    ")
    out.println("""  ],""")

    // Emit patterns
    out.println("""  "patterns": [""")
    emitPatterns(patterns.toList, includeSource, out, "    ")
    out.println("""  ],""")

    // Emit contexts
    out.println("""  "contexts": [""")
    emitContexts(contexts.toList, includeSource, out, "    ")
    out.println("""  ],""")

    // Emit section references (full content is in separate JSON files)
    out.println("""  "sections": [""")
    emitSectionRefs(sections.toList, includePrivate, out, "    ")
    out.println("  ]")

    out.println("}")

  private def emitTypes(types: List[Def], includePrivate: Boolean, includeSource: Boolean, out: PrintWriter, indent: String)(using Definitions): Unit =
    var first = true

    for t <- types do
      if !first then out.println(",")
      first = false

      t match
        case cd: ClassDef =>
          emitClassDef(cd, includePrivate, includeSource, out, indent)

        case id: InterfaceDef =>
          emitInterfaceDef(id, includePrivate, out, indent)

        case td: TypeDef =>
          emitTypeDef(td, out, indent)

        case _ => () // Skip other defs that shouldn't be in types list

  private def emitClassDef(cd: ClassDef, includePrivate: Boolean, includeSource: Boolean, out: PrintWriter, indent: String)(using Definitions): Unit =
    val sym = cd.symbol
    val defn = summon[Definitions]

    val kind =
      if sym.is(Flags.Object) then "object"
      else if sym.isInterface then "interface"
      else "class"

    out.println(s"""$indent{""")
    out.println(s"""$indent  "name": ${jsonString(sym.name)},""")
    out.println(s"""$indent  "fullName": ${jsonString(sym.fullName)},""")
    out.println(s"""$indent  "kind": ${jsonString(kind)},""")

    // Type params
    if cd.tparams.nonEmpty then
      out.println(s"""$indent  "typeParams": [${cd.tparams.map(p => jsonString(p.name)).mkString(", ")}],""")
    else
      out.println(s"""$indent  "typeParams": [],""")

    // Doc
    val docLines = defn.docComment(sym)
    if docLines.nonEmpty then
      out.println(s"""$indent  "doc": ${jsonString(docLines.mkString("\n"))},""")
    else
      out.println(s"""$indent  "doc": null,""")

    // Source
    out.println(s"""$indent  "source": { "file": ${jsonString(sym.source.file)}, "line": ${sym.sourcePos.startLine + 1} },""")

    // Fields (for classes)
    if sym.isClass then
      out.println(s"""$indent  "fields": [""")
      var fieldFirst = true
      for field <- cd.vals if includePrivate || !field.isPrivate do
        if !fieldFirst then out.println(",")
        fieldFirst = false
        val visibility = if field.isPrivate then "private" else "public"
        out.print(s"""$indent    { "name": ${jsonString(field.name)}, "type": ${emitType(field.info)}, "visibility": "$visibility" }""")
      out.println()
      out.println(s"""$indent  ],""")

      // Constructor
      val ctorOpt = cd.funs.find(_.symbol.name == sast.Names.Constructor)
      ctorOpt match
        case Some(ctor) =>
          val ctorParams = ctor.params.map(p => s"""{ "name": ${jsonString(p.name)}, "type": ${emitType(p.info)} }""").mkString(", ")
          val ctorVis = if ctor.symbol.isPrivate then "private" else "public"
          out.println(s"""$indent  "constructor": { "params": [$ctorParams], "visibility": "$ctorVis" },""")
        case None =>
          out.println(s"""$indent  "constructor": null,""")

    // Methods
    out.println(s"""$indent  "methods": [""")
    var methFirst = true
    for meth <- cd.funs if meth.symbol.name != sast.Names.Constructor && (includePrivate || !meth.symbol.isPrivate) do
      if !methFirst then out.println(",")
      methFirst = false
      emitMethod(meth, out, indent + "    ")
    out.println()
    out.println(s"""$indent  ],""")

    // Views (for classes)
    if sym.isClass then
      out.println(s"""$indent  "views": [${cd.directViews.map(v => emitType(v.tpe)).mkString(", ")}]""")
    else
      out.println(s"""$indent  "views": []""")

    out.print(s"""$indent}""")

  private def emitInterfaceDef(id: InterfaceDef, includePrivate: Boolean, out: PrintWriter, indent: String)(using Definitions): Unit =
    val sym = id.symbol
    val defn = summon[Definitions]

    out.println(s"""$indent{""")
    out.println(s"""$indent  "name": ${jsonString(sym.name)},""")
    out.println(s"""$indent  "fullName": ${jsonString(sym.fullName)},""")
    out.println(s"""$indent  "kind": "interface",""")

    // Type params
    if id.tparams.nonEmpty then
      out.println(s"""$indent  "typeParams": [${id.tparams.map(p => jsonString(p.name)).mkString(", ")}],""")
    else
      out.println(s"""$indent  "typeParams": [],""")

    // Doc
    val docLines = defn.docComment(sym)
    if docLines.nonEmpty then
      out.println(s"""$indent  "doc": ${jsonString(docLines.mkString("\n"))},""")
    else
      out.println(s"""$indent  "doc": null,""")

    // Source
    out.println(s"""$indent  "source": { "file": ${jsonString(sym.source.file)}, "line": ${sym.sourcePos.startLine + 1} },""")

    // Methods
    out.println(s"""$indent  "methods": [""")
    var methFirst = true
    for meth <- id.methods if includePrivate || !meth.symbol.isPrivate do
      if !methFirst then out.println(",")
      methFirst = false
      emitMethod(meth, out, indent + "    ")
    out.println()
    out.println(s"""$indent  ],""")

    out.println(s"""$indent  "views": []""")
    out.print(s"""$indent}""")

  private def emitTypeDef(td: TypeDef, out: PrintWriter, indent: String)(using Definitions): Unit =
    val sym = td.symbol
    val defn = summon[Definitions]

    val info = sym.info

    // Determine kind and extra fields based on the underlying type
    val (kind, extras) = info match
      case unionType: UnionType =>
        val cases = unionType.classes.map { cls =>
          val clsInfo = cls.classInfo
          val fields = clsInfo.fields.map(f => s"""{ "name": ${jsonString(f.name)}, "type": ${emitType(f.info)} }""")
          s"""{ "name": ${jsonString(cls.name)}, "fields": [${fields.mkString(", ")}] }"""
        }
        ("union", s""", "cases": [${cases.mkString(", ")}]""")

      case TypeLambda(tparams, body, _) =>
        body match
          case _: UnionType =>
            val bodyUnion = body.asUnionType
            val cases = bodyUnion.classes.map { cls =>
              val clsInfo = cls.classInfo
              val fields = clsInfo.fields.map(f => s"""{ "name": ${jsonString(f.name)}, "type": ${emitType(f.info)} }""")
              s"""{ "name": ${jsonString(cls.name)}, "fields": [${fields.mkString(", ")}] }"""
            }
            ("union", s""", "cases": [${cases.mkString(", ")}]""")

          case StaticRef(_) if sym.is(Flags.Alias) =>
            ("alias", s""", "aliasOf": ${emitType(body)}""")

          case _ =>
            ("abstract", "")

      case StaticRef(_) if sym.is(Flags.Alias) =>
        ("alias", s""", "aliasOf": ${emitType(info)}""")

      case _ =>
        ("abstract", "")

    out.println(s"""$indent{""")
    out.println(s"""$indent  "name": ${jsonString(sym.name)},""")
    out.println(s"""$indent  "fullName": ${jsonString(sym.fullName)},""")
    out.println(s"""$indent  "kind": "$kind",""")

    // Type params
    val tparams = info match
      case TypeLambda(params, _, _) => params.map(_.name)
      case _ => Nil
    out.println(s"""$indent  "typeParams": [${tparams.map(jsonString).mkString(", ")}],""")

    // Doc
    val docLines = defn.docComment(sym)
    if docLines.nonEmpty then
      out.println(s"""$indent  "doc": ${jsonString(docLines.mkString("\n"))}$extras,""")
    else
      out.println(s"""$indent  "doc": null$extras,""")

    // Source
    out.println(s"""$indent  "source": { "file": ${jsonString(sym.source.file)}, "line": ${sym.sourcePos.startLine + 1} }""")

    out.print(s"""$indent}""")

  private def emitMethod(meth: FunDef, out: PrintWriter, indent: String)(using Definitions): Unit =
    val sym = meth.symbol
    val defn = summon[Definitions]

    out.println(s"""$indent{""")
    out.println(s"""$indent  "name": ${jsonString(sym.name)},""")

    // Type params
    if meth.tparams.nonEmpty then
      out.println(s"""$indent  "typeParams": [${meth.tparams.map(p => jsonString(p.name)).mkString(", ")}],""")
    else
      out.println(s"""$indent  "typeParams": [],""")

    // Params
    val params = meth.params.map { p =>
      val modifier = if p.is(Flags.Context) then """, "modifier": "auto"""" else ""
      s"""{ "name": ${jsonString(p.name)}, "type": ${emitType(p.info)}$modifier }"""
    }
    out.println(s"""$indent  "params": [${params.mkString(", ")}],""")

    // Return type
    out.println(s"""$indent  "returnType": ${emitType(meth.resultType.tpe)},""")

    // Doc
    val docLines = defn.docComment(sym)
    if docLines.nonEmpty then
      out.println(s"""$indent  "doc": ${jsonString(docLines.mkString("\n"))}""")
    else
      out.println(s"""$indent  "doc": null""")

    out.print(s"""$indent}""")

  private def emitFunctions(functions: List[FunDef], includeSource: Boolean, out: PrintWriter, indent: String)(using Definitions): Unit =
    val defn = summon[Definitions]
    var first = true

    for fd <- functions do
      if !first then out.println(",")
      first = false

      val sym = fd.symbol

      out.println(s"""$indent{""")
      out.println(s"""$indent  "name": ${jsonString(sym.name)},""")
      out.println(s"""$indent  "fullName": ${jsonString(sym.fullName)},""")

      // Type params
      if fd.tparams.nonEmpty then
        out.println(s"""$indent  "typeParams": [${fd.tparams.map(p => jsonString(p.name)).mkString(", ")}],""")
      else
        out.println(s"""$indent  "typeParams": [],""")

      // Params (including autos)
      val allParams = fd.params.map { p =>
        val position = if fd.symbol.info.asProcType.preParamCount > 0 && fd.params.indexOf(p) < fd.symbol.info.asProcType.preParamCount then
          """, "position": "prefix""""
        else ""
        s"""{ "name": ${jsonString(p.name)}, "type": ${emitType(p.info)}$position }"""
      } ++ fd.autos.map { p =>
        s"""{ "name": ${jsonString(p.name)}, "type": ${emitType(p.info)}, "modifier": "auto" }"""
      }
      out.println(s"""$indent  "params": [${allParams.mkString(", ")}],""")

      // Return type
      out.println(s"""$indent  "returnType": ${emitType(fd.resultType.tpe)},""")

      // Modifiers
      if sym.is(Flags.Defer) then
        out.println(s"""$indent  "modifier": "defer",""")
      else if sym.is(Flags.Alias) then
        out.println(s"""$indent  "modifier": "alias",""")

      // Doc
      val docLines = defn.docComment(sym)
      if docLines.nonEmpty then
        out.println(s"""$indent  "doc": ${jsonString(docLines.mkString("\n"))},""")
      else
        out.println(s"""$indent  "doc": null,""")

      // Source
      out.println(s"""$indent  "source": { "file": ${jsonString(sym.source.file)}, "line": ${sym.sourcePos.startLine + 1} }""")

      out.print(s"""$indent}""")

  private def emitPatterns(patterns: List[PatDef], includeSource: Boolean, out: PrintWriter, indent: String)(using Definitions): Unit =
    val defn = summon[Definitions]
    var first = true

    for pd <- patterns do
      if !first then out.println(",")
      first = false

      val sym = pd.symbol

      out.println(s"""$indent{""")
      out.println(s"""$indent  "name": ${jsonString(sym.name)},""")
      out.println(s"""$indent  "fullName": ${jsonString(sym.fullName)},""")

      // Type params
      if pd.tparams.nonEmpty then
        out.println(s"""$indent  "typeParams": [${pd.tparams.map(p => jsonString(p.name)).mkString(", ")}],""")
      else
        out.println(s"""$indent  "typeParams": [],""")

      // Params
      val procType = sym.info.asProcType
      val params = pd.params.zipWithIndex.map { case (p, i) =>
        val position = if i < procType.preParamCount then """, "position": "prefix"""" else ""
        s"""{ "name": ${jsonString(p.name)}, "type": ${emitType(p.info)}$position }"""
      }
      out.println(s"""$indent  "params": [${params.mkString(", ")}],""")

      // Return type
      out.println(s"""$indent  "returnType": ${emitType(pd.resultType.tpe)},""")

      // Doc
      val docLines = defn.docComment(sym)
      if docLines.nonEmpty then
        out.println(s"""$indent  "doc": ${jsonString(docLines.mkString("\n"))},""")
      else
        out.println(s"""$indent  "doc": null,""")

      // Source
      out.println(s"""$indent  "source": { "file": ${jsonString(sym.source.file)}, "line": ${sym.sourcePos.startLine + 1} }""")

      out.print(s"""$indent}""")

  private def emitContexts(contexts: List[ParamDef], includeSource: Boolean, out: PrintWriter, indent: String)(using Definitions): Unit =
    val defn = summon[Definitions]
    var first = true

    for pdef <- contexts do
      if !first then out.println(",")
      first = false

      val sym = pdef.symbol

      out.println(s"""$indent{""")
      out.println(s"""$indent  "name": ${jsonString(sym.name)},""")
      out.println(s"""$indent  "fullName": ${jsonString(sym.fullName)},""")
      out.println(s"""$indent  "type": ${emitType(pdef.tpt.tpe)},""")

      // Doc
      val docLines = defn.docComment(sym)
      if docLines.nonEmpty then
        out.println(s"""$indent  "doc": ${jsonString(docLines.mkString("\n"))},""")
      else
        out.println(s"""$indent  "doc": null,""")

      // Source
      out.println(s"""$indent  "source": { "file": ${jsonString(sym.source.file)}, "line": ${sym.sourcePos.startLine + 1} }""")

      out.print(s"""$indent}""")

  /** Emit section references only (full content is in separate JSON files) */
  private def emitSectionRefs(sections: List[Section], includePrivate: Boolean, out: PrintWriter, indent: String)(using Definitions): Unit =
    var first = true

    for sec <- sections do
      if includePrivate || !sec.symbol.isPrivate then
        if !first then out.println(",")
        first = false

        val sym = sec.symbol
        out.print(s"""$indent{ "name": ${jsonString(sym.name)}, "fullName": ${jsonString(sym.fullName)} }""")

  /** Emit a type as structured JSON */
  private def emitType(tp: Type)(using Definitions): String =
    tp match
      case VoidType => """{ "kind": "ref", "name": "Unit" }"""
      case AnyType => """{ "kind": "ref", "name": "Any" }"""
      case BottomType => """{ "kind": "ref", "name": "Bottom" }"""
      case ErrorType => """{ "kind": "ref", "name": "Error" }"""

      case StaticRef(sym) =>
        s"""{ "kind": "ref", "name": ${jsonString(sym.fullName)} }"""

      case MemberRef(_, sym) =>
        s"""{ "kind": "ref", "name": ${jsonString(sym.fullName)} }"""

      case AppliedType(tctor, targs) =>
        val argsJson = targs.map(emitType).mkString(", ")
        s"""{ "kind": "applied", "name": ${jsonString(tctor.fullName)}, "args": [$argsJson] }"""

      case RecordType(fields) =>
        val elems = fields.map(f => emitType(f.info)).mkString(", ")
        s"""{ "kind": "tuple", "elements": [$elems] }"""

      case LambdaType(params, result, _) =>
        val paramJson = params.map(emitType).mkString(", ")
        s"""{ "kind": "fun", "params": [$paramJson], "result": ${emitType(result)} }"""

      case ProcType(tparams, params, autos, _, result, _, _) =>
        // For display, just show as function type
        val allParams = params.map(_.info) ++ autos.map(_.info)
        val paramJson = allParams.map(emitType).mkString(", ")
        s"""{ "kind": "fun", "params": [$paramJson], "result": ${emitType(result)} }"""

      case TypeLambda(tparams, body, _) =>
        // For type lambdas, show the body with type params
        emitType(body)

      case UnionType(branches) =>
        // Show as the type itself
        val branchJson = branches.map(emitType).mkString(", ")
        s"""{ "kind": "union", "branches": [$branchJson] }"""

      case classInfo: ClassInfo =>
        val cls = classInfo.classSymbol
        val targs = classInfo.targs
        if targs.isEmpty then
          s"""{ "kind": "ref", "name": ${jsonString(cls.fullName)} }"""
        else
          val argsJson = targs.map(emitType).mkString(", ")
          s"""{ "kind": "applied", "name": ${jsonString(cls.fullName)}, "args": [$argsJson] }"""

      case ConstantType(const) =>
        const match
          case sast.Constant.Int(n) => s"""{ "kind": "literal", "value": $n }"""
          case sast.Constant.Bool(b) => s"""{ "kind": "literal", "value": $b }"""
          case sast.Constant.String(s) => s"""{ "kind": "literal", "value": ${jsonString(s)} }"""
          case sast.Constant.Float(f) => s"""{ "kind": "literal", "value": $f }"""

      case tvar: TypeVar =>
        if tvar.isInstantiated then emitType(tvar.instantiated)
        else s"""{ "kind": "ref", "name": "?" }"""

      case _ =>
        s"""{ "kind": "unknown", "repr": ${jsonString(tp.show)} }"""

  /** Escape and quote a string for JSON */
  private def jsonString(s: String): String =
    val sb = new StringBuilder("\"")
    for c <- s do
      c match
        case '"'  => sb.append("\\\"")
        case '\\' => sb.append("\\\\")
        case '\b' => sb.append("\\b")
        case '\f' => sb.append("\\f")
        case '\n' => sb.append("\\n")
        case '\r' => sb.append("\\r")
        case '\t' => sb.append("\\t")
        case c if c < 32 => sb.append("\\u%04x".format(c.toInt))
        case c => sb.append(c)
    sb.append("\"")
    sb.toString

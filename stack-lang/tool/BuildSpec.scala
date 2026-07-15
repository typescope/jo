package tool

import tool.toml.{TomlValue, TomlDoc, TomlError}
import TomlValue.*

case class ModuleId(value: String):
  override def toString: String = value

enum ModuleKind:
  case Lib
  case App

/** How the dep is linked into the build. */
enum DepLink:
  case Check   // visible to user code (type-check library)
  case Link    // hidden; resolves defer defs at link time

/** A source module dependency: built from source, never versioned.
 *  `path` names the directory holding the `jo.toml` that defines it; None means this project.
 */
case class ModuleDepSpec(id: ModuleId, path: Option[String] = None, link: DepLink = DepLink.Check)

/** A registry package dependency: resolved and locked by version. */
case class PackageDepSpec(name: String, constraint: VersionSpec, link: DepLink = DepLink.Check)

case class LinkSpec(from: String, to: String)

case class ModuleSpec(
  kind: ModuleKind,
  src: List[String],            // globs; required in jo.toml
  platform: Option[Platform],   // app backend or lib platform binding
  enableFfi: Boolean,           // whether this module may call py.*/rb.*
  depth: Option[Int],           // optional package-depth override for this module
  moduleDeps: List[ModuleDepSpec],
  packageDeps: List[PackageDepSpec],
  links: List[LinkSpec],        // defer-sym -> target-sym
  compileOptions: List[String] = Nil,  // extra flags passed to `jo compile`
  pkg: Option[PackageSpec] = None,
)

case class ModuleDef(id: ModuleId, spec: ModuleSpec)

/** [module.<id>.package] section — presence marks a publishable module. */
case class PackageSpec(
  name: String,
  version: String,
  description: Option[String] = None,
  authors: List[String] = Nil,
  homepage: Option[String] = None,
  license: Option[String] = None,
  keywords: List[String] = Nil,
)

case class DocSpec(
  title: Option[String] = None,
  readme: Option[String] = None,
  includePrivate: Boolean = false,
  includeSource: Boolean = false,
)

case class BuildSpec(
  jo: VersionSpec,              // compiler compatibility line, e.g. "1.0"
  pinning: Map[String, Version] = Map.empty, // root-only exact resolver overrides
  doc: Option[DocSpec],
  defaultModule: Option[ModuleId],
  modules: List[ModuleDef],
  commands: Map[String, String] = Map.empty, // [commands] -> `jo <name>` shell aliases
):
  def module(id: ModuleId): Option[ModuleSpec] =
    modules.find(_.id == id).map(_.spec)

  def defaultModuleId: ModuleId =
    defaultModule.getOrElse(modules.head.id)

object BuildSpec:
  private val reservedTopLevel = Set("name", "package", "main", "test")

  def decode(doc: TomlDoc): BuildSpec =
    reservedTopLevel.foreach: key =>
      if doc.contains(key) then
        throw TomlError(s"'$key' is no longer supported; use [module.<id>] build specs")

    val jo   = requireVersionSpec(doc, "jo")
    if doc.contains("depth") then
      throw TomlError("'depth' is no longer supported at the top level; set depth under [module.<id>]")

    val pinning = doc.get("pinning").map(v => decodePinning(asTbl(v, "pinning"))).getOrElse(Map.empty)
    val docSpec = doc.get("doc").map(v => decodeDoc(asTbl(v, "doc")))
    val default = doc.get("default").map(v => ModuleId(validateModuleId(asStr(v, "default"), "default")))
    val modules = decodeModules(doc.get("module"))
    val commands = doc.get("commands").map(v => decodeCommands(asTbl(v, "commands"))).getOrElse(Map.empty)

    if modules.isEmpty then
      throw TomlError("missing required [module.<id>] section")

    default.foreach: id =>
      if !modules.exists(_.id == id) then
        throw TomlError(s"default module '${id.value}' is not defined")

    BuildSpec(jo, pinning, docSpec, default, modules, commands)

  private def decodeModules(value: Option[TomlValue]): List[ModuleDef] =
    value match
      case None => Nil
      case Some(Tbl(modules)) =>
        modules.toList.map: (rawId, value) =>
          val id = ModuleId(validateModuleId(rawId, s"module.$rawId"))
          val tbl = asTbl(value, s"module.$rawId")
          ModuleDef(id, decodeModule(id, tbl))

      case Some(_) =>
        throw TomlError("'module' must be a table")

  private def decodeModule(id: ModuleId, tbl: Map[String, TomlValue]): ModuleSpec =
    val kindRaw = requireStr(tbl, "kind", s"[module.${id.value}]")
    val kind =
      kindRaw match
        case "lib" => ModuleKind.Lib
        case "app" => ModuleKind.App
        case _     => throw TomlError(s"invalid module.${id.value}.kind '$kindRaw', must be one of: lib, app")

    val src = tbl.get("src") match
      case Some(value) => asStrList(value, s"module.${id.value}.src")
      case None        => throw TomlError(s"missing required field 'module.${id.value}.src'")
    if tbl.contains("target") then
      throw TomlError(s"module.${id.value}.target is no longer supported; use module.${id.value}.platform")
    val platform = tbl.get("platform").map: v =>
      val s = asStr(v, s"module.${id.value}.platform")
      Platform.parse(s).getOrElse:
        throw TomlError(s"invalid module.${id.value}.platform '$s', must be one of: ${Platform.all.map(_.value).mkString(", ")}")
    val enableFfi = tbl.get("enable-ffi").map(asBool(_, s"module.${id.value}.enable-ffi")).getOrElse(false)
    val depth = tbl.get("depth").map(asInt(_, s"module.${id.value}.depth"))
    val moduleDeps = tbl.get("modules").map(v => decodeModuleDeps(v, id)).getOrElse(Nil)
    val packageDeps = tbl.get("packages").map(v => decodePackageDeps(v, id)).getOrElse(Nil)
    val links = tbl.get("links").map(v => decodeLinks(v, id)).getOrElse(Nil)
    val compileOptions = tbl.get("compile-options").map(asStrList(_, s"module.${id.value}.compile-options")).getOrElse(Nil)
    val pkg = tbl.get("package").map(v => decodePackage(asTbl(v, s"module.${id.value}.package"), id))

    if kind == ModuleKind.App then
      platform match
        case None =>
          throw TomlError(s"missing required field 'module.${id.value}.platform'")
        case Some(Platform.Pure) =>
          throw TomlError(s"module.${id.value}.platform must be one of: python, ruby")
        case _ =>
    if kind == ModuleKind.Lib && links.nonEmpty then
      throw TomlError(s"module.${id.value}.links is valid only for kind = \"app\"")
    if kind == ModuleKind.Lib && moduleDeps.exists(_.link == DepLink.Link) then
      throw TomlError(s"module.${id.value}.modules cannot use link = true when kind = \"lib\"")
    if kind == ModuleKind.Lib && packageDeps.exists(_.link == DepLink.Link) then
      throw TomlError(s"module.${id.value}.packages cannot use link = true when kind = \"lib\"")
    if enableFfi && platform.getOrElse(Platform.Pure) == Platform.Pure then
      throw TomlError(s"module.${id.value}.enable-ffi requires platform = \"python\" or platform = \"ruby\"")

    ModuleSpec(kind, src, platform, enableFfi, depth, moduleDeps, packageDeps, links, compileOptions, pkg)

  /** `modules = ["api", { id = "helpers", path = "../lib" }]`
   *  A bare string is shorthand for `{ id = "<string>" }`.
   */
  private def decodeModuleDeps(value: TomlValue, owner: ModuleId): List[ModuleDepSpec] =
    val deps = decodeDepArray(value, owner, "modules"): (item, ctx) =>
      item match
        case Str(id) =>
          ModuleDepSpec(ModuleId(validateModuleId(id, ctx)))

        case Tbl(fields) =>
          val id = fields.get("id") match
            case Some(v) => ModuleId(validateModuleId(asStr(v, s"$ctx.id"), s"$ctx.id"))
            case None    => throw TomlError(s"missing required field '$ctx.id'")
          ModuleDepSpec(id, fields.get("path").map(asStr(_, s"$ctx.path")), decodeDepLink(fields, ctx))

        case _ =>
          throw TomlError(s"$ctx must be a string or an inline table")

    // Identity is (path, id): the same id in two different projects is two dependencies.
    requireDistinct(deps, owner, "modules")(dep => s"${dep.path.getOrElse(".")}:${dep.id.value}")
    deps

  /** `packages = [{ name = "mustache", version = "1.0" }]` */
  private def decodePackageDeps(value: TomlValue, owner: ModuleId): List[PackageDepSpec] =
    val deps = decodeDepArray(value, owner, "packages"): (item, ctx) =>
      val fields = asTbl(item, ctx)
      val name = fields.get("name") match
        case Some(v) =>
          val n = asStr(v, s"$ctx.name")
          validatePackageName(n, s"$ctx.name")
          n
        case None => throw TomlError(s"missing required field '$ctx.name'")
      val constraint = fields.get("version") match
        case Some(v) => parseVersionSpec(asStr(v, s"$ctx.version"), s"$ctx.version")
        case None    => throw TomlError(s"missing required field '$ctx.version'")
      PackageDepSpec(name, constraint, decodeDepLink(fields, ctx))

    requireDistinct(deps, owner, "packages")(_.name)
    deps

  private def decodeDepArray[T](value: TomlValue, owner: ModuleId, field: String)(
    decode: (TomlValue, String) => T
  ): List[T] =
    value match
      case Arr(items) =>
        items.zipWithIndex.map: (item, idx) =>
          decode(item, s"module.${owner.value}.$field[$idx]")

      case _ =>
        throw TomlError(s"'module.${owner.value}.$field' must be an array")

  private def requireDistinct[T](deps: List[T], owner: ModuleId, field: String)(key: T => String): Unit =
    val seen = collection.mutable.Set.empty[String]
    deps.foreach: dep =>
      val k = key(dep)
      if !seen.add(k) then
        throw TomlError(s"duplicate dependency '$k' in module.${owner.value}.$field")

  private def decodeDepLink(fields: Map[String, TomlValue], ctx: String): DepLink =
    fields.get("link") match
      case Some(Bool(true))  => DepLink.Link
      case Some(Bool(false)) => DepLink.Check
      case Some(_)           => throw TomlError(s"$ctx.link must be a boolean")
      case None              => DepLink.Check

  private def decodeLinks(value: TomlValue, owner: ModuleId): List[LinkSpec] =
    val items = value match
      case Arr(items) => items
      case _          => throw TomlError(s"'module.${owner.value}.links' must be an array")

    val links = items.zipWithIndex.map: (item, idx) =>
      val ctx = s"module.${owner.value}.links[$idx]"
      val tbl = asTbl(item, ctx)
      val from = requireStr(tbl, "from", ctx)
      val to = requireStr(tbl, "to", ctx)
      LinkSpec(from, to)

    val seen = collection.mutable.Set.empty[String]
    links.foreach: link =>
      if seen.contains(link.from) then
        throw TomlError(s"duplicate link '${link.from}' in module.${owner.value}.links")
      seen += link.from

    links

  private def decodeCommands(tbl: Map[String, TomlValue]): Map[String, String] =
    tbl.map: (name, value) =>
      val cmd = asStr(value, s"[commands].$name")
      if cmd.trim.isEmpty then
        throw TomlError(s"[commands].$name must be a non-empty command string")
      name -> cmd
    .toMap

  private def decodePinning(tbl: Map[String, TomlValue]): Map[String, Version] =
    tbl.toSeq.sortBy(_._1).map: (name, value) =>
      val raw = asStr(value, s"[pinning].$name")
      val version =
        Version.parse(raw).getOrElse:
          throw TomlError(s"""invalid [pinning].$name '$raw', version must be MAJOR.MINOR.PATCH or MAJOR.MINOR.PATCH-modifier""")
      name -> version
    .toMap

  private def decodePackage(tbl: Map[String, TomlValue], owner: ModuleId): PackageSpec =
    val ctx = s"[module.${owner.value}.package]"
    val name        = requireStr(tbl, "name", ctx)
    val version     = requireStr(tbl, "version", ctx)
    val description = tbl.get("description").map(asStr(_, s"$ctx.description"))
    val authors     = tbl.get("authors").map(asStrList(_, s"$ctx.authors")).getOrElse(Nil)
    val homepage    = tbl.get("homepage").map(asStr(_, s"$ctx.homepage"))
    val license     = tbl.get("license").map(asStr(_, s"$ctx.license"))
    val keywords    = tbl.get("keywords").map(asStrList(_, s"$ctx.keywords")).getOrElse(Nil)
    if tbl.contains("runtime") then
      throw TomlError(s"$ctx.runtime is no longer supported; set platform on [module.${owner.value}]")
    if tbl.contains("platform") then
      throw TomlError(s"$ctx.platform is invalid; set platform on [module.${owner.value}]")

    validatePackageName(name, s"$ctx.name")
    validateVersion(version, s"$ctx.version")

    PackageSpec(name, version, description, authors, homepage, license, keywords)

  private def decodeDoc(tbl: Map[String, TomlValue]): DocSpec =
    val title = tbl.get("title").map(asStr(_, "[doc].title"))
    val readme = tbl.get("readme").map(asStr(_, "[doc].readme"))
    val includePrivate = tbl.get("include-private").map(asBool(_, "[doc].include-private")).getOrElse(false)
    val includeSource = tbl.get("include-source").map(asBool(_, "[doc].include-source")).getOrElse(false)
    DocSpec(title, readme, includePrivate, includeSource)

  private def validateModuleId(id: String, ctx: String): String =
    if id.isEmpty || !id.head.isLetter || !id.tail.forall(c => c.isLetterOrDigit || c == '-') then
      throw TomlError(s"invalid $ctx '$id', must start with a letter and contain only letters, digits, and hyphens")
    id

  private def validatePackageName(name: String, ctx: String): Unit =
    if name.isEmpty || !name.head.isLetter || !name.tail.forall(c => c.isLetterOrDigit || c == '-') then
      throw TomlError(s"invalid $ctx '$name', must start with a letter and contain only letters, digits, and hyphens")

  private def validateVersion(v: String, ctx: String): Unit =
    val parts = v.split("\\.")
    if parts.length != 3 || !parts.forall(_.forall(_.isDigit)) then
      throw TomlError(s"invalid $ctx '$v', must be MAJOR.MINOR.PATCH")

  private def parseVersionSpec(v: String, ctx: String): VersionSpec =
    VersionSpec.parse(v) match
      case Left(_)      => throw TomlError(s"invalid $ctx '$v', version must be MAJOR.MINOR (e.g. \"1.2\")")
      case Right(spec)  => spec

  // ---- Helpers -------------------------------------------------------------

  private def requireStr(doc: Map[String, TomlValue], key: String, ctx: String): String =
    val label = if ctx.nonEmpty then s"$ctx.$key" else key

    doc.get(key) match
      case Some(Str(s)) => s
      case Some(_)      => throw TomlError(s"'$label' must be a string")
      case None         => throw TomlError(s"missing required field '$label'")

  private def requireVersionSpec(doc: Map[String, TomlValue], key: String): VersionSpec =
    doc.get(key) match
      case Some(Str(s)) => parseVersionSpec(s, key)
      case Some(_)      => throw TomlError(s"'$key' must be a string")
      case None         => throw TomlError(s"missing required field '$key'")

  private def asStr(v: TomlValue, ctx: String): String = v match
    case Str(s) => s
    case _      => throw TomlError(s"'$ctx' must be a string")

  private def asInt(v: TomlValue, ctx: String): Int = v match
    case Integer(n) => n.toInt
    case _          => throw TomlError(s"'$ctx' must be an integer")

  private def asBool(v: TomlValue, ctx: String): Boolean = v match
    case Bool(b) => b
    case _       => throw TomlError(s"'$ctx' must be a boolean")

  private def asTbl(v: TomlValue, ctx: String): Map[String, TomlValue] = v match
    case Tbl(m) => m
    case _      => throw TomlError(s"'$ctx' must be a table")

  private def asStrList(v: TomlValue, ctx: String): List[String] = v match
    case Arr(items) => items.map:
      case Str(s) => s
      case _      => throw TomlError(s"'$ctx' must be an array of strings")
    case _ => throw TomlError(s"'$ctx' must be an array")

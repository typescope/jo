package tool

import tool.toml.{TomlValue, TomlDoc, TomlError}
import TomlValue.*

/** How the dep is linked into the build. */
enum DepLink:
  case Check   // visible to user code (type-check library)
  case Link    // hidden; resolves defer defs at link time

/** Where the dep comes from. */
enum DepSource:
  case Path(path: String, spec: Option[String] = None)
  case Registry(constraint: String)

case class DepSpec(source: DepSource, link: DepLink = DepLink.Check)

case class ModuleSpec(
  src: List[String],            // globs; empty = use default
  target: Option[String],       // backend: "python" | "js" | "ruby" | "native"
  dependencies: Map[String, DepSpec],
  links: Map[String, String],   // defer-sym → target-sym
)

/** [package] section — presence marks a lib build. */
case class PackageSpec(
  version: String,
  description: Option[String] = None,
  ffi: Option[String] = None,   // optional assertion
)

case class BuildSpec(
  jo: String,                   // compiler version constraint, e.g. ">=1.0"
  name: String,                 // project name — letters and hyphens only
  depth: Option[Int] = None,    // max dependency tree height
  pkg: Option[PackageSpec],     // [package] → lib build; absent → app build
  main: ModuleSpec,
  test: Option[ModuleSpec],
):
  def isLib: Boolean = pkg.isDefined

object BuildSpec:
  val validFfi = Set("none", "ruby", "python", "js", "native")

  def decode(doc: TomlDoc): BuildSpec =
    val jo   = requireStr(doc, "jo")
    validateConstraint(jo, "jo")
    val name  = requireStr(doc, "name")
    validateName(name)
    val depth = doc.get("depth").map(asInt(_, "depth"))
    val pkg   = doc.get("package").map(v => decodePackage(asTbl(v, "package")))

    val mainTbl = doc.get("main").map(asTbl(_, "main")).getOrElse(Map.empty)
    val main    = decodeSection(mainTbl, "main")
    val test    = doc.get("test").map(v => decodeSection(asTbl(v, "test"), "test"))

    BuildSpec(jo, name, depth, pkg, main, test)

  private def decodePackage(tbl: Map[String, TomlValue]): PackageSpec =
    val version     = requireStr(tbl, "version", "[package]")
    val description = tbl.get("description").map(asStr(_, "[package].description"))
    val ffi         = tbl.get("ffi").map(asStr(_, "[package].ffi"))

    ffi.foreach: f =>
      if !validFfi.contains(f) then
        throw TomlError(s"invalid [package].ffi value '$f', must be one of: ${validFfi.mkString(", ")}")

    validateVersion(version, "[package].version")

    PackageSpec(version, description, ffi)

  private def decodeSection(tbl: Map[String, TomlValue], ctx: String): ModuleSpec =
    val src    = tbl.get("src").map(asStrList(_, s"$ctx.src")).getOrElse(Nil)
    val target = tbl.get("target").map(asStr(_, s"$ctx.target"))
    val deps   = tbl.get("dependencies").map(asTbl(_, s"$ctx.dependencies")).getOrElse(Map.empty)
    val links  = tbl.get("links").map(asTbl(_, s"$ctx.links")).getOrElse(Map.empty)

    ModuleSpec(
      src,
      target,
      deps.map { (k, v) => k -> decodeDep(v, k) },
      links.map { (k, v) => k -> asStr(v, s"$ctx.links.$k") },
    )

  private def decodeDep(v: TomlValue, name: String): DepSpec = v match
    case Str(constraint) =>
      validateConstraint(constraint, s"dependency '$name'")
      DepSpec(DepSource.Registry(constraint))
    case Tbl(fields) =>
      val link = fields.get("link") match
        case Some(Bool(true))  => DepLink.Link
        case Some(Bool(false)) => DepLink.Check
        case Some(_)           => throw TomlError(s"dependency '$name'.link must be a boolean")
        case None              => DepLink.Check

      fields.get("path") match
        case Some(Str(p)) =>
          val spec = fields.get("spec").map(asStr(_, s"$name.spec"))
          DepSpec(DepSource.Path(p, spec), link)
        case Some(_) => throw TomlError(s"dependency '$name'.path must be a string")
        case None    =>
          fields.get("version") match
            case Some(Str(c)) =>
              validateConstraint(c, s"dependency '$name'.version")
              DepSpec(DepSource.Registry(c), link)
            case Some(_) => throw TomlError(s"dependency '$name'.version must be a string")
            case None    => throw TomlError(s"dependency '$name' must have 'path' or 'version'")
    case _ => throw TomlError(s"dependency '$name' must be a string or inline table")

  private def validateName(name: String): Unit =
    if name.isEmpty || !name.forall(c => c.isLetter || c == '-') then
      throw TomlError(s"invalid name '$name', must contain only letters and hyphens")

  private def validateVersion(v: String, ctx: String): Unit =
    val parts = v.split("\\.")
    if parts.length != 3 || !parts.forall(_.forall(_.isDigit)) then
      throw TomlError(s"invalid $ctx '$v', must be MAJOR.MINOR.PATCH")

  private def validateConstraint(v: String, ctx: String): Unit =
    val vStr  = v.dropWhile("><^~=".contains(_)).trim
    val parts = vStr.split("\\.")
    if parts.length != 2 || !parts.forall(_.forall(_.isDigit)) then
      throw TomlError(s"invalid $ctx '$v', version must be MAJOR.MINOR (e.g. \">=1.2\")")

  // ---- Helpers -------------------------------------------------------------

  private def requireStr(doc: Map[String, TomlValue], key: String, ctx: String = ""): String =
    val label = if ctx.nonEmpty then s"$ctx.$key" else key

    doc.get(key) match
      case Some(Str(s)) => s
      case Some(_)      => throw TomlError(s"'$label' must be a string")
      case None         => throw TomlError(s"missing required field '$label'")

  private def asStr(v: TomlValue, ctx: String): String = v match
    case Str(s) => s
    case _      => throw TomlError(s"'$ctx' must be a string")

  private def asInt(v: TomlValue, ctx: String): Int = v match
    case Integer(n) => n.toInt
    case _          => throw TomlError(s"'$ctx' must be an integer")

  private def asTbl(v: TomlValue, ctx: String): Map[String, TomlValue] = v match
    case Tbl(m) => m
    case _      => throw TomlError(s"'$ctx' must be a table")

  private def asStrList(v: TomlValue, ctx: String): List[String] = v match
    case Arr(items) => items.map:
      case Str(s) => s
      case _      => throw TomlError(s"'$ctx' must be an array of strings")
    case _ => throw TomlError(s"'$ctx' must be an array")

package tool

import tool.toml.TomlValue.*
import tool.toml.{TomlValue, TomlDoc, TomlError}

/** Dependency specification: either a local path or a registry version constraint. */
enum DepSpec:
  case Path(path: String)
  case Registry(constraint: String)

case class SectionSpec(
  src: List[String],
  dependencies: Map[String, DepSpec],
  links: Map[String, String],
)

case class BuildSpec(
  namespace: String,
  version: String,
  ffi: String,
  main: SectionSpec,
  test: Option[SectionSpec],
)

object BuildSpec:
  val validFfi = Set("none", "ruby", "python", "js", "native")

  def decode(doc: TomlDoc): BuildSpec =
    val namespace = requireStr(doc, "namespace")
    val version   = requireStr(doc, "version")
    val ffi       = doc.get("ffi").map(asStr(_, "ffi")).getOrElse("none")

    if !validFfi.contains(ffi) then
      throw TomlError(s"invalid ffi value '$ffi', must be one of: ${validFfi.mkString(", ")}")

    validateVersion(version)

    val mainTbl = requireTbl(doc, "main")
    val main    = decodeSection(mainTbl, "main")
    val test    = doc.get("test").map(v => decodeSection(asTbl(v, "test"), "test"))

    BuildSpec(namespace, version, ffi, main, test)

  private def decodeSection(tbl: Map[String, TomlValue], ctx: String): SectionSpec =
    val src          = tbl.get("src").map(asStrList(_, s"$ctx.src")).getOrElse(Nil)
    val depsTbl      = tbl.get("dependencies").map(asTbl(_, s"$ctx.dependencies")).getOrElse(Map.empty)
    val linksTbl     = tbl.get("links").map(asTbl(_, s"$ctx.links")).getOrElse(Map.empty)
    val dependencies = depsTbl.map { (k, v) => k -> decodeDep(v, k) }
    val links        = linksTbl.map { (k, v) => k -> asStr(v, s"$ctx.links.$k") }
    SectionSpec(src, dependencies, links)

  private def decodeDep(v: TomlValue, name: String): DepSpec = v match
    case Tbl(fields) =>
      fields.get("path") match
        case Some(Str(p)) => DepSpec.Path(p)
        case Some(_)      => throw TomlError(s"dependency '$name'.path must be a string")
        case None         =>
          fields.get("version") match
            case Some(Str(c)) => DepSpec.Registry(c)
            case Some(_)      => throw TomlError(s"dependency '$name'.version must be a string")
            case None         => throw TomlError(s"dependency '$name' must have 'path' or 'version'")
    case Str(constraint) => DepSpec.Registry(constraint)
    case _ => throw TomlError(s"dependency '$name' must be a string or inline table")

  private def validateVersion(v: String): Unit =
    val parts = v.split("\\.")
    if parts.length != 3 || !parts.forall(_.forall(_.isDigit)) then
      throw TomlError(s"invalid version '$v', must be MAJOR.MINOR.PATCH")

  // ---- Helpers -------------------------------------------------------------

  private def requireStr(doc: Map[String, TomlValue], key: String): String =
    doc.get(key) match
      case Some(Str(s)) => s
      case Some(_)      => throw TomlError(s"'$key' must be a string")
      case None         => throw TomlError(s"missing required field '$key'")

  private def requireTbl(doc: Map[String, TomlValue], key: String): Map[String, TomlValue] =
    doc.get(key) match
      case Some(Tbl(m)) => m
      case Some(_)      => throw TomlError(s"'$key' must be a table")
      case None         => throw TomlError(s"missing required table [$key]")

  private def asStr(v: TomlValue, ctx: String): String = v match
    case Str(s) => s
    case _      => throw TomlError(s"'$ctx' must be a string")

  private def asTbl(v: TomlValue, ctx: String): Map[String, TomlValue] = v match
    case Tbl(m) => m
    case _      => throw TomlError(s"'$ctx' must be a table")

  private def asStrList(v: TomlValue, ctx: String): List[String] = v match
    case Arr(items) => items.map {
      case Str(s) => s
      case _      => throw TomlError(s"'$ctx' must be an array of strings")
    }
    case _ => throw TomlError(s"'$ctx' must be an array")

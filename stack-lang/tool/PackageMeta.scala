package tool

import tool.toml.TomlValue.*
import tool.toml.{TomlValue, TomlDoc, TomlError}

case class PackageDependencyInfo(
  jo: VersionSpec,
  platform: String,
  dependencies: Map[String, VersionSpec],
)

case class PackageMeta(
  namespace: String,
  name: String,
  jo: VersionSpec,
  version: String,
  platform: String,
  description: Option[String] = None,
  authors: List[String] = Nil,
  homepage: Option[String] = None,
  license: Option[String] = None,
  keywords: List[String] = Nil,
  dependencies: Map[String, VersionSpec],
)

object PackageMeta:
  def dependencyInfo(meta: PackageMeta): PackageDependencyInfo =
    PackageDependencyInfo(
      jo = meta.jo,
      platform = meta.platform,
      dependencies = meta.dependencies,
    )

  def decode(doc: TomlDoc): PackageMeta =
    val namespace = requireStr(doc, "namespace")
    val name      = requireStr(doc, "name")
    val jo        = requireVersionSpec(doc, "jo")
    val version   = requireStr(doc, "version")
    val platform =
      doc.get("platform")
        .orElse(doc.get("runtime"))
        .map(asStr(_, "platform"))
        .getOrElse("pure")
    val description = doc.get("description").map(asStr(_, "description"))
    val authors = doc.get("authors").map(asStrList(_, "authors")).getOrElse(Nil)
    val homepage = doc.get("homepage").map(asStr(_, "homepage"))
    val license = doc.get("license").map(asStr(_, "license"))
    val keywords = doc.get("keywords").map(asStrList(_, "keywords")).getOrElse(Nil)
    val dependencies = doc.get("dependencies").map(asDependencies(_, "dependencies")).getOrElse(Map.empty)

    if !BuildSpec.validPlatforms.contains(platform) then
      throw TomlError(s"invalid platform value '$platform'")

    PackageMeta(namespace, name, jo, version, platform, description, authors, homepage, license, keywords, dependencies)

  private def requireStr(doc: Map[String, TomlValue], key: String): String =
    doc.get(key) match
      case Some(Str(s)) => s
      case Some(_)      => throw TomlError(s"'$key' must be a string")
      case None         => throw TomlError(s"missing required field '$key'")

  private def requireVersionSpec(doc: Map[String, TomlValue], key: String): VersionSpec =
    doc.get(key) match
      case Some(Str(s)) =>
        VersionSpec.parse(s) match
          case Right(spec) => spec
          case Left(msg)   => throw TomlError(s"invalid $key '$s': $msg")
      case Some(_) =>
        throw TomlError(s"'$key' must be a string")
      case None =>
        throw TomlError(s"missing required field '$key'")

  private def asStr(v: TomlValue, ctx: String): String = v match
    case Str(s) => s
    case _      => throw TomlError(s"'$ctx' must be a string")

  private def asStrList(v: TomlValue, ctx: String): List[String] = v match
    case Arr(items) =>
      items.map:
        case Str(s) => s
        case _      => throw TomlError(s"'$ctx' must be an array of strings")
    case _ =>
      throw TomlError(s"'$ctx' must be an array")

  private def asDependencies(v: TomlValue, ctx: String): Map[String, VersionSpec] = v match
    case Tbl(m) =>
      m.map: (k, value) =>
        val raw = asStr(value, s"$ctx.$k")
        VersionSpec.parse(raw) match
          case Left(msg)    => throw TomlError(s"invalid $ctx.$k '$raw': $msg")
          case Right(spec)  => k -> spec

    case _ =>
      throw TomlError(s"'$ctx' must be a table")

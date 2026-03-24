package tool

import tool.toml.TomlValue.*
import tool.toml.{TomlValue, TomlDoc, TomlError}

case class PackageMeta(
  namespace: String,
  name: String,
  version: String,
  ffi: String,
  description: Option[String] = None,
  authors: List[String] = Nil,
  homepage: Option[String] = None,
  license: Option[String] = None,
  keywords: List[String] = Nil,
  dependencies: Map[String, String] = Map.empty,
)

object PackageMeta:
  def decode(doc: TomlDoc): PackageMeta =
    val namespace = requireStr(doc, "namespace")
    val name      = requireStr(doc, "name")
    val version   = requireStr(doc, "version")
    val ffi       = doc.get("ffi").map(asStr(_, "ffi")).getOrElse("none")
    val description = doc.get("description").map(asStr(_, "description"))
    val authors = doc.get("authors").map(asStrList(_, "authors")).getOrElse(Nil)
    val homepage = doc.get("homepage").map(asStr(_, "homepage"))
    val license = doc.get("license").map(asStr(_, "license"))
    val keywords = doc.get("keywords").map(asStrList(_, "keywords")).getOrElse(Nil)
    val dependencies = doc.get("dependencies").map(asDependencies(_, "dependencies")).getOrElse(Map.empty)

    if !BuildSpec.validFfi.contains(ffi) then
      throw TomlError(s"invalid ffi value '$ffi'")

    PackageMeta(namespace, name, version, ffi, description, authors, homepage, license, keywords, dependencies)

  private def requireStr(doc: Map[String, TomlValue], key: String): String =
    doc.get(key) match
      case Some(Str(s)) => s
      case Some(_)      => throw TomlError(s"'$key' must be a string")
      case None         => throw TomlError(s"missing required field '$key'")

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

  private def asDependencies(v: TomlValue, ctx: String): Map[String, String] = v match
    case Tbl(m) =>
      m.map: (k, value) =>
        k -> asStr(value, s"$ctx.$k")

    case _ =>
      throw TomlError(s"'$ctx' must be a table")

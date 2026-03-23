package tool

import tool.toml.TomlValue.*
import tool.toml.{TomlValue, TomlDoc, TomlError}

case class PackageMeta(
  namespace: String,
  name: String,
  version: String,
  ffi: String,
)

object PackageMeta:
  def decode(doc: TomlDoc): PackageMeta =
    val namespace = requireStr(doc, "namespace")
    val name      = requireStr(doc, "name")
    val version   = requireStr(doc, "version")
    val ffi       = doc.get("ffi").map(asStr(_, "ffi")).getOrElse("none")

    if !BuildSpec.validFfi.contains(ffi) then
      throw TomlError(s"invalid ffi value '$ffi'")

    PackageMeta(namespace, name, version, ffi)

  private def requireStr(doc: Map[String, TomlValue], key: String): String =
    doc.get(key) match
      case Some(Str(s)) => s
      case Some(_)      => throw TomlError(s"'$key' must be a string")
      case None         => throw TomlError(s"missing required field '$key'")

  private def asStr(v: TomlValue, ctx: String): String = v match
    case Str(s) => s
    case _      => throw TomlError(s"'$ctx' must be a string")

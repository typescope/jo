package tool

import tool.toml.TomlValue.*
import tool.toml.{TomlValue, TomlDoc, TomlError}

case class LockedPackage(name: String, version: String, sha512: String)
case class LockFile(packages: List[LockedPackage])

object LockFile:
  def decode(doc: TomlDoc): LockFile =
    val packages = doc.get("package") match
      case Some(Arr(entries)) =>
        entries.zipWithIndex.map { (v, i) =>
          val fields = asTbl(v, s"[[package]] entry $i")
          val name    = requireStr(fields, "name",   s"[[package]] entry $i")
          val version = requireStr(fields, "version", s"[[package]] entry $i")
          val sha512  = requireStr(fields, "sha512",  s"[[package]] entry $i")
          LockedPackage(name, version, sha512)
        }
      case Some(_) => throw TomlError("'package' must be an array-of-tables")
      case None    => Nil
    LockFile(packages)

  private def requireStr(fields: Map[String, TomlValue], key: String, ctx: String): String =
    fields.get(key) match
      case Some(Str(s)) => s
      case Some(_)      => throw TomlError(s"$ctx '$key' must be a string")
      case None         => throw TomlError(s"$ctx missing required field '$key'")

  private def asTbl(v: TomlValue, ctx: String): Map[String, TomlValue] = v match
    case Tbl(m) => m
    case _      => throw TomlError(s"$ctx must be a table")

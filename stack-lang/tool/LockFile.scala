package tool

import java.nio.file.{Files, Path}
import tool.toml.TomlValue.*
import tool.toml.{TomlValue, TomlDoc, TomlError}
import tool.toml.TomlParser

case class LockedPackage(name: String, version: String, sha512: String)
case class LockFile(packages: List[LockedPackage])

object LockFile:
  def decode(doc: TomlDoc): LockFile =
    val packages = doc.toSeq.sortBy(_._1).map: (name, value) =>
      val fields  = asTbl(value, s"package '$name'")
      val version = requireStr(fields, "version", s"package '$name'")
      val sha512  = requireStr(fields, "sha512",  s"package '$name'")
      LockedPackage(name, version, sha512)
    LockFile(packages)

  def load(path: Path): Result[Option[LockFile]] =
    if !Files.exists(path) then
      Result.Ok(None)
    else
      try
        val doc = TomlParser.parse(Files.readString(path))
        Result.Ok(Some(decode(doc)))
      catch
        case e: TomlError => Result.Err(s"in $path: ${e.getMessage}")

  def write(path: Path, lock: LockFile): Result[Unit] =
    try
      Files.writeString(path, render(lock))
      Result.unit
    catch
      case e: Exception => Result.Err(e.getMessage)

  def render(lock: LockFile): String =
    lock.packages.map: pkg =>
      s"""${quoteKey(pkg.name)} = { version = "${pkg.version}", sha512 = "${pkg.sha512}" }"""
    .mkString("\n")

  private def requireStr(fields: Map[String, TomlValue], key: String, ctx: String): String =
    fields.get(key) match
      case Some(Str(s)) => s
      case Some(_)      => throw TomlError(s"$ctx '$key' must be a string")
      case None         => throw TomlError(s"$ctx missing required field '$key'")

  private def asTbl(v: TomlValue, ctx: String): Map[String, TomlValue] = v match
    case Tbl(m) => m
    case _      => throw TomlError(s"$ctx must be a table")

  private def quoteKey(key: String): String =
    "\"" + key.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

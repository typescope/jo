package tool

import java.nio.file.{Files, Path}
import tool.toml.TomlValue.*
import tool.toml.{TomlValue, TomlDoc, TomlError}
import tool.toml.TomlParser

case class LockedPackage(name: String, version: String, sha512: String)
case class LockFile(jo: Option[String], packages: List[LockedPackage])

object LockFile:
  def pathForSpec(specPath: Path): Path =
    val fileName = specPath.getFileName.toString
    val stem = fileName.lastIndexOf('.') match
      case -1 => fileName
      case i  => fileName.take(i)
    specPath.resolveSibling(s"$stem.lock")

  def decode(doc: TomlDoc): LockFile =
    val jo = doc.get("jo") match
      case Some(Str(s)) => Some(s)
      case Some(_)      => throw TomlError("'jo' must be a string")
      case None         => None

    val packages = doc.toSeq.filter(_._1 != "jo").sortBy(_._1).map: (name, value) =>
      val fields  = asTbl(value, s"package '$name'")
      val version = requireStr(fields, "version", s"package '$name'")
      val sha512  = requireStr(fields, "sha512",  s"package '$name'")
      LockedPackage(name, version, sha512)
    LockFile(jo, packages.toList)

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
    if lock.jo.isEmpty && lock.packages.isEmpty then
      ""
    else
      val lines = collection.mutable.ListBuffer.empty[String]
      lock.jo.foreach(v => lines += s"""jo = "$v"""")
      lines ++= lock.packages.map: pkg =>
        s"""${renderKey(pkg.name)} = { version = "${pkg.version}", sha512 = "${pkg.sha512}" }"""
      lines.mkString("", "\n", "\n")

  private def requireStr(fields: Map[String, TomlValue], key: String, ctx: String): String =
    fields.get(key) match
      case Some(Str(s)) => s
      case Some(_)      => throw TomlError(s"$ctx '$key' must be a string")
      case None         => throw TomlError(s"$ctx missing required field '$key'")

  private def asTbl(v: TomlValue, ctx: String): Map[String, TomlValue] = v match
    case Tbl(m) => m
    case _      => throw TomlError(s"$ctx must be a table")

  private def renderKey(key: String): String =
    if key.matches("[A-Za-z0-9_-]+") then key
    else quoteKey(key)

  private def quoteKey(key: String): String =
    "\"" + key.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

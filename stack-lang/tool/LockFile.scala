package tool

import java.nio.file.{Files, Path}
import tool.toml.TomlValue.*
import tool.toml.{TomlValue, TomlDoc, TomlError}
import tool.toml.TomlParser

case class LockedPackage(name: String, version: String, sha512: String)

enum LockedGitSource:
  case Precompiled(joyUrl: String, sha512: String)
  case Source(rev: String)

case class LockedGitDep(name: String, url: String, source: LockedGitSource)

case class LockFile(jo: Option[Version], packages: List[LockedPackage], gitDeps: List[LockedGitDep] = Nil)

object LockFile:
  def pathForSpec(specPath: Path): Path =
    val fileName = specPath.getFileName.toString
    val stem = fileName.lastIndexOf('.') match
      case -1 => fileName
      case i  => fileName.take(i)
    specPath.resolveSibling(s"$stem.lock")

  def decode(doc: TomlDoc): LockFile =
    val jo = doc.get("jo") match
      case Some(Str(s)) =>
        Version.parse(s) match
          case Some(version) => Some(version)
          case None          => throw TomlError(s"'jo' must be a version in MAJOR.MINOR.PATCH format")
      case Some(_)      => throw TomlError("'jo' must be a string")
      case None         => None

    val packages = doc.toSeq.filter(k => k._1 != "jo" && k._1 != "git").sortBy(_._1).map: (name, value) =>
      val fields  = asTbl(value, s"package '$name'")
      val version = requireStr(fields, "version", s"package '$name'")
      val sha512  = requireStr(fields, "sha512",  s"package '$name'")
      LockedPackage(name, version, sha512)

    val gitDeps = doc.get("git").map: v =>
      val tbl = asTbl(v, "[git]")
      tbl.toSeq.sortBy(_._1).map: (name, value) =>
        val fields = asTbl(value, s"[git].$name")
        val url    = requireStr(fields, "url", s"[git].$name")
        val source =
          if fields.contains("joy-url") then
            val joyUrl = requireStr(fields, "joy-url", s"[git].$name")
            val sha512 = requireStr(fields, "sha512",  s"[git].$name")
            LockedGitSource.Precompiled(joyUrl, sha512)
          else
            LockedGitSource.Source(requireStr(fields, "rev", s"[git].$name"))
        LockedGitDep(name, url, source)
      .toList
    .getOrElse(Nil)

    LockFile(jo, packages.toList, gitDeps)

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
    if lock.jo.isEmpty && lock.packages.isEmpty && lock.gitDeps.isEmpty then
      ""
    else
      val lines = collection.mutable.ListBuffer.empty[String]
      lock.jo.foreach(v => lines += s"""jo = "$v"""")
      lines ++= lock.packages.map: pkg =>
        s"""${renderKey(pkg.name)} = { version = "${pkg.version}", sha512 = "${pkg.sha512}" }"""

      if lock.gitDeps.nonEmpty then
        lines += ""
        lines += "[git]"
        lock.gitDeps.sortBy(_.name).foreach: dep =>
          val sourceStr = dep.source match
            case LockedGitSource.Precompiled(joyUrl, sha512) =>
              s"""url = "${dep.url}", joy-url = "$joyUrl", sha512 = "$sha512""""
            case LockedGitSource.Source(rev) =>
              s"""url = "${dep.url}", rev = "$rev""""
          lines += s"""${renderKey(dep.name)} = { $sourceStr }"""

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

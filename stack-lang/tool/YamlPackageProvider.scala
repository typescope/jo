package tool

import java.nio.file.{Files, Path}
import scala.collection.mutable
import scala.util.boundary
import scala.util.boundary.break

private enum YamlValue:
  case Str(value: String)
  case Obj(fields: Map[String, YamlValue])

case class YamlRepoError(message: String) extends Exception(message)

private object SimpleYaml:
  private final class Node:
    val fields = mutable.LinkedHashMap.empty[String, Either[String, Node]]

  def parse(src: String): Either[String, Map[String, YamlValue]] =
    val root = Node()
    val stack = mutable.Stack.empty[(Int, Node)]
    stack.push(0 -> root)

    boundary:
      for (rawLine, index) <- src.linesIterator.zipWithIndex do
        val lineNo = index + 1
        val line = rawLine.takeWhile(_ != '#')
        if line.trim.nonEmpty then
          if line.exists(_ == '\t') then
            break(Left(s"line $lineNo: tabs are not supported"))

          val indent = line.takeWhile(_ == ' ').length
          if indent % 2 != 0 then
            break(Left(s"line $lineNo: indentation must use multiples of two spaces"))

          while stack.lengthCompare(1) > 0 && indent < stack.top._1 do
            stack.pop()

          if indent != stack.top._1 then
            break(Left(s"line $lineNo: invalid indentation"))

          val content = line.trim
          val colon = content.indexOf(':')
          if colon < 0 then
            break(Left(s"line $lineNo: expected 'key:' or 'key: value'"))

          val key = content.take(colon).trim
          val rest = content.drop(colon + 1).trim
          if key.isEmpty then
            break(Left(s"line $lineNo: empty key"))

          val current = stack.top._2
          if rest.isEmpty then
            val child = Node()
            current.fields(key) = Right(child)
            stack.push((indent + 2) -> child)
          else
            current.fields(key) = Left(unquote(rest))

      Right(materialize(root))

  private def unquote(value: String): String =
    if value.length >= 2 &&
      ((value.startsWith("\"") && value.endsWith("\"")) ||
       (value.startsWith("'") && value.endsWith("'"))) then
      value.substring(1, value.length - 1)
    else
      value

  private def materialize(node: Node): Map[String, YamlValue] =
    node.fields.iterator.map:
      case (key, Left(value)) => key -> YamlValue.Str(value)
      case (key, Right(child)) => key -> YamlValue.Obj(materialize(child))
    .toMap

private case class YamlRelease(meta: PackageMeta, yanked: Boolean)

case class YamlPackageProvider(repoFile: Path, cacheHome: Path) extends PackageProvider:
  private val packages: Map[String, Map[Version, YamlRelease]] =
    loadRepo(repoFile)

  def versions(name: String): Result[List[Version]] =
    packages.get(name) match
      case Some(versions) => Result.Ok(versions.collect { case (v, rel) if !rel.yanked => v }.toList.sorted)
      case None           => Result.Err(s"package not found: $name")

  def meta(name: String, version: Version): Result[PackageMeta] =
    packages.get(name).flatMap(_.get(version)) match
      case Some(release) => Result.Ok(release.meta)
      case None       => Result.Err(s"package artifact not found: ${pathFor(name, version)}")

  def path(name: String, version: Version): Result[Path] =
    if packages.get(name).exists(_.contains(version)) then Result.Ok(pathFor(name, version))
    else Result.Err(s"package artifact not found: ${pathFor(name, version)}")

  def digest(name: String, version: Version): Result[String] =
    if packages.get(name).exists(_.contains(version)) then
      Result.Ok(syntheticDigest(name, version))
    else
      Result.Err(s"package artifact not found: ${pathFor(name, version)}")

  def materialize(name: String, version: Version): Result[Path] =
    path(name, version).map: archive =>
      val outDir = cacheHome.resolve("packages").resolve(name).resolve(version.toString).resolve("unpacked")
      materializeArchive(archive, outDir)

  private def pathFor(name: String, version: Version): Path =
    repoFile.getParent.resolve("repo").resolve(name).resolve(version.toString).resolve(s"$name-v$version.joy")

  private def syntheticDigest(name: String, version: Version): String =
    val md = java.security.MessageDigest.getInstance("SHA-512")
    md.update(s"$name@$version".getBytes("UTF-8"))
    md.digest().map("%02x".format(_)).mkString

  private def materializeArchive(archive: Path, outDir: Path): Path =
    val digest = Digest.sha512Hex(archive)
    val marker = outDir.resolve(".digest")

    if !(Files.isDirectory(outDir) && Files.exists(marker) && Files.readString(marker) == digest) then
      if Files.exists(outDir) then deleteDir(outDir)
      JoyArchive.unpack(archive, outDir)
      Files.writeString(marker, digest)

    outDir

  private def loadRepo(path: Path): Map[String, Map[Version, YamlRelease]] =
    if !Files.exists(path) then return Map.empty

    val src = Files.readString(path)
    val doc = SimpleYaml.parse(src) match
      case Right(value) => value
      case Left(msg)    => throw YamlRepoError(s"in $path: $msg")

    val packages = asObj(doc.getOrElse("packages", throw YamlRepoError(s"in $path: missing 'packages' map")), "packages", path)
    packages.map: (pkgName, versionsValue) =>
      val versions = asObj(versionsValue, s"packages.$pkgName", path).map: (versionKey, metaValue) =>
        val version = Version.parse(versionKey).getOrElse:
          throw YamlRepoError(s"in $path: invalid package version '$versionKey' in packages.$pkgName")
        version -> decodeRelease(pkgName, versionKey, metaValue, s"packages.$pkgName.$versionKey", path)
      pkgName -> versions

  private def decodeRelease(name: String, version: String, value: YamlValue, ctx: String, path: Path): YamlRelease =
    val fields = asObj(value, ctx, path)
    val yanked = fields.get("yanked").map(asBool(_, s"$ctx.yanked", path)).getOrElse(false)
    YamlRelease(decodeMeta(name, version, fields, ctx, path), yanked)

  private def decodeMeta(name: String, version: String, fields: Map[String, YamlValue], ctx: String, path: Path): PackageMeta =
    val namespace = requireStr(fields, "namespace", ctx, path)
    val jo = requireVersionSpec(fields, "jo", ctx, path)
    val runtime = fields.get("runtime").map(asStr(_, s"$ctx.runtime", path)).getOrElse("pure")
    val description = fields.get("description").map(asStr(_, s"$ctx.description", path))
    val homepage = fields.get("homepage").map(asStr(_, s"$ctx.homepage", path))
    val license = fields.get("license").map(asStr(_, s"$ctx.license", path))
    val dependencies = fields.get("dependencies").map(asDeps(_, s"$ctx.dependencies", path)).getOrElse(Map.empty)
    PackageMeta(
      namespace,
      name,
      jo,
      version,
      runtime,
      description = description,
      homepage = homepage,
      license = license,
      dependencies = dependencies,
    )

  private def asObj(value: YamlValue, ctx: String, path: Path): Map[String, YamlValue] = value match
    case YamlValue.Obj(fields) => fields
    case _                     => throw YamlRepoError(s"in $path: '$ctx' must be a map")

  private def asStr(value: YamlValue, ctx: String, path: Path): String = value match
    case YamlValue.Str(s) => s
    case _                => throw YamlRepoError(s"in $path: '$ctx' must be a string")

  private def asBool(value: YamlValue, ctx: String, path: Path): Boolean = value match
    case YamlValue.Str("true")  => true
    case YamlValue.Str("false") => false
    case _                      => throw YamlRepoError(s"in $path: '$ctx' must be true or false")

  private def requireStr(fields: Map[String, YamlValue], key: String, ctx: String, path: Path): String =
    fields.get(key) match
      case Some(value) => asStr(value, s"$ctx.$key", path)
      case None        => throw YamlRepoError(s"in $path: missing '$ctx.$key'")

  private def requireVersionSpec(fields: Map[String, YamlValue], key: String, ctx: String, path: Path): VersionSpec =
    val raw = requireStr(fields, key, ctx, path)
    VersionSpec.parse(raw) match
      case Right(spec) => spec
      case Left(msg)   => throw YamlRepoError(s"in $path: invalid $ctx.$key '$raw': $msg")

  private def asDeps(value: YamlValue, ctx: String, path: Path): Map[String, VersionSpec] =
    asObj(value, ctx, path).map: (name, depValue) =>
      val raw = asStr(depValue, s"$ctx.$name", path)
      VersionSpec.parse(raw) match
        case Right(spec) => name -> spec
        case Left(msg)   => throw YamlRepoError(s"in $path: invalid $ctx.$name '$raw': $msg")

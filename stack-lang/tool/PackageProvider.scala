package tool

import java.nio.file.{Files, Path}
import java.util.zip.ZipFile
import scala.jdk.CollectionConverters.*
import tool.toml.TomlParser

trait PackageProvider:
  def versions(name: String): Result[List[Version]]
  def meta(name: String, version: Version): Result[PackageMeta]
  def path(name: String, version: Version): Result[Path]
  def digest(name: String, version: Version): Result[String]
  def materialize(name: String, version: Version): Result[Path]

object PackageProvider:
  def default(): PackageProvider =
    HttpPackageProvider(
      registryUrl = Config.registryUrl,
      cacheHome = Config.cache,
    )

case class LocalPackageProvider(root: Path, cacheHome: Path) extends PackageProvider:
  def versions(name: String): Result[List[Version]] =
    val pkgDir = root.resolve(name)

    if !Files.isDirectory(pkgDir) then
      return Result.Err(s"package not found: $name")

    val versions = Files.list(pkgDir).iterator.asScala
      .filter(Files.isDirectory(_))
      .flatMap(dir => Version.parse(dir.getFileName.toString))
      .toList
      .sorted

    Result.Ok(versions)

  def meta(name: String, version: Version): Result[PackageMeta] =
    path(name, version).flatMap: archive =>
      val zip = ZipFile(archive.toFile)

      try
        val entry = zip.getEntry("meta.toml")
        if entry == null then
          Result.Err(s"meta.toml not found in $archive")
        else
          val src = String(zip.getInputStream(entry).readAllBytes(), "UTF-8")
          try Result.Ok(PackageMeta.decode(TomlParser.parse(src)))
          catch
            case e: Exception => Result.Err(e.getMessage)

      finally zip.close()

  def path(name: String, version: Version): Result[Path] =
    val archive = root.resolve(name).resolve(version.toString).resolve(s"$name-v$version.joy")

    if Files.exists(archive) then Result.Ok(archive)
    else Result.Err(s"package artifact not found: $archive")

  def digest(name: String, version: Version): Result[String] =
    path(name, version).map(Digest.sha512Hex)

  def materialize(name: String, version: Version): Result[Path] =
    path(name, version).map: archive =>
      val outDir = cacheHome.resolve("packages").resolve(name).resolve(version.toString).resolve("unpacked")
      materializeArchive(archive, outDir)

  private def materializeArchive(archive: Path, outDir: Path): Path =
    val digest = Digest.sha512Hex(archive)
    val marker = outDir.resolve(".digest")

    if !(Files.isDirectory(outDir) && Files.exists(marker) && Files.readString(marker) == digest) then
      if Files.exists(outDir) then deleteDir(outDir)
      JoyArchive.unpack(archive, outDir)
      Files.writeString(marker, digest)

    outDir

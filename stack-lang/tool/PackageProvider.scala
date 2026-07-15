package tool

import java.nio.file.{Files, Path}
import java.util.zip.ZipFile
import scala.jdk.CollectionConverters.*
import tool.toml.TomlParser

trait PackageProvider:
  /** List all published versions for `name` that are available from this provider. */
  def versions(name: String): Result[List[Version]]

  /** Return the subset of package data needed during dependency resolution.
   *
   *  This exists separately from [[meta]] so resolvers can select versions
   *  without downloading or unpacking the full package archive. For the HTTP
   *  provider, this data comes from `<name>.jsonl`, which is much cheaper to
   *  fetch than a `.joy` artifact.
   *
   *  Only fields that affect dependency selection should live here:
   *  the required Jo compiler range, platform, and transitive package
   *  dependencies.
   */
  def dependencyInfo(name: String, version: Version): Result[PackageDependencyInfo]

  /** Return the complete package metadata from the package artifact.
   *
   *  This is intentionally separate from [[dependencyInfo]]. Full metadata is
   *  still needed for commands such as `jo info`, which display fields like
   *  namespace, description, homepage, authors, license, and keywords. Those
   *  fields do not affect dependency resolution, so resolution should prefer
   *  [[dependencyInfo]] and avoid calling [[meta]] until full package metadata
   *  is actually needed.
   */
  def meta(name: String, version: Version): Result[PackageMeta]

  /** Return the local path to the `.joy` archive, downloading it if needed. */
  def path(name: String, version: Version): Result[Path]

  /** Return the expected sha512 digest for the package artifact. */
  def digest(name: String, version: Version): Result[String]

  /** Unpack the package archive into a stable local directory and return it. */
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

  def dependencyInfo(name: String, version: Version): Result[PackageDependencyInfo] =
    meta(name, version).map(PackageMeta.dependencyInfo)

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

package tool

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

@main def printResolved(specFile: String): Unit =
  val specPath = Path.of(specFile).toAbsolutePath
  val specDir = specPath.getParent
  val repoSrc = specDir.resolve("repo-src")
  val repoDir = specDir.resolve("repo")

  try
    rebuildRepo(repoSrc, repoDir)
    given PackageProvider = LocalPackageProvider(repoDir)
    val spec = Graph.loadSpec(specDir, specPath.getFileName.toString)
    DependencyResolver.resolve(spec) match
      case Result.Ok(pkgs) =>
        pkgs.foreach: pkg =>
          println(s"${pkg.name} = ${pkg.version}")
          println(s"  path = ${specDir.relativize(pkg.path)}")
      case Result.Err(msg) =>
        println(s"error: $msg")
  catch
    case e: ToolError => println(s"error: ${e.getMessage}")

private def rebuildRepo(repoSrc: Path, repoDir: Path): Unit =
  if Files.exists(repoDir) then deleteRepoDir(repoDir)
  Files.createDirectories(repoDir)

  if !Files.isDirectory(repoSrc) then return

  val packages = Files.list(repoSrc).iterator.asScala
    .filter(Files.isDirectory(_))
    .toList
    .sortBy(_.getFileName.toString)

  for pkgDir <- packages do
    val name = pkgDir.getFileName.toString
    val versions = Files.list(pkgDir).iterator.asScala
      .filter(Files.isDirectory(_))
      .toList
      .sortBy(_.getFileName.toString)

    for versionDir <- versions do
      val version = versionDir.getFileName.toString
      val outDir = repoDir.resolve(name).resolve(version)
      val outFile = outDir.resolve(s"$name-v$version.joy")
      Files.createDirectories(outDir)
      JoyArchive.pack(versionDir, outFile)

private def deleteRepoDir(dir: Path): Unit =
  Files.walk(dir)
    .sorted(java.util.Comparator.reverseOrder())
    .forEach(Files.delete)

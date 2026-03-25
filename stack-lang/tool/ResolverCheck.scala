package tool

import java.nio.file.{Files, Path}

@main def printResolved(specFile: String): Unit =
  val specPath = Path.of(specFile).toAbsolutePath
  val specDir = specPath.getParent
  val repoSrc = specDir.resolve("repo-src")
  val repoDir = specDir.resolve("repo")

  try
    FixtureRepo.rebuild(repoSrc, repoDir)
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

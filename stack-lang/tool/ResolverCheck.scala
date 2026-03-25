package tool

import java.nio.file.Path

@main def printResolved(specFile: String): Unit =
  val specPath = Path.of(specFile).toAbsolutePath
  val specDir = specPath.getParent
  val repoFile = specDir.resolve("repo.yaml")

  try
    given PackageProvider = YamlPackageProvider(repoFile)
    val spec = Project.loadSpec(specDir, specPath.getFileName.toString)
    DependencyResolver.resolveSpec(spec) match
      case Result.Ok(pkgs) =>
        pkgs.foreach: pkg =>
          println(s"${pkg.name} = ${pkg.version}")
          println(s"  path = ${specDir.relativize(pkg.path)}")
      case Result.Err(msg) =>
        println(s"error: $msg")
  catch
    case e: ToolError => println(s"error: ${e.getMessage}")

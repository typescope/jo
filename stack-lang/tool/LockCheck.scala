package tool

import java.nio.file.Path

def lockCheck(specFile: String): String =
  val specPath = Path.of(specFile).toAbsolutePath
  val specDir = specPath.getParent
  val repoFile = specDir.resolve("repo.yaml")
  val lockPath = specPath.resolveSibling(specPath.getFileName.toString.stripSuffix(".toml") + ".lock")

  try
    val provider = YamlPackageProvider(repoFile)
    given PackageProvider = provider
    val project = Project.load(specPath)

    val resolved = LockFile.load(lockPath).flatMap:
      case Some(lock) => DependencyResolver.resolveProject(project, lock)
      case None       => DependencyResolver.resolveProject(project)

    val result = resolved.flatMap: resolved =>
      validatePackageDepths(project, resolved).flatMap: _ =>
        val locked = collection.mutable.ListBuffer.empty[LockedPackage]
        val sorted = resolved.packages.sortBy(_.name)
        val it = sorted.iterator
        var digestErr: Option[String] = None
        while it.hasNext do
          val pkg = it.next()
          provider.digest(pkg.name, pkg.version) match
            case Result.Ok(value) =>
              locked += LockedPackage(pkg.name, pkg.version.toString, value)

            case Result.Err(msg) =>
              digestErr = Some(msg)

        digestErr match
          case Some(msg) =>
            Result.Err(msg)

          case None =>
            val lock = LockFile(locked.toList)
            LockFile.write(lockPath, lock).map(_ => LockFile.render(lock))

    result match
      case Result.Ok(output)  => output
      case Result.Err(msg)    => s"error: $msg\n"
  catch
    case e: ToolError => s"error: ${e.getMessage}\n"

private def validatePackageDepths(project: Project, resolved: ResolutionResult): Result[Unit] =
  val modules =
    if project.test.isDefined then List(ModuleKind.Main, ModuleKind.Test)
    else List(ModuleKind.Main)

  modules.foldLeft(Result.unit): (acc, module) =>
    acc.flatMap: _ =>
      val (actualDepth, deepestPath) = module match
        case ModuleKind.Main => (resolved.mainPackageDepth, resolved.mainDeepestPath)
        case ModuleKind.Test => (resolved.testPackageDepth, resolved.testDeepestPath)
      val allowedDepth = project.depthOf(module)

      if actualDepth > allowedDepth then
        val moduleName = module match
          case ModuleKind.Main => "main"
          case ModuleKind.Test => "test"

        Result.Err(
          s"""package dependency depth exceeded for '${project.name}' $moduleName module: actual $actualDepth, allowed $allowedDepth
             |
             |  Path: ${(project.name :: deepestPath).mkString(" -> ")}""".stripMargin
        )
      else
        Result.unit

@main def printLock(specFile: String): Unit =
  print(lockCheck(specFile))

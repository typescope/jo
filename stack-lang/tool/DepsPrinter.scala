package tool

object DepsPrinter:
  private enum Child:
    case Project(dep: ProjectDep)
    case Package(name: String, pkg: ResolvedPackage)

  def render(project: Project, resolved: ResolutionResult): String =
    val packages = resolved.packages.map(pkg => pkg.name -> pkg).toMap
    val out = StringBuilder()

    appendModule(
      out,
      s"${project.name} [main]",
      project.deps.sortBy(_.name),
      project.main.dependencies.toList.sortBy(_._1),
      packages,
    )

    project.test.foreach: test =>
      if out.nonEmpty then
        out.append("\n")

      appendModule(
        out,
        s"${project.name} [test]",
        project.testDeps.sortBy(_.name),
        test.dependencies.toList.sortBy(_._1),
        packages,
      )

    out.toString

  private def appendModule(
    out: StringBuilder,
    rootLabel: String,
    projectDeps: List[ProjectDep],
    packageDeps: List[(String, DepSpec)],
    packages: Map[String, ResolvedPackage],
  ): Unit =
    appendLine(out, 0, rootLabel)
    appendChildren(out, 1, projectDeps, packageDeps, packages)

  private def appendChildren(
    out: StringBuilder,
    indent: Int,
    projectDeps: List[ProjectDep],
    packageDeps: List[(String, DepSpec)],
    packages: Map[String, ResolvedPackage],
  ): Unit =
    val projects = projectDeps.map(dep => dep.name -> Child.Project(dep))
    val pkgs = packageDeps.flatMap:
      case (name, DepSpec(DepSource.Registry(_), _)) =>
        packages.get(name).map(pkg => name -> Child.Package(name, pkg))

      case _ =>
        None

    (projects ++ pkgs).sortBy(_._1).foreach:
      case (_, Child.Project(dep)) =>
        appendLine(out, indent, dep.name)
        appendChildren(
          out,
          indent + 1,
          dep.project.deps.sortBy(_.name),
          dep.project.main.dependencies.toList.sortBy(_._1),
          packages,
        )

      case (_, Child.Package(name, pkg)) =>
        appendLine(out, indent, s"$name ${pkg.version}")
        appendResolvedPackage(out, indent + 1, pkg, packages)

  private def appendResolvedPackage(
    out: StringBuilder,
    indent: Int,
    pkg: ResolvedPackage,
    packages: Map[String, ResolvedPackage],
  ): Unit =
    pkg.meta.dependencies.toList.sortBy(_._1).foreach: (name, _) =>
      packages.get(name).foreach: dep =>
        appendLine(out, indent, s"$name ${dep.version}")
        appendResolvedPackage(out, indent + 1, dep, packages)

  private def appendLine(out: StringBuilder, indent: Int, text: String): Unit =
    out.append("  " * indent).append(text).append("\n")

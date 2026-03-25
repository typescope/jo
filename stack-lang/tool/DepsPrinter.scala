package tool

object DepsPrinter:
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
    appendProjectChildren(out, 1, projectDeps, packages)
    appendPackageChildren(out, 1, packageDeps, packages)

  private def appendProjectChildren(
    out: StringBuilder,
    indent: Int,
    projectDeps: List[ProjectDep],
    packages: Map[String, ResolvedPackage],
  ): Unit =
    projectDeps.foreach: dep =>
      appendLine(out, indent, dep.name)
      appendProjectChildren(out, indent + 1, dep.project.deps.sortBy(_.name), packages)
      appendPackageChildren(out, indent + 1, dep.project.main.dependencies.toList.sortBy(_._1), packages)

  private def appendPackageChildren(
    out: StringBuilder,
    indent: Int,
    deps: List[(String, DepSpec)],
    packages: Map[String, ResolvedPackage],
  ): Unit =
    deps.foreach:
      case (name, DepSpec(DepSource.Registry(_), _)) =>
        packages.get(name).foreach: pkg =>
          appendLine(out, indent, s"$name ${pkg.version}")
          appendResolvedPackage(out, indent + 1, pkg, packages)

      case _ =>
        ()

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

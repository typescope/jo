package tool

object DepsPrinter:
  private enum Child:
    case Module(project: Project, module: ModuleId)
    case Package(name: String, pkg: ResolvedPackage)

  def render(project: Project, modules: List[ModuleId], resolved: ResolutionResult): String =
    val packages = resolved.packages.map(pkg => pkg.name -> pkg).toMap
    val out = StringBuilder()

    modules.foreach: module =>
      if out.nonEmpty then out.append("\n")
      appendModule(out, project, module, packages)

    out.toString

  private def appendModule(
    out: StringBuilder,
    project: Project,
    module: ModuleId,
    packages: Map[String, ResolvedPackage],
  ): Unit =
    appendLine(out, 0, project.moduleLabel(project, module))
    appendChildren(out, 1, project, project, module, packages)

  private def appendChildren(
    out: StringBuilder,
    indent: Int,
    root: Project,
    project: Project,
    module: ModuleId,
    packages: Map[String, ResolvedPackage],
  ): Unit =
    val moduleDeps = project.moduleDepsOf(module).map: dep =>
      val depProject = dep.project.getOrElse(project)
      (dep.module.value, depProject.relativeProjectPath(root)) -> Child.Module(depProject, dep.module)

    val packageDeps = project.module(module).toList.flatMap: spec =>
      spec.dependencies.flatMap:
        case DepSpec(DepSource.Registry(name, _), _) =>
          packages.get(name).map(pkg => (name, name) -> Child.Package(name, pkg))
        case _ =>
          None

    (moduleDeps ++ packageDeps).sortBy(_._1).foreach:
      case (_, Child.Module(depProject, depModule)) =>
        appendLine(out, indent, depProject.moduleLabel(root, depModule))
        appendChildren(out, indent + 1, root, depProject, depModule, packages)

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

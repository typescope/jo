package tool

import java.nio.file.Path

object Planner:
  /** Returns the build-cache version label for a resolved binary, e.g. "jo-1.2". */
  def joLabel(v: Version): String = s"jo-${v.major}.${v.minor}"

  /** Produce a list of ModulePlans (Main first, then Test if present) from a resolved project. */
  def plan(
    project: Project,
    joVersion: Version,
    registrySastDirs: Map[String, Path],
  ): List[ModulePlan] =
    val root = project.spec
    val rootDir = project.dir
    val joVersionLabel = joLabel(joVersion)
    val allRegistrySastDirs = registrySastDirs.toList.sortBy(_._1).map(_._2)
    val rootRegistryLinkLibs = root.main.dependencies.toList.flatMap:
      case (name, DepSpec(DepSource.Registry(_), DepLink.Link)) => registrySastDirs.get(name).toList
      case _ => Nil

    def depSastDir(dir: Path, spec: BuildSpec): Path =
      dir.resolve(s".build/${spec.name}").resolve(joVersionLabel).resolve("sast")

    def makeDepPlan(dep: ProjectDep, allDeps: List[Project]): Option[ModulePlan] =
      val project = dep.project

      if !project.spec.isLib then None
      else
        val sources = SourceGlob.expand(project.spec.main.src, project.dir)
        val depCheckLibs = checkLibsOf(project, allDeps, depSastDir) ++ allRegistrySastDirs
        val task = CompileTask.LibTask(sources, depCheckLibs, depSastDir(project.dir, project.spec))
        val directDeps = project.deps.flatMap(d => makeDepPlan(d, allDeps))
        Some(ModulePlan(dep.name, task, directDeps))

    val mainDeps = Project.mainDepsTopological(project)
    val testOnlyDeps = Project.testDepsTopological(project)
    val mainDepEdges = allDepEdges(project)

    val checkLibs = mainDepEdges.collect:
      case dep if dep.link == DepLink.Check => depSastDir(dep.project.dir, dep.project.spec)

    val linkLibs = mainDepEdges.collect:
      case dep if dep.link == DepLink.Link => depSastDir(dep.project.dir, dep.project.spec)

    val rootBase = rootDir.resolve(s".build/${root.name}").resolve(joVersionLabel)

    val mainTask: CompileTask =
      if root.isLib then
        val sources = SourceGlob.expand(root.main.src, rootDir)
        CompileTask.LibTask(sources, checkLibs ++ allRegistrySastDirs, rootBase.resolve("sast"))
      else
        val sources = SourceGlob.expand(root.main.src, rootDir)
        val target = resolveTarget(root)
        CompileTask.AppTask(
          sources,
          checkLibs ++ allRegistrySastDirs,
          linkLibs ++ rootRegistryLinkLibs,
          root.main.links,
          target,
          rootBase.resolve(s"target/${root.name}${target.ext}"),
          rootBase.resolve("sast"),
        )

    val mainPlan = ModulePlan(root.name, mainTask, project.deps.flatMap(d => makeDepPlan(d, mainDeps)))

    root.test match
      case None =>
        List(mainPlan)

      case Some(testSpec) =>
        val rootSastDir = rootBase.resolve("sast")
        val testRegistryLinkLibs = testSpec.dependencies.toList.flatMap:
          case (name, DepSpec(DepSource.Registry(_), DepLink.Link)) => registrySastDirs.get(name).toList
          case _ => Nil
        val testDepEdges = allDepEdges(project, test = true)
        val testCheckLibs = rootSastDir :: checkLibs ++ allRegistrySastDirs ++
          testDepEdges.collect:
            case dep if dep.link == DepLink.Check => depSastDir(dep.project.dir, dep.project.spec)
        val testLinkLibs = linkLibs ++ rootRegistryLinkLibs ++ testRegistryLinkLibs ++
          testDepEdges.collect:
            case dep if dep.link == DepLink.Link => depSastDir(dep.project.dir, dep.project.spec)
        val testTarget: Target = testSpec.target
          .orElse(root.main.target)
          .orElse(root.pkg.flatMap(_.ffi).flatMap(Target.parse))
          .getOrElse(Target.Python)
        val testSources = SourceGlob.expand(testSpec.src, rootDir, SourceGlob.defaultTestSrc)

        val testTask = CompileTask.AppTask(
          testSources,
          testCheckLibs,
          testLinkLibs,
          root.main.links ++ testSpec.links,
          testTarget,
          rootBase.resolve(s"target/${root.name}-test${testTarget.ext}"),
          rootBase.resolve("sast-test"),
        )

        val mainAsLib = mainPlan.copy(
          task = mainPlan.task match
            case app: CompileTask.AppTask =>
              CompileTask.LibTask(app.sources, app.checkLibs, app.sastDir)
            case lib => lib
        )

        val testDepPlans = project.testDeps.flatMap(d => makeDepPlan(d, mainDeps ++ testOnlyDeps))
        val testPlan = ModulePlan(root.name, testTask, mainAsLib :: testDepPlans)
        List(mainPlan, testPlan)

  private def checkLibsOf(
    project: Project,
    allDeps: List[Project],
    sastDirOf: (Path, BuildSpec) => Path,
  ): List[Path] =
    project.deps.flatMap: dep =>
      if dep.link == DepLink.Check then
        allDeps.find(_.dir == dep.project.dir).map(d => sastDirOf(d.dir, d.spec)).toList
      else Nil

  private def allDepEdges(project: Project, test: Boolean = false): List[ProjectDep] =
    val ordered = collection.mutable.ListBuffer.empty[ProjectDep]
    val seen = collection.mutable.LinkedHashSet.empty[Path]
    val deps = if test then project.testDeps else project.deps

    def collect(edges: List[ProjectDep], includeTestDeps: Boolean): Unit =
      for dep <- edges do
        collect(dep.project.deps, includeTestDeps = false)

        if includeTestDeps then
          collect(dep.project.testDeps, includeTestDeps = true)

        if seen.add(dep.project.dir) then
          ordered += dep

    collect(deps, test)
    ordered.toList

  private def resolveTarget(spec: BuildSpec): Target =
    spec.main.target
      .orElse(spec.pkg.flatMap(_.ffi).flatMap(Target.parse))
      .getOrElse(Target.Python)

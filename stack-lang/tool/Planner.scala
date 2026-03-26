package tool

import java.nio.file.Path

object Planner:
  /** Produce a list of ModulePlans (Main first, then Test if present) from a resolved project. */
  def plan(project: Project, registrySastDirs: Map[String, Path]): ProjectPlan =
    val root = project
    val allRegistrySastDirs = registrySastDirs.toList.sortBy(_._1).map(_._2)
    val rootRegistryLinkLibs = root.main.dependencies.toList.flatMap:
      case (name, DepSpec(DepSource.Registry(_), DepLink.Link)) => registrySastDirs.get(name).toList
      case _ => Nil

    def makeDepPlan(dep: ProjectDep, allDeps: List[Project]): Option[ModulePlan] =
      val project = dep.project

      if !project.isLib then None
      else
        val sources = SourceGlob.expand(project.main.src, project.dir)
        val depCheckLibs = checkLibsOf(project, allDeps) ++ allRegistrySastDirs
        val task = CompileTask.LibTask(sources, depCheckLibs, project.mainSastDir)
        val directDeps = project.deps.flatMap(d => makeDepPlan(d, allDeps))
        Some(ModulePlan(dep.name, task, directDeps))

    val mainDeps = Project.mainDepsTopological(project)
    val testOnlyDeps = Project.testDepsTopological(project)
    val mainDepEdges = allDepEdges(project)

    val checkLibs = mainDepEdges.collect:
      case dep if dep.link == DepLink.Check => dep.project.mainSastDir

    val linkLibs = mainDepEdges.collect:
      case dep if dep.link == DepLink.Link => dep.project.mainSastDir

    val rootBase = root.buildBaseDir

    val mainTask: CompileTask =
      if root.isLib then
        val sources = SourceGlob.expand(root.main.src, root.dir)
        CompileTask.LibTask(sources, checkLibs ++ allRegistrySastDirs, root.mainSastDir, root.main.compileOptions)
      else
        val sources = SourceGlob.expand(root.main.src, root.dir)
        val target = resolveTarget(root)
        CompileTask.AppTask(
          sources,
          checkLibs ++ allRegistrySastDirs,
          linkLibs ++ rootRegistryLinkLibs,
          root.main.links,
          target,
          rootBase.resolve(s"target/${root.name}${target.ext}"),
          root.mainSastDir,
        )

    val mainPlan = ModulePlan(root.name, mainTask, project.deps.flatMap(d => makeDepPlan(d, mainDeps)))

    root.test match
      case None =>
        ProjectPlan(mainPlan, None, root.joBin)

      case Some(testSpec) =>
        val rootSastDir = root.mainSastDir
        val testRegistryLinkLibs = testSpec.dependencies.toList.flatMap:
          case (name, DepSpec(DepSource.Registry(_), DepLink.Link)) => registrySastDirs.get(name).toList
          case _ => Nil
        val testDepEdges = allDepEdges(project, test = true)
        val testCheckLibs = rootSastDir :: checkLibs ++ allRegistrySastDirs ++
          testDepEdges.collect:
            case dep if dep.link == DepLink.Check => dep.project.mainSastDir
        val testLinkLibs = linkLibs ++ rootRegistryLinkLibs ++ testRegistryLinkLibs ++
          testDepEdges.collect:
            case dep if dep.link == DepLink.Link => dep.project.mainSastDir
        val testTarget: Target = testSpec.target
          .orElse(root.main.target)
          .orElse(root.pkg.flatMap(_.ffi).flatMap(Target.parse))
          .getOrElse(Target.Python)
        val testSources = SourceGlob.expand(testSpec.src, root.dir, SourceGlob.defaultTestSrc)

        val testTask = CompileTask.AppTask(
          testSources,
          testCheckLibs,
          testLinkLibs,
          root.main.links ++ testSpec.links,
          testTarget,
          rootBase.resolve(s"target/${root.name}-test${testTarget.ext}"),
          root.testSastDir,
        )

        val mainAsLib = mainPlan.copy(
          task = mainPlan.task match
            case app: CompileTask.AppTask =>
              CompileTask.LibTask(app.sources, app.checkLibs, app.sastDir)
            case lib => lib
        )

        val testDepPlans = project.testDeps.flatMap(d => makeDepPlan(d, mainDeps ++ testOnlyDeps))
        val testPlan = ModulePlan(root.name, testTask, mainAsLib :: testDepPlans)
        ProjectPlan(mainPlan, Some(testPlan), root.joBin)

  private def checkLibsOf(project: Project, allDeps: List[Project]): List[Path] =
    project.deps.flatMap: dep =>
      if dep.link == DepLink.Check then
        allDeps.find(_.dir == dep.project.dir).map(_.mainSastDir).toList
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

  private def resolveTarget(project: Project): Target =
    project.main.target
      .orElse(project.pkg.flatMap(_.ffi).flatMap(Target.parse))
      .getOrElse(Target.Python)

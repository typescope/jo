package tool

import java.nio.file.Path

object Planner:
  /** Returns the build-cache version label for a resolved binary, e.g. "jo-1.2". */
  def joLabel(v: Version): String = s"jo-${v.major}.${v.minor}"

  /** Produce a list of ModulePlans (Main first, then Test if present) from a resolved dependency graph.
   *
   *  `registrySastDirs` maps registry dep names to their unpacked sast directories.
   */
  def plan(
    graph: ResolvedGraph,
    joVersion: Version,
    registrySastDirs: Map[String, Path],
  ): List[ModulePlan] =
    val root    = graph.root
    val rootDir = graph.rootDir

    val joVersionLabel      = joLabel(joVersion)
    val allRegistrySastDirs = registrySastDirs.toList.sortBy(_._1).map(_._2)
    val rootRegistryLinkLibs = root.main.dependencies.toList.flatMap:
      case (name, DepSpec(DepSource.Registry(_), DepLink.Link)) => registrySastDirs.get(name).toList
      case _ => Nil

    def depSastDir(specDir: Path, spec: BuildSpec): Path =
      specDir.resolve(s".build/${spec.name}").resolve(joVersionLabel).resolve("sast")

    def makeDepPlan(dep: ResolvedDep, allDeps: List[ResolvedDep]): Option[ModulePlan] =
      if !dep.spec.isLib then None
      else
        val sources      = SourceGlob.expand(dep.spec.main.src, dep.specDir)
        val depCheckLibs = checkLibsOf(dep.spec, allDeps, depSastDir) ++ allRegistrySastDirs
        val task         = CompileTask.LibTask(sources, depCheckLibs, depSastDir(dep.specDir, dep.spec))
        val directDeps   = dep.spec.main.dependencies.toList.flatMap: (name, depSpec) =>
          depSpec.source match
            case DepSource.Path(_, _) =>
              allDeps.find(_.name == name).flatMap(d => makeDepPlan(d, allDeps)).toList
            case DepSource.Registry(_) => Nil
        Some(ModulePlan(dep.name, task, directDeps))

    val checkLibs = graph.deps.collect:
      case d if d.link == DepLink.Check => depSastDir(d.specDir, d.spec)

    val linkLibs = graph.deps.collect:
      case d if d.link == DepLink.Link => depSastDir(d.specDir, d.spec)

    val rootBase = rootDir.resolve(s".build/${root.name}").resolve(joVersionLabel)

    val mainTask: CompileTask =
      if root.isLib then
        val sources = SourceGlob.expand(root.main.src, rootDir)
        CompileTask.LibTask(sources, checkLibs ++ allRegistrySastDirs, rootBase.resolve("sast"))
      else
        val sources = SourceGlob.expand(root.main.src, rootDir)
        val target  = resolveTarget(root)
        CompileTask.AppTask(
          sources,
          checkLibs ++ allRegistrySastDirs,
          linkLibs ++ rootRegistryLinkLibs,
          root.main.links,
          target,
          rootBase.resolve(s"target/${root.name}${target.ext}"),
          rootBase.resolve("sast"),
        )

    val mainDeps: List[ModulePlan] = root.main.dependencies.toList.flatMap: (name, depSpec) =>
      depSpec.source match
        case DepSource.Path(_, _) =>
          graph.deps.find(_.name == name).flatMap(d => makeDepPlan(d, graph.deps)).toList
        case DepSource.Registry(_) => Nil

    val mainPlan = ModulePlan(root.name, mainTask, mainDeps)

    root.test match
      case None =>
        List(mainPlan)

      case Some(testSpec) =>
        val rootSastDir = rootBase.resolve("sast")
        val testRegistryLinkLibs = testSpec.dependencies.toList.flatMap:
          case (name, DepSpec(DepSource.Registry(_), DepLink.Link)) => registrySastDirs.get(name).toList
          case _ => Nil
        val testCheckLibs = rootSastDir :: checkLibs ++ allRegistrySastDirs ++
          graph.testDeps.collect:
            case d if d.link == DepLink.Check => depSastDir(d.specDir, d.spec)
        val testLinkLibs = linkLibs ++ rootRegistryLinkLibs ++ testRegistryLinkLibs ++
          graph.testDeps.collect:
            case d if d.link == DepLink.Link => depSastDir(d.specDir, d.spec)
        val testTarget: Target = testSpec.target
          .orElse(root.main.target)
          .orElse(root.pkg.flatMap(_.ffi).flatMap(Target.parse))
          .getOrElse(Target.Python)
        val testSources = SourceGlob.expand(testSpec.src, rootDir, SourceGlob.defaultTestSrc)

        val testTask: CompileTask.AppTask = CompileTask.AppTask(
          testSources,
          testCheckLibs,
          testLinkLibs,
          root.main.links ++ testSpec.links,
          testTarget,
          rootBase.resolve(s"target/${root.name}-test${testTarget.ext}"),
          rootBase.resolve("sast-test"),
        )

        // Main module as a lib dep: the test module type-checks against main's sast output.
        val mainAsLib: ModulePlan = mainPlan.copy(
          task = mainPlan.task match
            case app: CompileTask.AppTask =>
              CompileTask.LibTask(app.sources, app.checkLibs, app.sastDir)
            case lib => lib
        )

        val testDepPlans: List[ModulePlan] = testSpec.dependencies.toList.flatMap: (name, depSpec) =>
          depSpec.source match
            case DepSource.Path(_, _) =>
              graph.testDeps.find(_.name == name).flatMap(d => makeDepPlan(d, graph.allDeps)).toList
            case DepSource.Registry(_) => Nil

        val testPlan = ModulePlan(root.name, testTask, mainAsLib :: testDepPlans)
        List(mainPlan, testPlan)

  // ---- Helpers ---------------------------------------------------------------

  /** Collect the sast dirs of the check-deps of a given spec. */
  private def checkLibsOf(
    spec: BuildSpec, allDeps: List[ResolvedDep],
    sastDirOf: (Path, BuildSpec) => Path,
  ): List[Path] =
    spec.main.dependencies.toList.flatMap: (name, dep) =>
      if dep.link == DepLink.Check then
        allDeps.find(d => d.name == name).map(d => sastDirOf(d.specDir, d.spec)).toList
      else Nil

  private def resolveTarget(spec: BuildSpec): Target =
    spec.main.target
      .orElse(spec.pkg.flatMap(_.ffi).flatMap(Target.parse))
      .getOrElse(Target.Python)

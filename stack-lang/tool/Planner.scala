package tool

import java.nio.file.Path

object Planner:
  /** Returns the build-cache version label for a resolved binary, e.g. "jo-1.2".
   *  Extracts MAJOR.MINOR from the compiler cache directory name (parent of joBin).
   *  Returns None for paths outside the cache (e.g. bare "jo" used in tests).
   */
  def joLabel(v: Version): String = s"jo-${v.major}.${v.minor}"

  /** Produce a BuildPlan from a resolved dependency graph. */
  def plan(graph: ResolvedGraph, joVersion: Version, joBin: Path, registryLibs: Map[String, Path]): BuildPlan =
    val root    = graph.root
    val rootDir = graph.rootDir

    val joVersionLabel = joLabel(joVersion)
    val allRegistryCheckLibs = registryLibs.toList.sortBy(_._1).map(_._2)
    val rootRegistryLinkLibs = root.main.dependencies.toList.flatMap:
      case (name, DepSpec(DepSource.Registry(_), DepLink.Link)) => registryLibs.get(name).toList
      case _ => Nil

    def sastDir(specDir: Path, spec: BuildSpec): Path =
      val base = specDir.resolve(s".build/${Graph.stemOf(spec)}")
      base.resolve(joVersionLabel).resolve("sast")

    // Dep lib builds — one per resolved dep that is a lib (has [package])
    val depBuilds: List[(String, CompilePlan.LibPlan)] = graph.deps.flatMap: dep =>
      if dep.spec.isLib then
        val sources      = SourceGlob.expand(dep.spec.main.src, dep.specDir)
        val depCheckLibs = checkLibsOf(dep.spec, graph.deps, sastDir) ++ allRegistryCheckLibs
        Some(dep.name ->
          CompilePlan.LibPlan(sources, depCheckLibs, sastDir(dep.specDir, dep.spec)))
      else
        None

    val checkLibs = graph.deps.collect:
      case d if d.link == DepLink.Check => sastDir(d.specDir, d.spec)

    val linkLibs  = graph.deps.collect:
      case d if d.link == DepLink.Link  => sastDir(d.specDir, d.spec)

    // Root build — use spec.name (same as deps) for consistent .build/<name>/ layout
    val rootBase  = rootDir.resolve(s".build/${root.name}").resolve(joVersionLabel)
    val mainPlan: CompilePlan =
      if root.isLib then
        val sources = SourceGlob.expand(root.main.src, rootDir)
        CompilePlan.LibPlan(sources, checkLibs ++ allRegistryCheckLibs, rootBase.resolve("sast"))
      else
        val sources = SourceGlob.expand(root.main.src, rootDir)
        val links   = root.main.links
        val target  = resolveTarget(root)
        CompilePlan.AppPlan(sources, checkLibs ++ allRegistryCheckLibs, linkLibs ++ rootRegistryLinkLibs, links, target,
          rootBase.resolve(s"target/${root.name}${target.ext}"),
          rootBase.resolve("sast"))

    // Test plan
    val rootSastDir = rootBase.resolve("sast")
    root.test match
      case None =>
        BuildPlan(root.name, joBin, depBuilds, mainPlan, Nil, None)

      case Some(testSpec) =>
        val testSources   = SourceGlob.expand(testSpec.src, rootDir, SourceGlob.defaultTestSrc)
        val testRegistryLinkLibs = testSpec.dependencies.toList.flatMap:
          case (name, DepSpec(DepSource.Registry(_), DepLink.Link)) => registryLibs.get(name).toList
          case _ => Nil
        val testCheckLibs = rootSastDir :: checkLibs ++ allRegistryCheckLibs ++
          graph.testDeps.collect:
            case d if d.link == DepLink.Check => sastDir(d.specDir, d.spec)
        val testLinkLibs  = linkLibs ++ rootRegistryLinkLibs ++ testRegistryLinkLibs ++
          graph.testDeps.collect:
            case d if d.link == DepLink.Link => sastDir(d.specDir, d.spec)

        val testLinks = root.main.links ++ testSpec.links
        val testTarget: Target = testSpec.target
          .orElse(root.main.target)
          .orElse(root.pkg.flatMap(_.ffi).flatMap(Target.parse))
          .getOrElse(Target.Python)

        val testOutFile = rootBase.resolve(s"target/${root.name}-test${testTarget.ext}")
        val testSastDir = rootBase.resolve("sast-test")

        val tDeps: List[(String, CompilePlan.LibPlan)] = graph.testDeps.map: dep =>
          val sources = SourceGlob.expand(dep.spec.main.src, dep.specDir)
          val depCheckLibs = checkLibsOf(dep.spec, graph.allDeps, sastDir) ++ allRegistryCheckLibs
          dep.name -> CompilePlan.LibPlan(sources, depCheckLibs, sastDir(dep.specDir, dep.spec))

        val testAppPlan: CompilePlan.AppPlan =
          CompilePlan.AppPlan(testSources, testCheckLibs, testLinkLibs, testLinks, testTarget, testOutFile, testSastDir)
        BuildPlan(root.name, joBin, depBuilds, mainPlan, tDeps, Some(testAppPlan))

  // ---- Helpers -------------------------------------------------------------

  /** Collect the compiled sast dirs for the check-deps of a given spec. */
  private def checkLibsOf(
    spec: BuildSpec, allDeps: List[ResolvedDep],
    sastDirOf: (Path, BuildSpec) => Path
  ): List[Path] =
    spec.main.dependencies.toList.flatMap: (name, dep) =>
      if dep.link == DepLink.Check then
        allDeps.find(d => d.name == name).map(d => sastDirOf(d.specDir, d.spec)).toList
      else Nil

  private def resolveTarget(spec: BuildSpec): Target =
    spec.main.target
      .orElse(spec.pkg.flatMap(_.ffi).flatMap(Target.parse))
      .getOrElse(Target.Python)

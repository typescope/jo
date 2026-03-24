package tool

import java.nio.file.Path

object Planner:
  /** Produce a BuildPlan from a resolved dependency graph.
   *  stemOverride allows specifying the spec file stem (defaults to package/app name). */
  def plan(graph: ResolvedGraph, stem: String, joBin: java.nio.file.Path): BuildPlan =
    val joLabel = JoResolver.joLabel(joBin)
    val root    = graph.root
    val rootDir = graph.rootDir

    def sastDir(specDir: Path, spec: BuildSpec): Path =
      val base = specDir.resolve(s".build/${Graph.stemOf(spec)}")
      joLabel.fold(base)(base.resolve).resolve("sast")

    // Dep lib builds — one per resolved dep that is a lib (has [package])
    val depBuilds: List[(String, RootBuild.LibBuild)] = graph.deps.flatMap: dep =>
      if dep.spec.isLib then
        val sources      = SourceGlob.expand(dep.spec.main.src, dep.specDir)
        val depCheckLibs = checkLibsOf(dep.spec, graph.deps, sastDir)
        Some(dep.name -> RootBuild.LibBuild(sources, depCheckLibs, sastDir(dep.specDir, dep.spec)))
      else
        None

    val checkLibs = graph.deps.collect { case d if d.link == DepLink.Check => sastDir(d.specDir, d.spec) }
    val linkLibs  = graph.deps.collect { case d if d.link == DepLink.Link  => sastDir(d.specDir, d.spec) }

    // Root build
    val rootBase  = joLabel.fold(rootDir.resolve(s".build/$stem"))(rootDir.resolve(s".build/$stem").resolve)
    val rootBuild: RootBuild = if root.isLib then
      val sources = SourceGlob.expand(root.main.src, rootDir)
      RootBuild.LibBuild(sources, checkLibs, rootBase.resolve("sast"))
    else
      val sources = SourceGlob.expand(root.main.src, rootDir)
      val links   = root.main.links
      val target  = resolveTarget(root)
      val ext     = targetExt(target)
      val appName = root.name
      RootBuild.AppBuild(sources, checkLibs, linkLibs, links, target,
        rootBase.resolve(s"target/$appName$ext"),
        rootBase.resolve("sast"))

    // Test build
    val rootSastDir = rootBase.resolve("sast")
    val testBuild: Option[TestBuild] = root.test.map: testSpec =>
      val testSources    = SourceGlob.expand(testSpec.src, rootDir, SourceGlob.defaultTestSrc)
      val testCheckLibs  = rootSastDir :: checkLibs ++
        graph.testDeps.collect { case d if d.link == DepLink.Check => d.sastDir }
      val testLinkLibs   = linkLibs ++
        graph.testDeps.collect { case d if d.link == DepLink.Link => d.sastDir }
      val testLinks      = root.main.links ++ testSpec.links
      val testTarget     = testSpec.target
        .orElse(root.main.target)
        .orElse(root.pkg.flatMap(_.ffi).filter(_ != "none"))
        .getOrElse("python")
      val testExt        = targetExt(testTarget)
      val testOutFile    = rootBase.resolve(s"target/${root.name}-test$testExt")
      val testSastDir    = rootBase.resolve("sast-test")
      val testDepBuilds: List[(String, RootBuild.LibBuild)] = graph.testDeps.map: dep =>
        val sources = SourceGlob.expand(dep.spec.main.src, dep.specDir)
        val depCheckLibs = checkLibsOf(dep.spec, graph.allDeps, sastDir)
        dep.name -> RootBuild.LibBuild(sources, depCheckLibs, dep.sastDir)
      TestBuild(
        testDepBuilds,
        RootBuild.AppBuild(testSources, testCheckLibs, testLinkLibs, testLinks, testTarget, testOutFile, testSastDir),
      )

    BuildPlan(joBin, depBuilds, rootBuild, testBuild)

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

  private def resolveTarget(spec: BuildSpec): String =
    spec.main.target
      .orElse(spec.pkg.flatMap(_.ffi).filter(_ != "none"))
      .getOrElse("python")

  private def targetExt(target: String): String = target match
    case "python" => ".py"
    case "js"     => ".js"
    case "ruby"   => ".rb"
    case _        => ""

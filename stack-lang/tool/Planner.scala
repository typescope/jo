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
      val s       = Graph.stemOf(specDir, spec)
      val segment = joLabel.map(l => s"/$l").getOrElse("")
      specDir.resolve(s".build/$s$segment/sast")

    // Dep lib builds — one per resolved dep that is a lib (has [package])
    val depBuilds: List[(String, RootBuild.LibBuild)] = graph.deps.flatMap: dep =>
      if dep.spec.isLib then
        val sources      = SourceGlob.expand(dep.spec.main.src, dep.specDir)
        val depCheckLibs = checkLibsOf(dep.spec, dep.specDir, graph, sastDir)
        Some(dep.name -> RootBuild.LibBuild(sources, depCheckLibs, sastDir(dep.specDir, dep.spec)))
      else
        None

    val checkLibs = graph.deps.collect { case d if d.link == DepLink.Check => sastDir(d.specDir, d.spec) }
    val linkLibs  = graph.deps.collect { case d if d.link == DepLink.Link  => sastDir(d.specDir, d.spec) }

    // Root build
    val segment   = joLabel.map(l => s"/$l").getOrElse("")
    val rootBuild: RootBuild = if root.isLib then
      val sources = SourceGlob.expand(root.main.src, rootDir)
      val outDir  = rootDir.resolve(s".build/$stem$segment/sast")
      RootBuild.LibBuild(sources, checkLibs, outDir)
    else
      val sources = SourceGlob.expand(root.main.src, rootDir)
      val links   = root.main.links
      val target  = resolveTarget(root)
      val ext     = targetExt(target)
      val appName = root.name.getOrElse(stem)
      val outFile = rootDir.resolve(s".build/$stem$segment/target/$appName$ext")
      RootBuild.AppBuild(sources, checkLibs, linkLibs, links, target, outFile)

    BuildPlan(joBin, depBuilds, rootBuild)

  // ---- Helpers -------------------------------------------------------------

  /** Collect the compiled sast dirs for the check-deps of a given spec. */
  private def checkLibsOf(
    spec: BuildSpec, specDir: Path, graph: ResolvedGraph,
    sastDirOf: (Path, BuildSpec) => Path
  ): List[Path] =
    spec.main.dependencies.toList.flatMap: (name, dep) =>
      if dep.link == DepLink.Check then
        graph.deps.find(d => d.name == name).map(d => sastDirOf(d.specDir, d.spec)).toList
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

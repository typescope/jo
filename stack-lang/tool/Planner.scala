package tool

import java.nio.file.Path

object Planner:
  /** Produce a BuildPlan from a resolved dependency graph.
   *  stemOverride allows specifying the spec file stem (defaults to package/app name). */
  def plan(graph: ResolvedGraph, stem: String): BuildPlan =
    val root    = graph.root
    val rootDir = graph.rootDir

    // Dep lib builds — one per resolved dep that is a lib (has [package])
    val depBuilds: List[(String, RootBuild.LibBuild)] = graph.deps.flatMap { dep =>
      if dep.spec.isLib then
        val sources = SourceGlob.expand(dep.spec.main.src, dep.specDir)
        // A dep's check libs are the check-deps of its own spec
        val depCheckLibs = checkLibsOf(dep.spec, dep.specDir, graph)
        Some(dep.name -> RootBuild.LibBuild(sources, depCheckLibs, dep.sastDir))
      else
        None
    }

    // Root build
    val rootBuild: RootBuild = if root.isLib then
      val sources   = SourceGlob.expand(root.main.src, rootDir)
      val checkLibs = graph.checkLibs
      val outDir    = rootDir.resolve(s".build/$stem/sast")
      RootBuild.LibBuild(sources, checkLibs, outDir)
    else
      val sources   = SourceGlob.expand(root.main.src, rootDir)
      val checkLibs = graph.checkLibs
      val linkLibs  = graph.linkLibs
      val links     = root.main.links
      val target    = resolveTarget(root)
      val ext       = targetExt(target)
      val appName   = root.name.getOrElse(stem)
      val outFile   = rootDir.resolve(s".build/$stem/target/$appName$ext")
      RootBuild.AppBuild(sources, checkLibs, linkLibs, links, target, outFile)

    BuildPlan(depBuilds, rootBuild)

  // ---- Helpers -------------------------------------------------------------

  /** Collect the compiled sast dirs for the check-deps of a given spec. */
  private def checkLibsOf(spec: BuildSpec, specDir: Path, graph: ResolvedGraph): List[Path] =
    spec.main.dependencies.toList.flatMap { (name, dep) =>
      if dep.link == DepLink.Check then
        graph.deps.find(d => d.name == name).map(_.sastDir).toList
      else Nil
    }

  private def resolveTarget(spec: BuildSpec): String =
    spec.main.target
      .orElse(spec.pkg.flatMap(_.ffi).filter(_ != "none"))
      .getOrElse("python")

  private def targetExt(target: String): String = target match
    case "python" => ".py"
    case "js"     => ".js"
    case "ruby"   => ".rb"
    case _        => ""

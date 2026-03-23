package tool

import java.nio.file.Path

/** Compile a set of sources into .sast files (library build). */
case class LibBuild(
  sources: List[Path],
  checkLibs: List[Path],
  outDir: Path,
)

/** Compile a set of sources into a runnable output (app build). */
case class AppBuild(
  sources: List[Path],
  checkLibs: List[Path],
  linkLibs: List[Path],
  links: Map[String, String],
  target: String,             // "python" | "js" | "ruby" | "native"
  outFile: Path,
)

/** Full build plan: compile each dep lib in order, then build the root. */
case class BuildPlan(
  depBuilds: List[(String, LibBuild)],    // (dep name, lib build) — topological order
  rootBuild: Either[LibBuild, AppBuild],  // Left = lib, Right = app
)

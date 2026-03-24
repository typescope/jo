package tool

import java.nio.file.Path

enum RootBuild:
  /** Compile sources into .sast files (library build). */
  case LibBuild(
    sources: List[Path],
    checkLibs: List[Path],
    outDir: Path,
  )
  /** Compile sources into a runnable output (app build). */
  case AppBuild(
    sources: List[Path],
    checkLibs: List[Path],
    linkLibs: List[Path],
    links: Map[String, String],
    target: String,             // "python" | "js" | "ruby" | "native"
    outFile: Path,
    sastDir: Path,              // .build/<name>/jo-<version>/sast/ — compiled alongside the executable
  )

/** Test build: additional test-only dep libs plus the test app. */
case class TestBuild(
  testDepBuilds: List[(String, RootBuild.LibBuild)],   // test-only dep libs
  appBuild: RootBuild.AppBuild,                        // test executable
)

/** Full build plan: compile each dep lib in order, then build the root. */
case class BuildPlan(
  joBin: java.nio.file.Path,                       // resolved jo binary for this build
  depBuilds: List[(String, RootBuild.LibBuild)],   // (dep name, lib build) — topological order
  rootBuild: RootBuild,
  testBuild: Option[TestBuild] = None,
)

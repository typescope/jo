package tool

import java.nio.file.Path

enum CompilePlan:
  /** Compile sources into .sast files (library build). */
  case LibPlan(
    sources: List[Path],
    checkLibs: List[Path],
    outDir: Path,
  )
  /** Compile sources into a runnable output (app build). */
  case AppPlan(
    sources: List[Path],
    checkLibs: List[Path],
    linkLibs: List[Path],
    links: Map[String, String],
    target: Target,
    outFile: Path,
    sastDir: Path,              // .build/<name>/jo-<version>/sast/ — compiled alongside the executable
  )

/** Full build plan: compile each dep lib in order, then build the root. */
case class BuildPlan(
  name: String,                                          // root project name
  joBin: java.nio.file.Path,                            // resolved jo binary for this build
  depBuilds: List[(String, CompilePlan.LibPlan)],        // main dep libs — topological order
  mainPlan: CompilePlan,
  testDepBuilds: List[(String, CompilePlan.LibPlan)] = Nil,  // test-only dep libs
  testPlan: Option[CompilePlan.AppPlan] = None,
)

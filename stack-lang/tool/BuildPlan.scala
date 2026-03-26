package tool

import java.nio.file.Path

enum CompileTask:
  /** Compile sources into .sast files (library build). */
  case LibTask(
    sources: List[Path],
    checkLibs: List[Path],
    outDir: Path,
    compileOptions: List[String] = Nil,
  )
  /** Compile sources into a runnable output (app build).
   *  Also writes .sast files alongside — used by the test build for type-checking. */
  case AppTask(
    sources: List[Path],
    checkLibs: List[Path],
    linkLibs: List[Path],
    links: Map[String, String],
    target: Target,
    outFile: Path,
    sastDir: Path,
  )

enum ModuleKind:
  case Main
  case Test

/** Build plan for a single module: execute dep modules first, then compile this module's task. */
case class ModulePlan(
  projectName: String,
  task: CompileTask,
  deps: List[ModulePlan],
)

/** Build plans for a project: always has a main module, optionally a test module. */
case class ProjectPlan(main: ModulePlan, test: Option[ModulePlan])

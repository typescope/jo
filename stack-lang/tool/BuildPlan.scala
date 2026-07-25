package tool

import java.nio.file.Path

/** A resource file after expanding a module's resource mappings.
 *
 *  @param inputFile the concrete file on disk to copy from
 *  @param resourcePath the slash-separated path used inside package/app resource output
 *  @param sourceArchivePath the original source-relative path preserved in source archives
 */
case class ResourceFile(inputFile: Path, resourcePath: Path, sourceArchivePath: Path)

case class ResourceGroup(owner: String, files: List[ResourceFile])

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
    resources: List[ResourceGroup],
    compileOptions: List[String],
  )

/** Build plan for a single module: execute dep modules first, then compile this module's task. */
case class ModulePlan(
  moduleLabel: String,
  module: ModuleId,
  joBin: Path,
  task: CompileTask,
  deps: List[ModulePlan],
)

/** Build plans for selected modules in a project, preserving selection order. */
case class ProjectPlan(modules: List[ModulePlan])

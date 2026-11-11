package tool

import java.nio.file.{Files, Path}
import scala.collection.mutable.{ArrayBuffer, Set}
import scala.jdk.CollectionConverters.*

/** Executes module plans by invoking `jo compile` subprocesses.
 *
 *  Each entry point walks the dependency graph of a plan and handles every module once:
 *
 *  - `build` compiles libraries to `.sast` files and applications to their output file.
 *    `jo build`, `jo run`, `jo test`, and `jo release` use it.
 *  - `check` type-checks modules. Libraries are compiled as by `build`, applications are
 *    type-checked without linking or generating code. `jo check` uses it.
 *  - `doc` type-checks the dependencies of a module and generates its documentation.
 *
 *  Every compilation writes a sentinel file that records its command line. A module whose
 *  sources, dependency outputs, and command line are unchanged since the sentinel was
 *  written is skipped.
 *
 *  `runInteractive` and `execute` run a built application.
 */
object Runner:
  private val checkOnlyFlag = "--check-only"

  private def moduleLabel(plan: ModulePlan): String =
    plan.moduleLabel

  /** Build a module after building its dependencies. A library is compiled to `.sast`
   *  files, an application to its output file. */
  def build(plan: ModulePlan)(using Logger): Result[Unit] =
    buildOnce(plan, Set.empty)

  private def buildOnce(plan: ModulePlan, visited: Set[ModuleKey])(using Logger): Result[Unit] =
    if !visited.add(plan.key) then return Result.unit

    val jo = plan.joBin.toString
    val it = plan.deps.iterator
    while it.hasNext do
      buildOnce(it.next(), visited) match
        case Result.Err(msg) => return Result.Err(msg)
        case _ =>
    plan.task match
      case lib: CompileTask.LibTask =>
        info(s"[build] ${moduleLabel(plan)}\n")
        buildLib(lib, jo, sentinelFile = dirSentinel(lib.outDir))

      case app: CompileTask.AppTask =>
        if app.compileOptions.contains(checkOnlyFlag) then
          return Result.Err(s"remove $checkOnlyFlag from compile-options to build ${moduleLabel(plan)}, or use 'jo check'")

        info(s"[build] ${moduleLabel(plan)}\n")
        buildApp(app, jo).flatMap: _ =>
          syncResources(app).map: _ =>
            info(s"[output] ${Logger.relativize(app.outFile)}\n")

  /** Type-check a module after type-checking its dependencies. A library is compiled to
   *  `.sast` files as by `build`. An application is compiled with `--check-only`, which
   *  writes its `.sast` files but neither links the entry point nor generates code. */
  def check(plan: ModulePlan)(using Logger): Result[Unit] =
    checkOnce(plan, Set.empty)

  private def checkOnce(plan: ModulePlan, visited: Set[ModuleKey])(using Logger): Result[Unit] =
    if !visited.add(plan.key) then return Result.unit

    val jo = plan.joBin.toString
    val it = plan.deps.iterator
    while it.hasNext do
      checkOnce(it.next(), visited) match
        case Result.Err(msg) => return Result.Err(msg)
        case _ =>
    info(s"[check] ${moduleLabel(plan)}\n")
    plan.task match
      case lib: CompileTask.LibTask =>
        buildLib(lib, jo, sentinelFile = dirSentinel(lib.outDir))
      case app: CompileTask.AppTask =>
        checkApp(app, jo)

  /** Generate the documentation of a module into `outDir` after type-checking its
   *  dependencies. `task` compiles the module's sources as a library with the
   *  documentation options. */
  def doc(plan: ModulePlan, task: CompileTask.LibTask, outDir: Path)(using Logger): Result[Unit] =
    val jo = plan.joBin.toString
    val visited = Set.empty[ModuleKey]
    val it = plan.deps.iterator
    while it.hasNext do
      checkOnce(it.next(), visited) match
        case Result.Err(msg) => return Result.Err(msg)
        case _ =>
    info(s"[doc] ${moduleLabel(plan)}\n")
    val docIndex = outDir.resolve("index.html")
    buildLib(task, jo, sentinelFile = dirSentinel(outDir), requiredOutputs = List(docIndex))

  /** Execute the compiled app interactively: stdin/stdout/stderr inherit from
   *  the parent process, so the app sees a real terminal (a TTY). This is what
   *  lets interactive programs use readline, ANSI/isatty-gated output, etc.
   *  Output is not captured here; on a non-zero exit it has already been shown.
   */
  def runInteractive(app: CompileTask.AppTask, appArgs: List[String]): Result[Unit] =
    val cmd = app.target.interpreter :: app.outFile.toString :: appArgs
    val pb = ProcessBuilder(cmd.asJava)
    pb.redirectInput(ProcessBuilder.Redirect.INHERIT)
    pb.redirectOutput(ProcessBuilder.Redirect.INHERIT)
    pb.redirectError(ProcessBuilder.Redirect.INHERIT)
    val proc = pb.start()
    val exit = proc.waitFor()
    if exit != 0 then Result.Err(s"program exited with status $exit") else Result.unit

  /** Execute the compiled app, capturing and returning stdout.
   *
   *  Used by the test harness to compare output against expected strings.
   *  Returns Ok(stdout) on success, Err(output) on non-zero exit.
   */
  def execute(app: CompileTask.AppTask, appArgs: List[String]): Result[String] =
    val cmd = app.target.interpreter :: app.outFile.toString :: appArgs
    val pb = ProcessBuilder(cmd.asJava)
    pb.redirectErrorStream(true)
    val proc = pb.start()
    val out  = String(proc.getInputStream.readAllBytes(), "UTF-8")
    val exit = proc.waitFor()
    if exit != 0 then Result.Err(out) else Result.Ok(out)

  /** Compile a library to `.sast` files, unless they are up to date. */
  private def buildLib(
    lib: CompileTask.LibTask,
    jo: String,
    sentinelFile: Path,
    requiredOutputs: List[Path] = Nil,
  )(using Logger): Result[Unit] =
    runSast(lib.outDir, lib.sources, lib.checkLibs, buildLibArgs(lib, jo), sentinelFile, requiredOutputs)

  /** Type-check an application with `--check-only`, writing its `.sast` files, unless they
   *  are up to date. */
  private def checkApp(app: CompileTask.AppTask, jo: String)(using Logger): Result[Unit] =
    val lib: CompileTask.LibTask = CompileTask.LibTask(app.sources, app.checkLibs, app.sastDir, app.defaultSourceRoot, app.compileOptions)
    val backendArgs = buildLibArgs(lib, jo) :+ s"--${app.target.flag}"
    val args = if app.compileOptions.contains(checkOnlyFlag) then backendArgs else backendArgs :+ checkOnlyFlag
    runSast(app.sastDir, app.sources, app.checkLibs, args, dirSentinel(app.sastDir))

  /** Run a `--sast` compilation into `outDir`, unless its outputs are up to date. */
  private def runSast(
    outDir: Path,
    sources: List[Path],
    checkLibs: List[Path],
    args: List[String],
    sentinel: Path,
    requiredOutputs: List[Path] = Nil,
  )(using Logger): Result[Unit] =
    if Files.isDirectory(outDir) && requiredOutputs.forall(Files.exists(_)) && isUpToDate(sources, checkLibs, Nil, sentinel, args) then
      return Result.unit

    recreateDir(outDir)
    exec(args) match
      case ok @ Result.Ok(_) =>
        Files.writeString(sentinel, fingerprint(args))
        ok
      case err => err

  /** Compile and link an application to its output file, unless it is up to date. */
  private def buildApp(app: CompileTask.AppTask, jo: String)(using Logger): Result[Unit] =
    val sentinel = appSentinel(app)
    val args = buildAppArgs(app, jo)
    if Files.exists(app.outFile) && Files.isDirectory(app.sastDir) && isUpToDate(app.sources, app.checkLibs, app.linkLibs, sentinel, args) then return Result.unit
    Files.createDirectories(app.outFile.getParent)
    recreateDir(app.sastDir)
    exec(args) match
      case ok @ Result.Ok(_) =>
        Files.writeString(sentinel, fingerprint(args))
        ok
      case err => err

  private def syncResources(app: CompileTask.AppTask): Result[Unit] =
    val resourceDir = app.outFile.getParent.resolve("resources")
    try
      ResourcePaths.syncGroups(app.resources, resourceDir)
    catch
      case e: java.io.IOException => Result.Err(s"could not sync resources: ${e.getMessage}")

  private def appSentinel(app: CompileTask.AppTask): Path =
    app.outFile.resolveSibling(app.outFile.getFileName.toString + ".done")

  private def dirSentinel(dir: Path): Path =
    dir.resolveSibling(dir.getFileName.toString + ".done")

  private def recreateDir(dir: Path): Unit =
    if Files.exists(dir) then deleteDir(dir)
    Files.createDirectories(dir)

  private def buildLibArgs(lib: CompileTask.LibTask, jo: String): List[String] =
    val args = ArrayBuffer[String]()
    args += jo
    args += "compile"
    args += "--sast"
    args += lib.outDir.toString
    lib.compileOptions.foreach(args += _)
    if !lib.compileOptions.contains("--source-root") then
      args += "--source-root"
      args += lib.defaultSourceRoot.toString
    lib.sources.foreach(args += _.toString)
    lib.checkLibs.foreach: l =>
      args += "--lib"
      args += l.toString
    args.toList

  private def buildAppArgs(app: CompileTask.AppTask, jo: String): List[String] =
    val args = ArrayBuffer[String]()
    args += jo
    args += "compile"
    args += s"--${app.target.flag}"
    args += "--sast"
    args += app.sastDir.toString
    app.compileOptions.foreach(args += _)
    if !app.compileOptions.contains("--source-root") then
      args += "--source-root"
      args += app.defaultSourceRoot.toString
    app.sources.foreach(args += _.toString)
    app.checkLibs.foreach: l =>
      args += "--lib"
      args += l.toString
    app.linkLibs.foreach: l =>
      args += "--link-lib"
      args += l.toString
    app.links.toSeq.sortBy(_._1).foreach: (k, v) =>
      args += "--link"
      args += s"$k=$v"
    args += "-o"
    args += app.outFile.toString
    args.toList

  /** True if sentinel exists with matching fingerprint and is newer than all sources and dep sentinels.
   *  The fingerprint records the full compile command; if any argument changes (compile-options,
   *  sources list, libs, target, links), the mismatch forces a rebuild. */
  private def isUpToDate(
    sources: List[Path], checkLibs: List[Path], linkLibs: List[Path], sentinel: Path,
    args: List[String],
  ): Boolean =
    if !Files.exists(sentinel) then return false
    if Files.readString(sentinel) != fingerprint(args) then return false
    val sentinelTime = Files.getLastModifiedTime(sentinel)
    def olderThanSentinel(p: Path): Boolean =
      Files.exists(p) && Files.getLastModifiedTime(p).compareTo(sentinelTime) <= 0
    sources.forall(olderThanSentinel) &&
    (checkLibs ++ linkLibs).forall: libDir =>
      val done = dirSentinel(libDir)
      olderThanSentinel(if Files.exists(done) then done else libDir)

  private def fingerprint(args: List[String]): String = args.mkString("\n")

  /** Run a subprocess. Returns Err with compiler output on non-zero exit. */
  private def exec(args: List[String])(using Logger): Result[Unit] =
    log(s"[cmd] ${args.mkString(" ")}\n")
    val pb = ProcessBuilder(args.asJava)
    pb.redirectErrorStream(true)
    val proc = pb.start()
    val out  = String(proc.getInputStream.readAllBytes(), "UTF-8")
    val exit = proc.waitFor()
    if exit != 0 then Result.Err(out) else Result.unit

  private def log(msg: String)(using Logger): Unit  = Logger.log(msg)
  private def info(msg: String)(using Logger): Unit = Logger.info(msg)

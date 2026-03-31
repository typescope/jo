package tool

import java.nio.file.{Files, Path}
import scala.collection.mutable.ArrayBuffer
import scala.jdk.CollectionConverters.*

/** Executes build plans by invoking `jo compile` subprocesses. */
object Runner:
  private def moduleLabel(plan: ModulePlan): String =
    val module = plan.module match
      case ModuleKind.Main => "main"
      case ModuleKind.Test => "test"
    s"${plan.projectName}.$module"

  /** Build a module: recursively build deps, then compile this module's task. */
  def run(plan: ModulePlan, joBin: Path, action: String = "build")(using Logger): Result[Unit] =
    val jo = joBin.toString
    val it = plan.deps.iterator
    while it.hasNext do
      run(it.next(), joBin) match
        case Result.Err(msg) => return Result.Err(msg)
        case _ =>
    plan.task match
      case lib: CompileTask.LibTask =>
        info(s"[$action] ${moduleLabel(plan)}\n")
        runLib(lib, jo, sentinelFile = dirSentinel(lib.outDir))
      case app: CompileTask.AppTask =>
        info(s"[$action] ${moduleLabel(plan)}\n")
        runLib(
          CompileTask.LibTask(app.sources, app.checkLibs, app.sastDir, app.compileOptions),
          jo,
          sentinelFile = dirSentinel(app.sastDir),
        ) match
          case err @ Result.Err(_) => err
          case _ =>
            runApp(app, jo).map: _ =>
              info(s"[output] ${LogFormat.path(app.outFile)}\n")

  /** Type-check only: compile everything as libs (--sast), skip app link step. */
  def check(plan: ModulePlan, joBin: Path, action: String)(using Logger): Result[Unit] =
    val jo = joBin.toString
    val it = plan.deps.iterator
    while it.hasNext do
      check(it.next(), joBin, action) match
        case Result.Err(msg) => return Result.Err(msg)
        case _ =>
    info(s"[$action] ${moduleLabel(plan)}\n")
    plan.task match
      case lib: CompileTask.LibTask =>
        runLib(lib, jo, sentinelFile = dirSentinel(lib.outDir))
      case app: CompileTask.AppTask =>
        runLib(
          CompileTask.LibTask(app.sources, app.checkLibs, app.sastDir, app.compileOptions),
          jo,
          sentinelFile = dirSentinel(app.sastDir),
        )

  def doc(plan: ModulePlan, joBin: Path, outDir: Path)(using Logger): Result[Unit] =
    val jo = joBin.toString
    val it = plan.deps.iterator
    while it.hasNext do
      check(it.next(), joBin, "check") match
        case Result.Err(msg) => return Result.Err(msg)
        case _ =>
    info(s"[doc] ${moduleLabel(plan)}\n")
    val docIndex = outDir.resolve("index.html")
    plan.task match
      case lib: CompileTask.LibTask =>
        runLib(lib, jo, sentinelFile = dirSentinel(outDir), requiredOutputs = List(docIndex))
      case app: CompileTask.AppTask =>
        runLib(
          CompileTask.LibTask(app.sources, app.checkLibs, app.sastDir, app.compileOptions),
          jo,
          sentinelFile = dirSentinel(outDir),
          requiredOutputs = List(docIndex),
        )

  /** Build the test module (which includes main as a lib dep), then run tests. */
  def test(testOpt: Option[ModulePlan], joBin: Path)(using Logger): Result[Unit] =
    testOpt match
      case None =>
        info("no tests defined\n")
        Result.unit
      case Some(tp) =>
        run(tp, joBin).flatMap: _ =>
          info(s"[test] ${tp.projectName}\n")
          tp.task match
            case app: CompileTask.AppTask => runInteractive(app, Nil)
            case _ => Result.Err("test module task must be an AppTask")

  /** Execute the compiled app interactively: stdout/stderr/stdin inherit from the parent process. */
  def runInteractive(app: CompileTask.AppTask, appArgs: List[String]): Result[Unit] =
    val cmd = app.target.interpreter :: app.outFile.toString :: appArgs
    val pb = ProcessBuilder(cmd.asJava)
    pb.redirectErrorStream(true)
    pb.redirectInput(ProcessBuilder.Redirect.INHERIT)
    val proc = pb.start()
    val in = proc.getInputStream
    val out = new java.io.ByteArrayOutputStream
    val chunk = new Array[Byte](8192)
    var n = in.read(chunk)
    while n != -1 do
      System.out.write(chunk, 0, n)
      System.out.flush()
      out.write(chunk, 0, n)
      n = in.read(chunk)
    val exit = proc.waitFor()
    if exit != 0 then Result.Err(out.toString("UTF-8")) else Result.unit

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

  private def runLib(
    lib: CompileTask.LibTask,
    jo: String,
    sentinelFile: Path,
    requiredOutputs: List[Path] = Nil,
  )(using Logger): Result[Unit] =
    val sentinel = sentinelFile
    val args = buildLibArgs(lib, jo)
    if requiredOutputs.forall(Files.exists(_)) && isUpToDate(lib.sources, lib.checkLibs, Nil, sentinel, args) then
      return Result.unit
    Files.createDirectories(lib.outDir)
    exec(args) match
      case ok @ Result.Ok(_) =>
        Files.writeString(sentinel, fingerprint(args))
        ok
      case err => err

  private def runApp(app: CompileTask.AppTask, jo: String)(using Logger): Result[Unit] =
    val sentinel = appSentinel(app)
    val args = buildAppArgs(app, jo)
    if Files.exists(app.outFile) && isUpToDate(app.sources, app.checkLibs, app.linkLibs, sentinel, args) then return Result.unit
    Files.createDirectories(app.outFile.getParent)
    Files.createDirectories(app.sastDir)
    exec(args) match
      case ok @ Result.Ok(_) =>
        Files.writeString(sentinel, fingerprint(args))
        ok
      case err => err

  private def appSentinel(app: CompileTask.AppTask): Path =
    app.outFile.resolveSibling(app.outFile.getFileName.toString + ".done")

  private def dirSentinel(dir: Path): Path =
    dir.resolveSibling(dir.getFileName.toString + ".done")

  private def buildLibArgs(lib: CompileTask.LibTask, jo: String): List[String] =
    val args = ArrayBuffer[String]()
    args += jo
    args += "compile"
    args += "--sast"
    args += lib.outDir.toString
    lib.compileOptions.foreach(args += _)
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

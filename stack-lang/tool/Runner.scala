package tool

import java.nio.file.{Files, Path}
import scala.collection.mutable.ArrayBuffer
import scala.jdk.CollectionConverters.*

/** Executes build plans by invoking `jo compile` subprocesses. */
object Runner:
  /** Build a module: recursively build deps, then compile this module's task. */
  def run(plan: ModulePlan, joBin: Path)(using Logger): Result[Unit] =
    val jo = joBin.toString
    val it = plan.deps.iterator
    while it.hasNext do
      run(it.next(), joBin) match
        case Result.Err(msg) => return Result.Err(msg)
        case _ =>
    plan.task match
      case lib: CompileTask.LibTask =>
        info(s"[build] ${plan.projectName}\n")
        runLib(lib, jo)
      case app: CompileTask.AppTask =>
        info(s"[build] ${plan.projectName}\n")
        runLib(CompileTask.LibTask(app.sources, app.checkLibs, app.sastDir), jo) match
          case err @ Result.Err(_) => err
          case _ =>
            runApp(app, jo).map: _ =>
              info(s"[output] ${app.outFile}\n")

  /** Type-check only: compile everything as libs (--sast), skip app link step. */
  def check(plan: ModulePlan, joBin: Path)(using Logger): Result[Unit] =
    val jo = joBin.toString
    val it = plan.deps.iterator
    while it.hasNext do
      check(it.next(), joBin) match
        case Result.Err(msg) => return Result.Err(msg)
        case _ =>
    info(s"[check] ${plan.projectName}\n")
    plan.task match
      case lib: CompileTask.LibTask =>
        runLib(lib, jo)
      case app: CompileTask.AppTask =>
        runLib(CompileTask.LibTask(app.sources, app.checkLibs, app.sastDir), jo)

  /** Build the test module (which includes main as a lib dep), then run tests. */
  def test(testOpt: Option[ModulePlan], joBin: Path)(using Logger): Result[Unit] =
    testOpt match
      case None =>
        info("no tests defined\n")
        Result.unit
      case Some(tp) =>
        run(tp, joBin).flatMap: _ =>
          info("[test] run\n")
          tp.task match
            case app: CompileTask.AppTask => execute(app, Nil).map(_ => ())
            case _ => Result.Err("test module task must be an AppTask")

  /** Execute the compiled app interactively: stdout/stderr/stdin inherit from the parent process. */
  def runInteractive(app: CompileTask.AppTask, appArgs: List[String]): Result[Unit] =
    val cmd = app.target.interpreter :: app.outFile.toString :: appArgs
    val pb = ProcessBuilder(cmd.asJava)
    pb.inheritIO()
    val exit = pb.start().waitFor()
    if exit != 0 then Result.Err("") else Result.unit

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

  private def runLib(lib: CompileTask.LibTask, jo: String)(using Logger): Result[Unit] =
    val sentinel = lib.outDir.resolve(".done")
    if isUpToDate(lib.sources, lib.checkLibs, Nil, sentinel) then return Result.unit
    Files.createDirectories(lib.outDir)
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

    exec(args.toList) match
      case ok @ Result.Ok(_) =>
        Files.write(sentinel, Array.emptyByteArray)
        ok

      case err => err

  private def runApp(app: CompileTask.AppTask, jo: String)(using Logger): Result[Unit] =
    if isUpToDate(app.sources, app.checkLibs, app.linkLibs, app.outFile) then return Result.unit
    Files.createDirectories(app.outFile.getParent)
    Files.createDirectories(app.sastDir)
    val args = ArrayBuffer[String]()
    args += jo
    args += "compile"
    args += s"--${app.target.flag}"
    args += "--sast"
    args += app.sastDir.toString
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
    exec(args.toList)

  /** True if sentinel exists and is newer than all sources and dep sentinels. */
  private def isUpToDate(
    sources: List[Path], checkLibs: List[Path], linkLibs: List[Path], sentinel: Path
  ): Boolean =
    if !Files.exists(sentinel) then return false
    val sentinelTime = Files.getLastModifiedTime(sentinel)
    def olderThanSentinel(p: Path): Boolean =
      Files.exists(p) && Files.getLastModifiedTime(p).compareTo(sentinelTime) <= 0
    sources.forall(olderThanSentinel) &&
    (checkLibs ++ linkLibs).forall: libDir =>
      val done = libDir.resolve(".done")
      olderThanSentinel(if Files.exists(done) then done else libDir)

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

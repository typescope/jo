package tool

import java.nio.file.{Files, Path}
import scala.collection.mutable.ArrayBuffer
import scala.jdk.CollectionConverters.*

trait Logger:
  def log(msg: String): Unit

object Logger:
  /** Default: route build output to stderr. */
  val stderr: Logger = msg => Console.err.print(msg)
  given Logger = stderr

/** Executes a BuildPlan by invoking `jo compile` subprocesses. */
object Runner:
  def run(plan: BuildPlan)(using Logger): Unit =
    val jo = plan.joBin.toString

    for (name, lib) <- plan.depBuilds do
      log(s"[build] $name\n")
      runLib(lib, jo)

    plan.mainPlan match
      case lib: CompilePlan.LibPlan =>
        log("[build] root (lib)\n")
        runLib(lib, jo)
      case app: CompilePlan.AppPlan =>
        log("[build] root (app)\n")
        runLib(CompilePlan.LibPlan(app.sources, app.checkLibs, app.sastDir), jo)
        runApp(app, jo)

  /** Type-check only: compile everything as libs (--sast), skip app link step. */
  def check(plan: BuildPlan)(using Logger): Unit =
    val jo = plan.joBin.toString

    for (name, lib) <- plan.depBuilds do
      log(s"[check] $name\n")
      runLib(lib, jo)

    plan.mainPlan match
      case lib: CompilePlan.LibPlan =>
        log("[check] root\n")
        runLib(lib, jo)
      case app: CompilePlan.AppPlan =>
        log("[check] root\n")
        runLib(CompilePlan.LibPlan(app.sources, app.checkLibs, app.sastDir), jo)

  /** Build all deps, root lib, test deps, and test app — without executing.
   *  Returns the test app plan, or None if no test is defined. */
  def buildForTest(plan: BuildPlan)(using Logger): Option[CompilePlan.AppPlan] =
    val jo = plan.joBin.toString

    for (name, lib) <- plan.depBuilds do
      log(s"[build] $name\n")
      runLib(lib, jo)

    val rootLibBuild: CompilePlan.LibPlan = plan.mainPlan match
      case lib: CompilePlan.LibPlan => lib
      case app: CompilePlan.AppPlan => CompilePlan.LibPlan(app.sources, app.checkLibs, app.sastDir)
    log("[build] root\n")
    runLib(rootLibBuild, jo)

    plan.testPlan match
      case None => None
      case Some(tp) =>
        for (name, lib) <- plan.testDepBuilds do
          log(s"[build] $name (test)\n")
          runLib(lib, jo)
        log("[test] build\n")
        runApp(tp, jo)
        Some(tp)

  /** Build all deps and root as lib, then build and run the test app. */
  def test(plan: BuildPlan)(using Logger): Unit =
    val jo = plan.joBin.toString

    for (name, lib) <- plan.depBuilds do
      log(s"[build] $name\n")
      runLib(lib, jo)

    val rootLibBuild: CompilePlan.LibPlan = plan.mainPlan match
      case lib: CompilePlan.LibPlan => lib
      case app: CompilePlan.AppPlan => CompilePlan.LibPlan(app.sources, app.checkLibs, app.sastDir)
    log("[build] root\n")
    runLib(rootLibBuild, jo)

    plan.testPlan match
      case None =>
        log("no tests defined\n")
      case Some(tp) =>
        for (name, lib) <- plan.testDepBuilds do
          log(s"[build] $name (test)\n")
          runLib(lib, jo)
        log("[test] build\n")
        runApp(tp, jo)
        log("[test] run\n")
        execute(tp, Nil)

  /** Execute the compiled app, forwarding appArgs.  Output goes to Console.out (capturable). */
  def execute(app: CompilePlan.AppPlan, appArgs: List[String]): Unit =
    val cmd = app.target match
      case "python" => "python3" :: app.outFile.toString :: appArgs
      case "js"     => "node"    :: app.outFile.toString :: appArgs
      case "ruby"   => "ruby"    :: app.outFile.toString :: appArgs
      case _        =>              app.outFile.toString :: appArgs
    val pb = ProcessBuilder(cmd.asJava)
    pb.redirectErrorStream(true)
    val proc = pb.start()
    val out  = String(proc.getInputStream.readAllBytes(), "UTF-8")
    val exit = proc.waitFor()
    if out.nonEmpty then print(out)    // Console.out — captured by captureResult
    if exit != 0 then throw ToolError(s"execution failed (exit $exit)")

  private def runLib(lib: CompilePlan.LibPlan, jo: String)(using Logger): Unit =
    val sentinel = lib.outDir.resolve(".done")
    if isUpToDate(lib.sources, lib.checkLibs, Nil, sentinel) then return
    Files.createDirectories(lib.outDir)
    val args = ArrayBuffer[String]()
    args += jo
    args += "compile"
    args += "--sast"
    args += lib.outDir.toString
    lib.sources.foreach(args += _.toString)
    lib.checkLibs.foreach { l => args += "--lib"; args += l.toString }
    exec(args.toList)
    Files.write(sentinel, Array.emptyByteArray)

  private def runApp(app: CompilePlan.AppPlan, jo: String)(using Logger): Unit =
    if isUpToDate(app.sources, app.checkLibs, app.linkLibs, app.outFile) then return
    Files.createDirectories(app.outFile.getParent)
    Files.createDirectories(app.sastDir)
    val args = ArrayBuffer[String]()
    args += jo
    args += "compile"
    args += s"--${app.target}"
    args += "--sast"
    args += app.sastDir.toString
    app.sources.foreach(args += _.toString)
    app.checkLibs.foreach { l => args += "--lib"; args += l.toString }
    app.linkLibs.foreach { l => args += "--link-lib"; args += l.toString }
    app.links.toSeq.sortBy(_._1).foreach { (k, v) => args += "--link"; args += s"$k=$v" }
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

  /** Run a subprocess; route stdout+stderr through the logger.  Throws on non-zero exit. */
  private def exec(args: List[String])(using logger: Logger): Unit =
    val pb = ProcessBuilder(args.asJava)
    pb.redirectErrorStream(true)
    val proc = pb.start()
    val out  = String(proc.getInputStream.readAllBytes(), "UTF-8")
    val exit = proc.waitFor()
    if out.nonEmpty then logger.log(out)
    if exit != 0 then throw ToolError(s"command failed (exit $exit)")

  private def log(msg: String)(using logger: Logger): Unit = logger.log(msg)

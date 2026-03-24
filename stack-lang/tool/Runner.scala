package tool

import java.nio.file.{Files, Path}
import scala.collection.mutable.ArrayBuffer
import scala.jdk.CollectionConverters.*

enum Result[+A]:
  case Ok(value: A)
  case Err(output: String)

  def map[B](f: A => B): Result[B] = this match
    case Ok(v)  => Ok(f(v))
    case Err(o) => Err(o)

  def flatMap[B](f: A => Result[B]): Result[B] = this match
    case Ok(v)  => f(v)
    case Err(o) => Err(o)

object Result:
  val unit: Result[Unit] = Ok(())

trait Logger:
  def log(msg: String): Unit
  def error(msg: String): Unit

object Logger:
  val stderr: Logger = new Logger:
    def log(msg: String): Unit   = Console.err.print(msg)
    def error(msg: String): Unit = Console.err.print(msg)
  given Logger = stderr

  def log(msg: String)(using l: Logger): Unit   = l.log(msg)
  def error(msg: String)(using l: Logger): Unit = l.error(msg)

/** Executes a BuildPlan by invoking `jo compile` subprocesses. */
object Runner:
  def run(plan: BuildPlan)(using Logger): Result[Unit] =
    val jo = plan.joBin.toString

    val it = plan.depBuilds.iterator
    while it.hasNext do
      val (name, lib) = it.next()
      log(s"[build] $name\n")
      runLib(lib, jo) match
        case Result.Err(msg) => return Result.Err(msg)
        case _ =>

    plan.mainPlan match
      case lib: CompilePlan.LibPlan =>
        log("[build] root (lib)\n")
        runLib(lib, jo)

      case app: CompilePlan.AppPlan =>
        log("[build] root (app)\n")
        runLib(CompilePlan.LibPlan(app.sources, app.checkLibs, app.sastDir), jo) match
          case err @ Result.Err(_) => err
          case _ => runApp(app, jo)

  /** Type-check only: compile everything as libs (--sast), skip app link step. */
  def check(plan: BuildPlan)(using Logger): Result[Unit] =
    val jo = plan.joBin.toString

    val it = plan.depBuilds.iterator
    while it.hasNext do
      val (name, lib) = it.next()
      log(s"[check] $name\n")
      runLib(lib, jo) match
        case Result.Err(msg) => return Result.Err(msg)
        case _ =>

    plan.mainPlan match
      case lib: CompilePlan.LibPlan =>
        log("[check] root\n")
        runLib(lib, jo)

      case app: CompilePlan.AppPlan =>
        log("[check] root\n")
        runLib(CompilePlan.LibPlan(app.sources, app.checkLibs, app.sastDir), jo)

  /** Build all deps, root lib, test deps, and test app — without executing.
   *
   *  Returns Ok(None) if no test is defined, Ok(Some(plan)) if built successfully.
   */
  def buildForTest(plan: BuildPlan)(using Logger): Result[Option[CompilePlan.AppPlan]] =
    val jo = plan.joBin.toString

    val it = plan.depBuilds.iterator
    while it.hasNext do
      val (name, lib) = it.next()
      log(s"[build] $name\n")
      runLib(lib, jo) match
        case Result.Err(msg) => return Result.Err(msg)
        case _ =>

    val rootLibBuild: CompilePlan.LibPlan = plan.mainPlan match
      case lib: CompilePlan.LibPlan => lib
      case app: CompilePlan.AppPlan => CompilePlan.LibPlan(app.sources, app.checkLibs, app.sastDir)
    log("[build] root\n")
    runLib(rootLibBuild, jo) match
      case Result.Err(msg) => return Result.Err(msg)
      case _ =>

    plan.testPlan match
      case None => Result.Ok(None)

      case Some(tp) =>
        val it2 = plan.testDepBuilds.iterator
        while it2.hasNext do
          val (name, lib) = it2.next()
          log(s"[build] $name (test)\n")
          runLib(lib, jo) match
            case Result.Err(msg) => return Result.Err(msg)
            case _ =>
        log("[test] build\n")
        runApp(tp, jo).map(_ => Some(tp))

  /** Build all deps and root as lib, then build and run the test app. */
  def test(plan: BuildPlan)(using Logger): Result[Unit] =
    buildForTest(plan).flatMap:
      case None =>
        log("no tests defined\n")
        Result.unit

      case Some(tp) =>
        log("[test] run\n")
        execute(tp, Nil).map(_ => ())

  /** Execute the compiled app, forwarding appArgs.
   *
   *  Returns Ok(stdout) on success, Err(output) on non-zero exit.
   */
  def execute(app: CompilePlan.AppPlan, appArgs: List[String]): Result[String] =
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
    if exit != 0 then Result.Err(out) else Result.Ok(out)

  private def runLib(lib: CompilePlan.LibPlan, jo: String): Result[Unit] =
    val sentinel = lib.outDir.resolve(".done")
    if isUpToDate(lib.sources, lib.checkLibs, Nil, sentinel) then return Result.unit
    Files.createDirectories(lib.outDir)
    val args = ArrayBuffer[String]()
    args += jo
    args += "compile"
    args += "--sast"
    args += lib.outDir.toString
    lib.sources.foreach(args += _.toString)
    lib.checkLibs.foreach: l =>
      args += "--lib"
      args += l.toString

    exec(args.toList) match
      case ok @ Result.Ok(_) =>
        Files.write(sentinel, Array.emptyByteArray)
        ok

      case err => err

  private def runApp(app: CompilePlan.AppPlan, jo: String): Result[Unit] =
    if isUpToDate(app.sources, app.checkLibs, app.linkLibs, app.outFile) then return Result.unit
    Files.createDirectories(app.outFile.getParent)
    Files.createDirectories(app.sastDir)
    val args = ArrayBuffer[String]()
    args += jo
    args += "compile"
    args += s"--${app.target}"
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

  /** Run a subprocess.  Returns Err with compiler output on non-zero exit. */
  private def exec(args: List[String]): Result[Unit] =
    val pb = ProcessBuilder(args.asJava)
    pb.redirectErrorStream(true)
    val proc = pb.start()
    val out  = String(proc.getInputStream.readAllBytes(), "UTF-8")
    val exit = proc.waitFor()
    if exit != 0 then Result.Err(out) else Result.unit

  private def log(msg: String)(using Logger): Unit = Logger.log(msg)

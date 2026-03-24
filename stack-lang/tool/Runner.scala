package tool

import java.nio.file.{Files, Path}
import scala.collection.mutable.ArrayBuffer
import scala.jdk.CollectionConverters.*

/** Executes a BuildPlan by invoking `jo compile` subprocesses. */
object Runner:
  def run(plan: BuildPlan): Unit =
    val jo = plan.joBin.toString

    for (name, lib) <- plan.depBuilds do
      println(s"[build] $name")
      runLib(lib, jo)

    plan.mainPlan match
      case lib: CompilePlan.LibPlan =>
        println("[build] root (lib)")
        runLib(lib, jo)
      case app: CompilePlan.AppPlan =>
        println("[build] root (app)")
        runLib(CompilePlan.LibPlan(app.sources, app.checkLibs, app.sastDir), jo)
        runApp(app, jo)

  /** Type-check only: compile everything as libs (--sast), skip app link step. */
  def check(plan: BuildPlan): Unit =
    val jo = plan.joBin.toString

    for (name, lib) <- plan.depBuilds do
      println(s"[check] $name")
      runLib(lib, jo)

    plan.mainPlan match
      case lib: CompilePlan.LibPlan =>
        println("[check] root")
        runLib(lib, jo)
      case app: CompilePlan.AppPlan =>
        println("[check] root")
        runLib(CompilePlan.LibPlan(app.sources, app.checkLibs, app.sastDir), jo)

  /** Build all deps and root as lib, then build and run the test app. */
  def test(plan: BuildPlan): Unit =
    val jo = plan.joBin.toString

    for (name, lib) <- plan.depBuilds do
      println(s"[build] $name")
      runLib(lib, jo)

    val rootLibBuild: CompilePlan.LibPlan = plan.mainPlan match
      case lib: CompilePlan.LibPlan => lib
      case app: CompilePlan.AppPlan => CompilePlan.LibPlan(app.sources, app.checkLibs, app.sastDir)
    println("[build] root")
    runLib(rootLibBuild, jo)

    plan.testPlan match
      case None =>
        println("no tests defined")
      case Some(tp) =>
        for (name, lib) <- plan.testDepBuilds do
          println(s"[build] $name (test)")
          runLib(lib, jo)
        println("[test] build")
        runApp(tp, jo)
        println("[test] run")
        execute(tp, Nil)

  /** Execute the compiled app output, forwarding appArgs to the process. */
  def execute(app: CompilePlan.AppPlan, appArgs: List[String]): Unit =
    val cmd = app.target match
      case "python" => "python3" :: app.outFile.toString :: appArgs
      case "js"     => "node"    :: app.outFile.toString :: appArgs
      case "ruby"   => "ruby"    :: app.outFile.toString :: appArgs
      case _        =>              app.outFile.toString :: appArgs
    exec(cmd)

  private def runLib(lib: CompilePlan.LibPlan, jo: String): Unit =
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

  private def runApp(app: CompilePlan.AppPlan, jo: String): Unit =
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

  private def exec(args: List[String]): Unit =
    val pb = ProcessBuilder(args.asJava)
    pb.inheritIO()
    val exit = pb.start().waitFor()
    if exit != 0 then
      throw ToolError(s"command failed (exit $exit): ${args.mkString(" ")}")

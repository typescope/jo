package tool

import java.nio.file.{Path, Files}
import scala.collection.mutable.ArrayBuffer
import scala.jdk.CollectionConverters.*

/** Executes a BuildPlan by invoking `jo compile` subprocesses. */
object Runner:
  /** Path to the jo binary — resolved from the same directory as this JVM process,
   *  or from PATH if not found. */
  def joBin: String =
    val jar = getClass.getProtectionDomain.getCodeSource.getLocation.toURI.getPath
    val binDir = java.nio.file.Paths.get(jar).getParent
    val candidate = binDir.resolve("jo")
    if Files.isExecutable(candidate) then candidate.toString else "jo"

  def run(plan: BuildPlan, joCmd: String = joBin): Unit =
    for (name, lib) <- plan.depBuilds do
      println(s"[build] $name")
      runLib(lib, joCmd)

    plan.rootBuild match
      case lib: RootBuild.LibBuild =>
        println("[build] root (lib)")
        runLib(lib, joCmd)
      case app: RootBuild.AppBuild =>
        println("[build] root (app)")
        runApp(app, joCmd)

  private def runLib(lib: RootBuild.LibBuild, jo: String): Unit =
    Files.createDirectories(lib.outDir)
    val args = ArrayBuffer[String]()
    args += jo
    args += "compile"
    args += "--sast"
    lib.sources.foreach: s =>
      args += s.toString

    lib.checkLibs.foreach: l =>
      args += "--lib"
      args += l.toString

    args += "-d"
    args += lib.outDir.toString
    exec(args.toList)

  private def runApp(app: RootBuild.AppBuild, jo: String): Unit =
    Files.createDirectories(app.outFile.getParent)
    val args = ArrayBuffer[String]()
    args += jo
    args += "compile"
    args += s"--${app.target}"
    app.sources.foreach: s =>
      args += s.toString

    app.checkLibs.foreach: l =>
      args += "--lib"
      args += l.toString

    app.linkLibs.foreach: l =>
      args += "--runtime"
      args += l.toString

    app.links.toSeq.sortBy(_._1).foreach: (k, v) =>
      args += "--link"
      args += s"$k=$v"

    args += "-o"
    args += app.outFile.toString
    exec(args.toList)

  private def exec(args: List[String]): Unit =
    val pb = ProcessBuilder(args.asJava)
    pb.inheritIO()
    val exit = pb.start().waitFor()

    if exit != 0 then throw ToolError(s"command failed (exit $exit): ${args.mkString(" ")}")

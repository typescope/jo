package tool

import java.nio.file.Path
import scala.collection.mutable.ArrayBuffer

/** Prints a BuildPlan as a sequence of `jo compile` commands for inspection and testing. */
object PlanPrinter:
  def print(plan: BuildPlan, baseDir: Path): String =
    val sb = new StringBuilder

    for (name, lib) <- plan.depBuilds do
      sb.append(s"# lib: $name\n")
      sb.append(libCmd(lib, baseDir))
      sb.append("\n\n")

    plan.mainPlan match
      case lib: CompilePlan.LibPlan =>
        sb.append(s"# root (lib)\n")
        sb.append(libCmd(lib, baseDir))
      case app: CompilePlan.AppPlan =>
        sb.append(s"# root (app)\n")
        sb.append(appCmd(app, baseDir))

    plan.testPlan.foreach: tp =>
      sb.append("\n\n")
      for (name, lib) <- plan.testDepBuilds do
        sb.append(s"# test lib: $name\n")
        sb.append(libCmd(lib, baseDir))
        sb.append("\n\n")
      sb.append(s"# test (app)\n")
      sb.append(appCmd(tp, baseDir))

    sb.toString.stripTrailing()

  private def libCmd(lib: CompilePlan.LibPlan, base: Path): String =
    val parts = ArrayBuffer[String]("jo compile")
    parts += s"--sast ${rel(lib.outDir, base)}"
    lib.sources.foreach(s => parts += rel(s, base))
    lib.checkLibs.foreach(l => parts += s"--lib ${rel(l, base)}")
    parts.mkString(" ")

  private def appCmd(app: CompilePlan.AppPlan, base: Path): String =
    val parts = ArrayBuffer[String](s"jo compile --${app.target.flag}")
    app.sources.foreach(s => parts += rel(s, base))
    app.checkLibs.foreach(l => parts += s"--lib ${rel(l, base)}")
    app.linkLibs.foreach(l => parts += s"--link-lib ${rel(l, base)}")

    app.links.toSeq.sortBy(_._1).foreach { (k, v) => parts += s"--link $k=$v" }
    parts += s"-o ${rel(app.outFile, base)}"
    parts.mkString(" ")

  private def rel(p: Path, base: Path): String =
    try base.relativize(p).toString
    catch case _: IllegalArgumentException => p.toString

package tool

import java.nio.file.Path
import scala.collection.mutable.{ArrayBuffer, Set}

/** Prints a ModulePlan as a sequence of `jo compile` commands for inspection and testing. */
object PlanPrinter:
  def print(plans: ProjectPlan, baseDir: Path): String =
    val sb      = new StringBuilder
    val visited = Set.empty[Path]

    def outKey(task: CompileTask): Path = task match
      case lib: CompileTask.LibTask => lib.outDir
      case app: CompileTask.AppTask => app.outFile

    def appendCmd(label: String, task: CompileTask): Unit =
      sb.append(s"# $label\n")
      task match
        case lib: CompileTask.LibTask => sb.append(libCmd(lib, baseDir))
        case app: CompileTask.AppTask => sb.append(appCmd(app, baseDir))
      sb.append("\n\n")

    def traverse(plan: ModulePlan, isRoot: Boolean, inTest: Boolean): Unit =
      for dep <- plan.deps do traverse(dep, isRoot = false, inTest)
      val key = outKey(plan.task)
      if !visited.contains(key) then
        visited += key
        plan.task match
          case app: CompileTask.AppTask => visited += app.sastDir
          case _ =>
        val label =
          if isRoot then
            plan.task match
              case _: CompileTask.LibTask => if inTest then "test (lib)" else "root (lib)"
              case _: CompileTask.AppTask => if inTest then "test (app)" else "root (app)"
          else
            if inTest then s"test lib: ${plan.projectName}"
            else s"lib: ${plan.projectName}"
        appendCmd(label, plan.task)

    traverse(plans.main, isRoot = true, inTest = false)
    plans.test.foreach(tp => traverse(tp, isRoot = true, inTest = true))
    sb.toString.stripTrailing()

  private def libCmd(lib: CompileTask.LibTask, base: Path): String =
    val parts = ArrayBuffer[String]("jo compile")
    parts += s"--sast ${rel(lib.outDir, base)}"
    lib.sources.foreach(s => parts += rel(s, base))
    lib.checkLibs.foreach(l => parts += s"--lib ${rel(l, base)}")
    parts.mkString(" ")

  private def appCmd(app: CompileTask.AppTask, base: Path): String =
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

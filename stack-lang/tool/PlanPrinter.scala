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

    def traverse(plan: ModulePlan, isRoot: Boolean): Unit =
      for dep <- plan.deps do traverse(dep, isRoot = false)
      val key = outKey(plan.task)
      if !visited.contains(key) then
        visited += key
        plan.task match
          case app: CompileTask.AppTask => visited += app.sastDir
          case _ =>
        val label =
          if isRoot then
            plan.task match
              case _: CompileTask.LibTask => s"root ${plan.projectName}.${plan.module.value} (lib)"
              case _: CompileTask.AppTask => s"root ${plan.projectName}.${plan.module.value} (app)"
          else
            s"lib: ${plan.projectName}.${plan.module.value}"
        appendCmd(label, plan.task)

    plans.modules.foreach(plan => traverse(plan, isRoot = true))
    sb.toString.stripTrailing()

  private def libCmd(lib: CompileTask.LibTask, base: Path): String =
    val parts = ArrayBuffer[String]("jo compile")
    lib.compileOptions.foreach(o => parts += o)
    parts += s"--sast ${rel(lib.outDir, base)}"
    lib.sources.foreach(s => parts += rel(s, base))
    lib.checkLibs.foreach(l => parts += s"--lib ${rel(l, base)}")
    parts.mkString(" ")

  private def appCmd(app: CompileTask.AppTask, base: Path): String =
    val parts = ArrayBuffer[String](s"jo compile --${app.target.flag}")
    app.compileOptions.foreach(o => parts += o)
    app.sources.foreach(s => parts += rel(s, base))
    app.checkLibs.foreach(l => parts += s"--lib ${rel(l, base)}")
    app.linkLibs.foreach(l => parts += s"--link-lib ${rel(l, base)}")
    app.links.toSeq.sortBy(_._1).foreach { (k, v) => parts += s"--link $k=$v" }
    parts += s"-o ${rel(app.outFile, base)}"
    parts.mkString(" ")

  private def rel(p: Path, base: Path): String =
    try base.relativize(p).toString
    catch case _: IllegalArgumentException => p.toString

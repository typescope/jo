package tool

import java.nio.file.Path
import scala.collection.mutable

object Planner:
  type RegistrySastDirs = Map[String, Path]
  private case class EffectiveLink(to: String, source: String)
  private case class EffectiveAppLinks(linkLibs: List[Path], links: Map[String, EffectiveLink])

  def plan(project: Project, selected: List[ModuleId], registrySastDirs: RegistrySastDirs): Result[ProjectPlan] =
    Project.validateModuleAcyclic(project, selected).flatMap: _ =>
      PlanBuilder(project, registrySastDirs).plan(selected)

  private final class PlanBuilder(root: Project, registrySastDirs: RegistrySastDirs):
    private val memo = collection.mutable.Map.empty[(Path, ModuleId), ModulePlan]
    private val stack = collection.mutable.ArrayBuffer.empty[(Project, ModuleId)]
    private val linkResolver = LinkResolver(root, registrySastDirs)

    def plan(selected: List[ModuleId]): Result[ProjectPlan] =
      selected.foldRight(Result.Ok(List.empty[ModulePlan]): Result[List[ModulePlan]]): (id, acc) =>
        makePlan(root, id).flatMap: plan =>
          acc.map(plans => plan :: plans)
      .map(ProjectPlan(_))

    private def makePlan(project0: Project, id: ModuleId): Result[ModulePlan] =
      val key = project0.specPath -> id
      memo.get(key) match
        case Some(plan) =>
          Result.Ok(plan)

        case None =>
          val cycleStart = stack.indexWhere((p, m) => p.specPath == project0.specPath && m == id)
          if cycleStart >= 0 then
            Result.Err(Project.formatModuleCycle(root, stack.drop(cycleStart).toList :+ ((project0, id))))
          else
            stack += ((project0, id))
            val result = project0.requireModule(id).flatMap: spec =>
              val depPlansResult = project0.moduleDepsOf(id).foldRight(Result.Ok(List.empty[ModulePlan]): Result[List[ModulePlan]]): (dep, acc) =>
                makePlan(dep.project.getOrElse(project0), dep.module).flatMap: plan =>
                  acc.map(plans => plan :: plans)

              depPlansResult.flatMap: depPlans =>
                val sourceDeps = sourceClosure(project0, id)
                val sourceCheckLibs = sourceDeps.collect:
                  case (depProject, depModule, DepLink.Check) => depProject.sastDir(depModule)
                val checkLibs = sourceCheckLibs ++ registryCheckLibs(spec)

                SourcePaths.expand(spec.src, project0.dir).flatMap: sources =>
                  val compileOptions = ffiCompileOptions(spec) ++ spec.compileOptions
                  val task =
                    spec.kind match
                      case ModuleKind.Lib =>
                        Result.Ok(
                          CompileTask.LibTask(
                            sources,
                            checkLibs,
                            project0.sastDir(id),
                            compileOptions,
                          )
                        )

                      case ModuleKind.App =>
                        resolveTarget(project0, id).flatMap: target =>
                          linkResolver.resolve(project0, id).map: effectiveLinks =>
                            val visibleLibs = checkLibs.toSet
                            CompileTask.AppTask(
                              sources,
                              checkLibs,
                              effectiveLinks.linkLibs.filterNot(visibleLibs),
                              effectiveLinks.links.view.mapValues(_.to).toMap,
                              target,
                              project0.appOutFile(id, target),
                              project0.sastDir(id),
                              compileOptions,
                            )

                  task.map: task =>
                    ModulePlan(moduleLabel(project0, id), id, project0.joBin, task, depPlans)

            stack.remove(stack.length - 1)
            result.map: plan =>
              memo(key) = plan
              plan

    private def moduleLabel(project0: Project, id: ModuleId): String =
      project0.moduleLabel(root, id)

    private def registryCheckLibs(module: ModuleSpec): List[Path] =
      module.packageDeps.filter(_.link == DepLink.Check).flatMap(dep => registrySastDirs.get(dep.name))

    private def sourceClosure(project0: Project, id: ModuleId): List[(Project, ModuleId, DepLink)] =
      val out = new mutable.ArrayBuffer[(Project, ModuleId, DepLink)]
      val seen = mutable.Set.empty[(Path, ModuleId)]

      def walk(currentProject: Project, current: ModuleId): Unit =
        for dep <- currentProject.moduleDepsOf(current) do
          val depProject = dep.project.getOrElse(currentProject)
          val key = depProject.specPath -> dep.module
          if dep.link == DepLink.Check && seen.add(key) then
            out += ((depProject, dep.module, dep.link))
            walk(depProject, dep.module)

      walk(project0, id)
      out.toList

  private final class LinkResolver(root: Project, registrySastDirs: RegistrySastDirs):
    private val memo = collection.mutable.Map.empty[(Path, ModuleId), EffectiveAppLinks]
    private val stack = collection.mutable.Set.empty[(Path, ModuleId)]

    def resolve(project: Project, id: ModuleId): Result[EffectiveAppLinks] =
      val key = project.specPath -> id
      memo.get(key) match
        case Some(value) =>
          Result.Ok(value)

        case None =>
          if stack.contains(key) then
            Result.Err(s"circular app link inheritance detected at ${moduleLabel(project, id)}")
          else
            stack += key
            val result = compute(project, id)
            stack -= key
            result.map: value =>
              memo(key) = value
              value

    private def compute(project: Project, id: ModuleId): Result[EffectiveAppLinks] =
      project.requireModule(id).flatMap: spec =>
        spec.kind match
          case ModuleKind.Lib => Result.Ok(EffectiveAppLinks(Nil, Map.empty))
          case ModuleKind.App => computeAppLinks(project, id, spec)

    private def computeAppLinks(project: Project, id: ModuleId, spec: ModuleSpec): Result[EffectiveAppLinks] =
      val owner = moduleLabel(project, id)
      val ownOverrides = spec.links.map(_.from).toSet
      val linkLibs = collection.mutable.ListBuffer.empty[Path]
      val inheritedLinks = collection.mutable.LinkedHashMap.empty[String, EffectiveLink]

      spec.packageDeps.filter(_.link == DepLink.Link).foreach: dep =>
        registrySastDirs.get(dep.name).foreach(linkLibs += _)

      spec.moduleDeps.foldLeft(Result.unit): (acc, depSpec) =>
        acc.flatMap(_ => addModuleLinks(project, id, depSpec, linkLibs, inheritedLinks, ownOverrides, owner))
      .map: _ =>
        spec.links.foreach: link =>
          inheritedLinks(link.from) = EffectiveLink(link.to, owner)

        EffectiveAppLinks(linkLibs.toList.distinct, inheritedLinks.toMap)

    private def addModuleLinks(
      project: Project,
      id: ModuleId,
      depSpec: ModuleDepSpec,
      linkLibs: collection.mutable.ListBuffer[Path],
      inheritedLinks: collection.mutable.LinkedHashMap[String, EffectiveLink],
      ownOverrides: Set[String],
      owner: String,
    ): Result[Unit] =
      project.moduleDepOf(id, depSpec.id, depSpec.path) match
        case None =>
          Result.Err(s"module '${id.value}' depends on unresolved module '${depSpec.id.value}'")

        case Some(dep) =>
          val depProject = dep.project.getOrElse(project)
          if depSpec.link == DepLink.Link then
            linkLibs += depProject.sastDir(depSpec.id)
            linkLibs ++= hiddenCheckLibs(depProject, depSpec.id)

          projectLinks(depProject, depSpec.id).flatMap: inherited =>
            linkLibs ++= inherited.linkLibs
            inherited.links.foldLeft(Result.unit): (mergeAcc, entry) =>
              val (from, link) = entry
              mergeAcc.flatMap(_ => mergeInherited(id, owner, ownOverrides, inheritedLinks, from, link))

    private def projectLinks(project: Project, id: ModuleId): Result[EffectiveAppLinks] =
      project.requireModule(id).flatMap: spec =>
        if spec.kind == ModuleKind.App then resolve(project, id)
        else Result.Ok(EffectiveAppLinks(Nil, Map.empty))

    private def hiddenCheckLibs(project: Project, id: ModuleId): List[Path] =
      val out = collection.mutable.ListBuffer.empty[Path]
      val seen = collection.mutable.Set.empty[(Path, ModuleId)]

      def walk(currentProject: Project, current: ModuleId): Unit =
        currentProject.module(current).foreach: spec =>
          spec.packageDeps.filter(_.link == DepLink.Check).foreach: dep =>
            registrySastDirs.get(dep.name).foreach(out += _)

        for dep <- currentProject.moduleDepsOf(current) do
          val depProject = dep.project.getOrElse(currentProject)
          val key = depProject.specPath -> dep.module
          if dep.link == DepLink.Check && seen.add(key) then
            out += depProject.sastDir(dep.module)
            walk(depProject, dep.module)

      walk(project, id)
      out.toList

    private def mergeInherited(
      id: ModuleId,
      owner: String,
      ownOverrides: Set[String],
      inheritedLinks: collection.mutable.LinkedHashMap[String, EffectiveLink],
      from: String,
      link: EffectiveLink,
    ): Result[Unit] =
      if ownOverrides.contains(from) then
        Result.unit
      else
        inheritedLinks.get(from) match
          case None =>
            inheritedLinks(from) = link
            Result.unit
          case Some(existing) if existing.to == link.to =>
            Result.unit
          case Some(existing) =>
            Result.Err(
              s"""conflicting inherited links for module '$owner'
                 |
                 |  $from -> ${existing.to} from ${existing.source}
                 |  $from -> ${link.to} from ${link.source}
                 |
                 |Declare an explicit module.${id.value}.links entry for '$from' to override.""".stripMargin
            )

    private def moduleLabel(project: Project, id: ModuleId): String =
      project.moduleLabel(root, id)

  private def resolveTarget(project: Project, module: ModuleId): Result[Target] =
    project.platform(module).target match
      case Some(target) => Result.Ok(target)
      case None         => Result.Err(s"module '${module.value}' has no app platform")

  private def ffiCompileOptions(module: ModuleSpec): List[String] =
    if module.enableFfi then
      module.platform.flatMap(_.target).toList.flatMap(target => List("--use-runtime-api", target.flag))
    else
      Nil

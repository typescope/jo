package tool

import java.nio.file.Path

object Planner:
  type RegistrySastDirs = Map[String, Path]
  private case class EffectiveLink(to: String, source: String)
  private case class EffectiveAppLinks(linkLibs: List[Path], links: Map[String, EffectiveLink])

  def plan(project: Project, selected: List[ModuleId], registrySastDirs: RegistrySastDirs): Result[ProjectPlan] =
    Project.validateModuleAcyclic(project, selected).flatMap: _ =>
      planAcyclic(project, selected, registrySastDirs)

  private def planAcyclic(project: Project, selected: List[ModuleId], registrySastDirs: RegistrySastDirs): Result[ProjectPlan] =
    val memo = collection.mutable.Map.empty[(Path, ModuleId), ModulePlan]
    val stack = collection.mutable.ArrayBuffer.empty[(Project, ModuleId)]
    val linkMemo = collection.mutable.Map.empty[(Path, ModuleId), EffectiveAppLinks]
    val linkStack = collection.mutable.Set.empty[(Path, ModuleId)]

    def moduleLabel(project0: Project, id: ModuleId): String =
      project0.moduleLabel(project, id)

    def registryCheckLibs(module: ModuleSpec): List[Path] =
      module.dependencies.flatMap:
        case DepSpec(DepSource.Registry(name, _), DepLink.Check) => registrySastDirs.get(name)
        case _ => Nil

    def sourceClosure(project0: Project, id: ModuleId): List[(Project, ModuleId, DepLink)] =
      val out = collection.mutable.ListBuffer.empty[(Project, ModuleId, DepLink)]
      val seen = collection.mutable.Set.empty[(Path, ModuleId)]

      def walk(currentProject: Project, current: ModuleId): Unit =
        for dep <- currentProject.moduleDepsOf(current) do
          val depProject = dep.project.getOrElse(currentProject)
          val key = depProject.specPath -> dep.module
          if seen.add(key) then
            out += ((depProject, dep.module, dep.link))
            walk(depProject, dep.module)

      walk(project0, id)
      out.toList

    def requireSpec(project0: Project, id: ModuleId): Result[ModuleSpec] =
      project0.requireModule(id)

    def effectiveAppLinks(project0: Project, id: ModuleId): Result[EffectiveAppLinks] =
      val key = project0.specPath -> id
      linkMemo.get(key) match
        case Some(value) =>
          Result.Ok(value)

        case None =>
          if linkStack.contains(key) then
            Result.Err(s"circular app link inheritance detected at ${moduleLabel(project0, id)}")
          else
            linkStack += key
            val result = requireSpec(project0, id).flatMap: spec =>
              def compute: Result[EffectiveAppLinks] =
                val owner = moduleLabel(project0, id)
                val ownOverrides = spec.links.map(_.from).toSet
                val linkLibs = collection.mutable.ListBuffer.empty[Path]
                val inheritedLinks = collection.mutable.LinkedHashMap.empty[String, EffectiveLink]

                def mergeInherited(from: String, link: EffectiveLink): Result[Unit] =
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

                val depsResult = spec.dependencies.foldLeft(Result.unit): (acc, depSpec) =>
                  acc.flatMap: _ =>
                    depSpec match
                      case DepSpec(DepSource.Registry(name, _), DepLink.Link) =>
                        registrySastDirs.get(name).foreach(linkLibs += _)
                        Result.unit

                      case DepSpec(DepSource.Registry(_, _), DepLink.Check) =>
                        Result.unit

                      case DepSpec(DepSource.Module(depModule, sourcePath), linkMode) =>
                        project0.moduleDepOf(id, depModule, sourcePath) match
                          case None =>
                            Result.Err(s"module '${id.value}' depends on unresolved module '${depModule.value}'")

                          case Some(dep) =>
                            val depProject = dep.project.getOrElse(project0)
                            if linkMode == DepLink.Link then
                              linkLibs += depProject.sastDir(depModule)

                            requireSpec(depProject, depModule).flatMap: depSpec =>
                              if depSpec.kind == ModuleKind.App then
                                effectiveAppLinks(depProject, depModule).flatMap: inherited =>
                                  linkLibs ++= inherited.linkLibs
                                  inherited.links.foldLeft(Result.unit): (mergeAcc, entry) =>
                                    val (from, link) = entry
                                    mergeAcc.flatMap(_ => mergeInherited(from, link))
                              else
                                Result.unit

                depsResult.map: _ =>
                  spec.links.foreach: link =>
                    inheritedLinks(link.from) = EffectiveLink(link.to, owner)

                  EffectiveAppLinks(linkLibs.toList.distinct, inheritedLinks.toMap)

              spec.kind match
                case ModuleKind.Lib => Result.Ok(EffectiveAppLinks(Nil, Map.empty))
                case ModuleKind.App => compute

            linkStack -= key
            result.map: value =>
              linkMemo(key) = value
              value

    def makePlan(project0: Project, id: ModuleId): Result[ModulePlan] =
      val key = project0.specPath -> id
      memo.get(key) match
        case Some(plan) =>
          Result.Ok(plan)

        case None =>
          val cycleStart = stack.indexWhere((p, m) => p.specPath == project0.specPath && m == id)
          if cycleStart >= 0 then
            Result.Err(Project.formatModuleCycle(project, stack.drop(cycleStart).toList :+ ((project0, id))))
          else
            stack += ((project0, id))
            val result = requireSpec(project0, id).flatMap: spec =>
              val depPlansResult = project0.moduleDepsOf(id).foldRight(Result.Ok(List.empty[ModulePlan]): Result[List[ModulePlan]]): (dep, acc) =>
                makePlan(dep.project.getOrElse(project0), dep.module).flatMap: plan =>
                  acc.map(plans => plan :: plans)

              depPlansResult.flatMap: depPlans =>
                val sourceDeps = sourceClosure(project0, id)
                val sourceCheckLibs = sourceDeps.collect:
                  case (depProject, depModule, DepLink.Check) => depProject.sastDir(depModule)

                val sources = SourceGlob.expand(spec.src, project0.dir, SourceGlob.defaultModuleSrc(id))
                val compileOptions = ffiCompileOptions(spec) ++ spec.compileOptions
                val task =
                  spec.kind match
                    case ModuleKind.Lib =>
                      Result.Ok(
                        CompileTask.LibTask(
                          sources,
                          sourceCheckLibs ++ registryCheckLibs(spec),
                          project0.sastDir(id),
                          compileOptions,
                        )
                      )

                    case ModuleKind.App =>
                      resolveTarget(project0, id).flatMap: target =>
                        effectiveAppLinks(project0, id).map: effectiveLinks =>
                          CompileTask.AppTask(
                            sources,
                            sourceCheckLibs ++ registryCheckLibs(spec),
                            effectiveLinks.linkLibs,
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

    selected.foldRight(Result.Ok(List.empty[ModulePlan]): Result[List[ModulePlan]]): (id, acc) =>
      makePlan(project, id).flatMap: plan =>
        acc.map(plans => plan :: plans)
    .map(ProjectPlan(_))

  private def resolveTarget(project: Project, module: ModuleId): Result[Target] =
    project.platform(module).target match
      case Some(target) => Result.Ok(target)
      case None         => Result.Err(s"module '${module.value}' has no app platform")

  private def ffiCompileOptions(module: ModuleSpec): List[String] =
    if module.enableFfi then
      module.platform.flatMap(_.target).toList.flatMap(target => List("--use-runtime-api", target.flag))
    else
      Nil

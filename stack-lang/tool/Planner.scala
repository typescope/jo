package tool

import java.nio.file.Path

object Planner:
  type RegistrySastDirs = Map[String, Path]
  private case class EffectiveLink(to: String, source: String)
  private case class EffectiveAppLinks(linkLibs: List[Path], links: Map[String, EffectiveLink])

  def plan(project: Project, selected: List[ModuleId], registrySastDirs: RegistrySastDirs): ProjectPlan =
    Project.validateModuleAcyclic(project, selected) match
      case Result.Err(msg) => throw IllegalStateException(msg)
      case Result.Ok(_) =>

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

    def requireSpec(project0: Project, id: ModuleId): ModuleSpec =
      project0.requireModule(id) match
        case Result.Ok(value) => value
        case Result.Err(msg)  => throw IllegalArgumentException(msg)

    def effectiveAppLinks(project0: Project, id: ModuleId): EffectiveAppLinks =
      val key = project0.specPath -> id
      linkMemo.getOrElseUpdate(
        key,
        {
          if linkStack.contains(key) then
            throw IllegalStateException(s"circular app link inheritance detected at ${moduleLabel(project0, id)}")
          linkStack += key

          try
            val spec = requireSpec(project0, id)
            spec.kind match
              case ModuleKind.Lib =>
                EffectiveAppLinks(Nil, Map.empty)

              case ModuleKind.App =>
                val owner = moduleLabel(project0, id)
                val ownOverrides = spec.links.map(_.from).toSet
                val linkLibs = collection.mutable.ListBuffer.empty[Path]
                val inheritedLinks = collection.mutable.LinkedHashMap.empty[String, EffectiveLink]

                def mergeInherited(from: String, link: EffectiveLink): Unit =
                  if ownOverrides.contains(from) then
                    ()
                  else
                    inheritedLinks.get(from) match
                      case None =>
                        inheritedLinks(from) = link
                      case Some(existing) if existing.to == link.to =>
                        ()
                      case Some(existing) =>
                        throw IllegalArgumentException(
                          s"""conflicting inherited links for module '$owner'
                             |
                             |  $from -> ${existing.to} from ${existing.source}
                             |  $from -> ${link.to} from ${link.source}
                             |
                             |Declare an explicit module.${id.value}.links entry for '$from' to override.""".stripMargin
                        )

                spec.dependencies.foreach:
                  case DepSpec(DepSource.Registry(name, _), DepLink.Link) =>
                    registrySastDirs.get(name).foreach(linkLibs += _)

                  case DepSpec(DepSource.Registry(_, _), DepLink.Check) =>
                    ()

                  case DepSpec(DepSource.Module(depModule, sourcePath), linkMode) =>
                    val dep = project0.moduleDepOf(id, depModule, sourcePath).getOrElse:
                      throw IllegalArgumentException(s"module '${id.value}' depends on unresolved module '${depModule.value}'")
                    val depProject = dep.project.getOrElse(project0)
                    if linkMode == DepLink.Link then
                      linkLibs += depProject.sastDir(depModule)

                    val depSpec = requireSpec(depProject, depModule)
                    if depSpec.kind == ModuleKind.App then
                      val inherited = effectiveAppLinks(depProject, depModule)
                      linkLibs ++= inherited.linkLibs
                      inherited.links.foreach: (from, link) =>
                        mergeInherited(from, link)

                spec.links.foreach: link =>
                  inheritedLinks(link.from) = EffectiveLink(link.to, owner)

                EffectiveAppLinks(linkLibs.toList.distinct, inheritedLinks.toMap)
          finally
            linkStack -= key
        }
      )

    def makePlan(project0: Project, id: ModuleId): ModulePlan =
      val key = project0.specPath -> id
      memo.getOrElseUpdate(
        key,
        {
          val cycleStart = stack.indexWhere((p, m) => p.specPath == project0.specPath && m == id)
          if cycleStart >= 0 then
            throw IllegalStateException(Project.formatModuleCycle(project, stack.drop(cycleStart).toList :+ ((project0, id))))
          stack += ((project0, id))
          try
            val spec = requireSpec(project0, id)

            val depPlans = project0.moduleDepsOf(id).map: dep =>
              makePlan(dep.project.getOrElse(project0), dep.module)

            val sourceDeps = sourceClosure(project0, id)
            val sourceCheckLibs = sourceDeps.collect:
              case (depProject, depModule, DepLink.Check) => depProject.sastDir(depModule)

            val sources = SourceGlob.expand(spec.src, project0.dir, SourceGlob.defaultModuleSrc(id))
            val compileOptions = ffiCompileOptions(spec) ++ spec.compileOptions
            val task =
              spec.kind match
                case ModuleKind.Lib =>
                  CompileTask.LibTask(
                    sources,
                    sourceCheckLibs ++ registryCheckLibs(spec),
                    project0.sastDir(id),
                    compileOptions,
                  )

                case ModuleKind.App =>
                  val target = resolveTarget(project0, id)
                  val effectiveLinks = effectiveAppLinks(project0, id)
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

            ModulePlan(moduleLabel(project0, id), id, project0.joBin, task, depPlans)
          finally
            stack.remove(stack.length - 1)
        }
      )

    ProjectPlan(selected.map(id => makePlan(project, id)))

  private def resolveTarget(project: Project, module: ModuleId): Target =
    project.platform(module).target.getOrElse:
      throw IllegalArgumentException(s"module '${module.value}' has no app platform")

  private def ffiCompileOptions(module: ModuleSpec): List[String] =
    if module.enableFfi then
      module.platform.flatMap(_.target).toList.flatMap(target => List("--use-runtime-api", target.flag))
    else
      Nil

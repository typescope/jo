package tool

import java.nio.file.Path
import scala.collection.mutable

object Planner:
  /** A resolved registry package, as materialized for this build.
   *
   *  `deps` is the dependency-name set from its published `meta.toml`. Every entry there was
   *  check-visible to that package's own compilation, so it must be walked when computing a
   *  consumer's check libs — see `PlanBuilder.registryPackageClosure`.
   */
  case class RegistryPackage(sastDir: Path, deps: Set[String])
  type RegistryPackages = Map[String, RegistryPackage]

  private case class EffectiveLink(to: String, source: String)
  private case class EffectiveAppLinks(linkLibs: List[Path], links: Map[String, EffectiveLink])

  /** Identifies a module by its owning project's spec path rather than the `Project` value itself.
   *
   *  The same spec file can be loaded into more than one `Project` instance while walking
   *  cross-project module deps, and those must still be treated as the same module.
   */
  private case class ModuleKey(specPath: Path, id: ModuleId)
  private object ModuleKey:
    def apply(project: Project, id: ModuleId): ModuleKey = ModuleKey(project.specPath, id)

  def plan(project: Project, selected: List[ModuleId], registryPackages: RegistryPackages): Result[ProjectPlan] =
    Project.validateModuleAcyclic(project, selected).flatMap: _ =>
      PlanBuilder(project, registryPackages).plan(selected)

  private final class PlanBuilder(root: Project, registryPackages: RegistryPackages):
    private val memo = mutable.Map.empty[ModuleKey, ModulePlan]
    private val stack = mutable.ArrayBuffer.empty[(Project, ModuleId)]
    private val checkLibsCache = mutable.Map.empty[ModuleKey, List[Path]]
    private val linkResolver = LinkResolver(root, registryPackages, checkLibsOf)

    def plan(selected: List[ModuleId]): Result[ProjectPlan] =
      selected.foldRight(Result.Ok(List.empty[ModulePlan]): Result[List[ModulePlan]]): (id, acc) =>
        makePlan(root, id).flatMap: plan =>
          acc.map(plans => plan :: plans)
      .map(ProjectPlan(_))

    private def makePlan(project0: Project, id: ModuleId): Result[ModulePlan] =
      val key = ModuleKey(project0, id)
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
                val checkLibs = checkLibsOf(project0, id)

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
                          linkResolver.resolve(project0, id).flatMap: effectiveLinks =>
                            collectResources(project0, id).map: resources =>
                              val visibleLibs = checkLibs.toSet
                              val hiddenPackageLibs = registryHiddenLibs(visibleLibs)
                              CompileTask.AppTask(
                                sources,
                                checkLibs,
                                (effectiveLinks.linkLibs ++ hiddenPackageLibs).distinct.filterNot(visibleLibs),
                                effectiveLinks.links.view.mapValues(_.to).toMap,
                                target,
                                project0.appOutFile(id, target),
                                project0.sastDir(id),
                                resources,
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

    /** The check libs a module compiles against.
     *
     *  This is its own package deps plus everything reachable via Check-linked module deps
     *  (their sast dirs and their own package deps), with the registry side closed transitively
     *  through each package's own declared dependencies. It is the single definition of
     *  "check-visible" for a module — reused both to compile it directly and, when it is instead
     *  consumed via a `link: true` module dependency, to determine what must still be linked in
     *  on its behalf, invisibly. Memoized since the same module is often reached from multiple
     *  paths in the dependency graph.
     */
    private def checkLibsOf(project0: Project, id: ModuleId): List[Path] =
      checkLibsCache.getOrElseUpdate(ModuleKey(project0, id), computeCheckLibs(project0, id))

    private def computeCheckLibs(project0: Project, id: ModuleId): List[Path] =
      val moduleClosure = checkModuleClosure(project0, id)
      val moduleCheckLibs = moduleClosure.map((depProject, depModule) => depProject.sastDir(depModule))

      val ownPackageNames = project0.module(id).toList.flatMap(_.checkPackageDeps.map(_.name))
      val depPackageNames = moduleClosure.flatMap((depProject, depModule) => depProject.module(depModule).toList.flatMap(_.checkPackageDeps.map(_.name)))

      moduleCheckLibs ++ registryPackageClosure(ownPackageNames ++ depPackageNames)

    /** Modules reachable from `id` by following only Check-linked module deps, transitively.
     *
     *  A Link-linked dependency's own dependencies never become check-visible past it — the
     *  Link edge is exactly the boundary where a dependency's implementation goes opaque.
     */
    private def checkModuleClosure(project0: Project, id: ModuleId): List[(Project, ModuleId)] =
      val out = new mutable.ArrayBuffer[(Project, ModuleId)]
      val seen = mutable.Set.empty[ModuleKey]

      def walk(currentProject: Project, current: ModuleId): Unit =
        for dep <- currentProject.moduleDepsOf(current) do
          val depProject = dep.project.getOrElse(currentProject)
          val key = ModuleKey(depProject, dep.module)
          if dep.link == DepLink.Check && seen.add(key) then
            out += ((depProject, dep.module))
            walk(depProject, dep.module)

      walk(project0, id)
      out.toList

    /** The full registry-dependency closure reachable from `rootNames`.
     *
     *  Every declared dependency in a published package's `meta.toml` was check-visible during
     *  that package's own compilation, so its types may leak into the package's public API.
     *  Consumers need the whole chain on their check-lib path, not just the roots they name.
     */
    private def registryPackageClosure(rootNames: List[String]): List[Path] =
      val out = new mutable.ArrayBuffer[Path]
      val seen = mutable.Set.empty[String]

      def walk(name: String): Unit =
        if seen.add(name) then
          registryPackages.get(name).foreach: pkg =>
            out += pkg.sastDir
            pkg.deps.foreach(walk)

      rootNames.foreach(walk)
      out.toList

    private def registryHiddenLibs(visibleLibs: Set[Path]): List[Path] =
      registryPackages.values.map(_.sastDir).toList.sortBy(_.toString).filterNot(visibleLibs)

    private def collectResources(project0: Project, id: ModuleId): Result[List[ResourceGroup]] =
      val groups = new mutable.ArrayBuffer[ResourceGroup]
      val owners = mutable.Set.empty[String]

      def addGroup(result: Result[Option[ResourceGroup]]): Result[Unit] =
        result.flatMap:
          case None => Result.unit
          case Some(group) =>
            if !owners.add(group.owner) then
              Result.Err(s"duplicate resource owner '${group.owner}' in app resource closure")
            else
              groups += group
              Result.unit

      registryPackages.toSeq.sortBy(_._1).foldLeft(Result.unit): (acc, entry) =>
        val (name, pkg) = entry
        acc.flatMap(_ => addGroup(ResourcePaths.fromPackage(name, pkg.sastDir)))
      .flatMap: _ =>
        val seen = mutable.Set.empty[ModuleKey]

        def walk(currentProject: Project, current: ModuleId): Result[Unit] =
          val key = ModuleKey(currentProject, current)
          if !seen.add(key) then Result.unit
          else
            currentProject.requireModule(current).flatMap: spec =>
              val owner = sourceResourceOwner(currentProject, current)
              addGroup(ResourcePaths.fromModule(owner, spec.resources, currentProject.dir)).flatMap: _ =>
                currentProject.moduleDepsOf(current).foldLeft(Result.unit): (acc, dep) =>
                  acc.flatMap: _ =>
                    walk(dep.project.getOrElse(currentProject), dep.module)

        walk(project0, id)
      .map(_ => groups.toList)

    private def sourceResourceOwner(project0: Project, id: ModuleId): String =
      project0.pkg(id).map(_.name).getOrElse(id.value)

  private final class LinkResolver(root: Project, registryPackages: RegistryPackages, checkLibsOf: (Project, ModuleId) => List[Path]):
    private val memo = mutable.Map.empty[ModuleKey, EffectiveAppLinks]
    private val stack = mutable.Set.empty[ModuleKey]

    def resolve(project: Project, id: ModuleId): Result[EffectiveAppLinks] =
      val key = ModuleKey(project, id)
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
      val linkLibs = new mutable.ArrayBuffer[Path]
      val inheritedLinks = mutable.LinkedHashMap.empty[String, EffectiveLink]

      spec.linkPackageDeps.foreach: dep =>
        registryPackages.get(dep.name).foreach(pkg => linkLibs += pkg.sastDir)

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
      linkLibs: mutable.ArrayBuffer[Path],
      inheritedLinks: mutable.LinkedHashMap[String, EffectiveLink],
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
            linkLibs ++= checkLibsOf(depProject, depSpec.id)

          projectLinks(depProject, depSpec.id).flatMap: inherited =>
            linkLibs ++= inherited.linkLibs
            inherited.links.foldLeft(Result.unit): (mergeAcc, entry) =>
              val (from, link) = entry
              mergeAcc.flatMap(_ => mergeInherited(id, owner, ownOverrides, inheritedLinks, from, link))

    private def projectLinks(project: Project, id: ModuleId): Result[EffectiveAppLinks] =
      project.requireModule(id).flatMap: spec =>
        if spec.kind == ModuleKind.App then resolve(project, id)
        else Result.Ok(EffectiveAppLinks(Nil, Map.empty))

    private def mergeInherited(
      id: ModuleId,
      owner: String,
      ownOverrides: Set[String],
      inheritedLinks: mutable.LinkedHashMap[String, EffectiveLink],
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

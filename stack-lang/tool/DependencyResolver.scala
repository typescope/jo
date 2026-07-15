package tool

import java.nio.file.Path
import scala.collection.mutable

case class ResolvedPackage(
  name: String,
  version: Version,
  meta: PackageDependencyInfo,
)

case class DepthInfo(depth: Int, deepestPath: List[String])

case class ResolutionResult(
  packages: List[ResolvedPackage],
  unusedPins: List[(String, Version)],
  packageDepthByModule: Map[ModuleId, DepthInfo],
)

case class PackageConstraint(name: String, spec: VersionSpec)

object DependencyResolver:
  private enum Node:
    case Root(module: ModuleId)
    case Module(specPath: Path, module: ModuleId)
    case Package(name: String)

  private type DependencyGraph = mutable.LinkedHashMap[Node, mutable.ArrayBuffer[Node]]

  /** Resolve registry/package dependencies for a resolved project.
   *
   *  Algorithm:
   *
   *  1. Validate that every requested root module exists and that the source
   *     module graph reachable from those roots is acyclic.
   *  2. Build the source-module part of the dependency graph for the requested
   *     module roots. Same-project and external source module dependencies are
   *     walked into the same resolution universe.
   *  3. Seed a work queue with every registry package constraint reached from
   *     that source-module graph.
   *  4. For each package name, accumulate all version constraints seen so far,
   *     keeping the parent node so diagnostics can print paths back to roots.
   *  5. The first time a package is processed, select its version:
   *     - if a lock entry exists, it must satisfy the collected constraints,
   *       the root pin if any, and the selected Jo compiler;
   *     - otherwise ask the PackageProvider for available versions, honor an
   *       exact pin if present, skip prereleases unless pinned, and choose the
   *       highest Jo-compatible version satisfying all collected constraints.
   *  6. That version choice is fixed for the rest of resolution.
   *  7. Load that version's dependency metadata, validate the locked digest when
   *     the choice came from jo.lock, and record the selected package.
   *  8. Add reversed graph edges from each dependency package to its dependent
   *     package/module node, then enqueue the selected package's transitive
   *     package constraints.
   *  9. If a later-discovered constraint does not match the already selected
   *     version, fail explicitly with a conflict error instead of revising the
   *     earlier choice.
   *  10. When the queue is exhausted, return selected packages, unused pins, and
   *      final depth/path summaries for each requested root module.
   *
   *  Error behavior is explicit: failures are returned as Result.Err rather
   *  than being used for control flow via exceptions.
   */
  def resolveProject(project: Project, modules: List[ModuleId])(using provider: PackageProvider): Result[ResolutionResult] =
    resolve(project, modules, Map.empty)

  def resolveProject(project: Project, modules: List[ModuleId], lock: LockFile)(using provider: PackageProvider): Result[ResolutionResult] =
    resolve(project, modules, lock.packages.map(pkg => pkg.name -> pkg).toMap)

  private def resolve(
    project: Project,
    modules: List[ModuleId],
    locked: Map[String, LockedPackage],
  )(using provider: PackageProvider): Result[ResolutionResult] =
    val selectedModules = modules.distinct
    val missingModule = selectedModules.iterator
      .map(project.requireModule)
      .collectFirst:
        case Result.Err(msg) => msg

    missingModule match
      case Some(msg) => return Result.Err(msg)
      case None =>

    Project.validateModuleAcyclic(project, selectedModules) match
      case Result.Err(msg) => return Result.Err(msg)
      case Result.Ok(_) =>

    val (pendingSeeds, graph) = seedGraph(project, selectedModules)
    val packageConstraints = mutable.LinkedHashMap.empty[String, mutable.ArrayBuffer[(PackageConstraint, Node)]]
    val selectedPackages = mutable.LinkedHashMap.empty[String, ResolvedPackage]
    val queue = mutable.Queue.empty[(PackageConstraint, Node)]

    def addEdge(from: Node, to: Node): Unit =
      val parents = graph.getOrElseUpdate(from, mutable.ArrayBuffer.empty)
      if !parents.contains(to) then parents += to

    def addConstraint(
      name: String,
      constraint: PackageConstraint,
      parent: Node,
    ): mutable.ArrayBuffer[(PackageConstraint, Node)] =
      val current = packageConstraints.getOrElseUpdate(name, mutable.ArrayBuffer.empty)
      val alreadySeen = current.exists((existing, existingParent) =>
        existing.spec == constraint.spec && existingParent == parent
      )
      if !alreadySeen then current += ((constraint, parent))
      current

    def enqueueDeps(meta: PackageDependencyInfo, parent: Node): Unit =
      meta.dependencies.foreach: (depName, depConstraint) =>
        queue.enqueue(PackageConstraint(depName, depConstraint) -> parent)

    pendingSeeds.foreach(queue.enqueue(_))

    while queue.nonEmpty do
      val (current, parent) = queue.dequeue()
      val name = current.name
      val allConstraints = addConstraint(name, current, parent)
      addEdge(Node.Package(name), parent)
      val pinned = project.pinning.get(name)

      selectedPackages.get(name) match
        case Some(selectedPackage) =>
          val version = selectedPackage.version
          if !current.spec.contains(version) then
            return Result.Err(formatConstraintConflict(name, allConstraints.toList, graph, Some(version), pinned, project.specPath))

        case None =>
          selectVersion(name, project.joVersion, allConstraints.toList, locked.get(name), pinned, graph, project.specPath).flatMap:
            (version, meta) =>
              validateLockedDigest(name, version, locked.get(name)).map: _ =>
                selectedPackages(name) = ResolvedPackage(name, version, meta)
                enqueueDeps(meta, Node.Package(name))
          match
            case Result.Ok(_) =>
            case Result.Err(msg) => return Result.Err(msg)

    val packageNames = selectedPackages.keys.toList.sorted
    val packages = packageNames.map(selectedPackages)
    val unusedPins = project.pinning.toList.filterNot((name, _) => selectedPackages.contains(name)).sortBy(_._1)
    val depthByModule = selectedModules.map: module =>
      module -> deepestPath(graph, packageNames, module, project.specPath)
    .toMap

    Result.Ok(ResolutionResult(packages, unusedPins, depthByModule))

  private def selectVersion(
    name: String,
    joVersion: Version,
    constraints: List[(PackageConstraint, Node)],
    locked: Option[LockedPackage],
    pinned: Option[Version],
    graph: DependencyGraph,
    rootSpecPath: Path,
  )(using provider: PackageProvider): Result[(Version, PackageDependencyInfo)] =
    locked match
      case Some(pkg) =>
        selectLockedVersion(name, joVersion, constraints, pkg, pinned, graph, rootSpecPath)

      case None =>
        provider.versions(name) match
          case Result.Err(msg) if msg == s"package not found: $name" =>
            Result.Err(formatMissingPackage(name, constraints, graph, rootSpecPath))

          case Result.Err(msg) =>
            Result.Err(msg)

          case Result.Ok(versions) =>
            val pinError = pinned match
              case Some(pin) if !constraints.forall((constraint, _) => constraint.spec.contains(pin)) =>
                Some(formatPinnedVersionConflict(name, constraints, graph, pin, rootSpecPath))
              case Some(pin) if !versions.contains(pin) =>
                Some(formatPinnedVersionNotFound(name, constraints, graph, pin, rootSpecPath))
              case _ =>
                None

            pinError match
              case Some(msg) =>
                Result.Err(msg)

              case None =>
                val candidates = versions.sorted.reverse.filter: version =>
                  pinned.forall(_ == version) &&
                  (pinned.isDefined || !version.isPreRelease) &&
                  constraints.forall((constraint, _) => constraint.spec.contains(version))

                var incompatibleJo = List.empty[VersionSpec]
                val it = candidates.iterator
                while it.hasNext do
                  val version = it.next()
                  provider.dependencyInfo(name, version) match
                    case Result.Ok(meta) if meta.jo.contains(joVersion) =>
                      return Result.Ok(version -> meta)
                    case Result.Ok(meta) =>
                      incompatibleJo ::= meta.jo
                    case Result.Err(msg) =>
                      return Result.Err(msg)

                if candidates.nonEmpty then
                  val required = incompatibleJo.map(_.show).distinct.sorted.mkString(", ")
                  Result.Err(formatNoJoCompatibleVersion(name, constraints, graph, pinned, joVersion, required, rootSpecPath))
                else
                  Result.Err(formatNoSatisfiableVersion(name, constraints, graph, rootSpecPath))

  private def selectLockedVersion(
    name: String,
    joVersion: Version,
    constraints: List[(PackageConstraint, Node)],
    locked: LockedPackage,
    pinned: Option[Version],
    graph: DependencyGraph,
    rootSpecPath: Path,
  )(using provider: PackageProvider): Result[(Version, PackageDependencyInfo)] =
    parseLockedVersion(name, locked).flatMap: lockedVersion =>
      validateLockedVersion(name, lockedVersion, constraints, pinned, graph, rootSpecPath).flatMap: _ =>
        loadLockedDependencyInfo(name, lockedVersion, joVersion)

  private def validateLockedVersion(
    name: String,
    lockedVersion: Version,
    constraints: List[(PackageConstraint, Node)],
    pinned: Option[Version],
    graph: DependencyGraph,
    rootSpecPath: Path,
  ): Result[Unit] =
    val satisfiesPin = pinned.forall(_ == lockedVersion)
    val satisfiesConstraints = constraints.forall((constraint, _) => constraint.spec.contains(lockedVersion))
    if satisfiesPin && satisfiesConstraints then Result.unit
    else Result.Err(formatLockVersionMismatch(name, constraints, graph, lockedVersion, rootSpecPath))

  private def loadLockedDependencyInfo(
    name: String,
    lockedVersion: Version,
    joVersion: Version,
  )(using provider: PackageProvider): Result[(Version, PackageDependencyInfo)] =
    provider.dependencyInfo(name, lockedVersion).flatMap: meta =>
      if meta.jo.contains(joVersion) then Result.Ok(lockedVersion -> meta)
      else Result.Err(formatLockedJoMismatch(name, lockedVersion, meta.jo, joVersion))

  private def parseLockedVersion(name: String, pkg: LockedPackage): Result[Version] =
    Version.parse(pkg.version) match
      case Some(version) => Result.Ok(version)
      case None          => Result.Err(s"invalid locked version '${pkg.version}' for package '$name'")

  private def validateLockedDigest(
    name: String,
    version: Version,
    locked: Option[LockedPackage],
  )(using provider: PackageProvider): Result[Unit] =
    locked match
      case Some(pkg) if pkg.version == version.toString =>
        provider.digest(name, version).flatMap: actual =>
          if actual == pkg.sha512 then Result.unit
          else Result.Err(s"lock file digest mismatch for $name ${pkg.version}: expected ${pkg.sha512}, got $actual")

      case _ =>
        Result.unit

  private def seedGraph(project: Project, roots: List[ModuleId]): (List[(PackageConstraint, Node)], DependencyGraph) =
    val graph = mutable.LinkedHashMap.empty[Node, mutable.ArrayBuffer[Node]]
    val pending = mutable.ListBuffer.empty[(PackageConstraint, Node)]

    def addEdge(from: Node, to: Node): Unit =
      val parents = graph.getOrElseUpdate(from, mutable.ArrayBuffer.empty)
      if !parents.contains(to) then parents += to

    def walkModule(currentProject: Project, module: ModuleId, parent: Node, seen: mutable.Set[(Path, ModuleId)]): Unit =
      val key = currentProject.specPath -> module
      if seen.add(key) then
        currentProject.module(module).foreach: spec =>
          spec.packageDeps.foreach: dep =>
            pending += (PackageConstraint(dep.name, dep.constraint) -> parent)

          spec.moduleDeps.foreach: dep =>
            val depProject = currentProject.moduleDepOf(module, dep.id, dep.path)
              .flatMap(_.project)
              .getOrElse(currentProject)
            val child = Node.Module(depProject.specPath, dep.id)
            addEdge(child, parent)
            walkModule(depProject, dep.id, child, seen)

    roots.foreach: root =>
      val rootNode = Node.Root(root)
      walkModule(project, root, rootNode, mutable.Set.empty)

    (pending.toList, graph)

  private def deepestPath(
    graph: DependencyGraph,
    selectedPackages: List[String],
    root: ModuleId,
    rootSpecPath: Path,
  ): DepthInfo =
    val memo = mutable.Map.empty[Node, Option[(Int, List[Node])]]

    def longest(node: Node): Option[(Int, List[Node])] =
      memo.getOrElseUpdate(node, computeLongest(node))

    def computeLongest(node: Node): Option[(Int, List[Node])] =
      node match
        case Node.Root(module) =>
          if module == root then Some(0 -> List(node))
          else None

        case _ =>
          val parents = graph.getOrElse(node, mutable.ArrayBuffer.empty)
          parents.flatMap: parent =>
            longest(parent).map: (depth, path) =>
              val nextDepth = node match
                case Node.Package(_) => depth + 1
                case _               => depth
              (nextDepth, path :+ node)
          .maxByOption(_._1)

    val deepest = selectedPackages
      .flatMap(name => longest(Node.Package(name)))
      .maxByOption(_._1)

    deepest match
      case Some(depth, path) =>
        DepthInfo(depth, path.map(labelOf(_, rootSpecPath)).drop(1))
      case None =>
        DepthInfo(0, Nil)

  private def formatMissingPackage(
    name: String,
    constraints: List[(PackageConstraint, Node)],
    graph: DependencyGraph,
    rootSpecPath: Path,
  ): String =
    val lines = renderConstraintLines(name, constraints, graph, rootSpecPath)
    (s"package not found: $name" :: lines).mkString("\n")

  private def formatNoSatisfiableVersion(
    name: String,
    constraints: List[(PackageConstraint, Node)],
    graph: DependencyGraph,
    rootSpecPath: Path,
  ): String =
    val lines = renderConstraintLines(name, constraints, graph, rootSpecPath)
    (s"no satisfiable version available for $name" :: lines).mkString("\n")

  private def formatNoJoCompatibleVersion(
    name: String,
    constraints: List[(PackageConstraint, Node)],
    graph: DependencyGraph,
    pinned: Option[Version],
    joVersion: Version,
    required: String,
    rootSpecPath: Path,
  ): String =
    val lines = renderConstraintLines(name, constraints, graph, rootSpecPath)
    val pinLine = pinned.map(v => s"  The build spec pins $name to $v.").toList
    (
      s"no Jo-compatible version available for $name" ::
      lines :::
      List(
        "",
      ) :::
      pinLine :::
      List(
        s"  The selected Jo compiler is $joVersion.",
        s"  There are releases available for Jo $required.",
        "  Updating the project's jo version may allow resolution.",
      )
    ).mkString("\n")

  private def formatPinnedVersionConflict(
    name: String,
    constraints: List[(PackageConstraint, Node)],
    graph: DependencyGraph,
    pinnedVersion: Version,
    rootSpecPath: Path,
  ): String =
    val lines = renderConstraintLines(name, constraints, graph, rootSpecPath)
    (
      s"pinned version conflict for $name" ::
      lines :::
      List(
        "",
        s"  The build spec pins $name to $pinnedVersion.",
        "  That pinned version does not satisfy all dependency requirements.",
      )
    ).mkString("\n")

  private def formatPinnedVersionNotFound(
    name: String,
    constraints: List[(PackageConstraint, Node)],
    graph: DependencyGraph,
    pinnedVersion: Version,
    rootSpecPath: Path,
  ): String =
    val lines = renderConstraintLines(name, constraints, graph, rootSpecPath)
    (
      s"pinned version not found for $name" ::
      lines :::
      List(
        "",
        s"  The build spec pins $name to $pinnedVersion.",
        "  That exact release is not available.",
      )
    ).mkString("\n")

  private def formatLockVersionMismatch(
    name: String,
    constraints: List[(PackageConstraint, Node)],
    graph: DependencyGraph,
    lockedVersion: Version,
    rootSpecPath: Path,
  ): String =
    val lines = renderConstraintLines(name, constraints, graph, rootSpecPath)
    (
      s"lock file version mismatch for $name" ::
      lines :::
      List(
        "",
        s"  The lock file had already fixed $name to $lockedVersion.",
        "  Run `jo lock` to refresh the lock file.",
      )
    ).mkString("\n")

  private def formatLockedJoMismatch(
    name: String,
    lockedVersion: Version,
    requiredJo: VersionSpec,
    joVersion: Version,
  ): String =
    s"lock file Jo compiler mismatch for $name\n\n  The lock file fixed $name to $lockedVersion.\n  That package requires Jo ${requiredJo.show}, but the selected compiler is $joVersion."

  private def formatConstraintConflict(
    name: String,
    constraints: List[(PackageConstraint, Node)],
    graph: DependencyGraph,
    selected: Option[Version],
    pinned: Option[Version],
    rootSpecPath: Path,
  ): String =
    val lines = renderConstraintLines(name, constraints, graph, rootSpecPath)
    val notes =
      pinned.map(v => s"The build spec pins $name to $v.").toList :::
      selected.map: v =>
        s"Jo had already fixed $name to $v.\n  Jo resolves dependencies level by level and does not later switch to a larger version."
      .toList
    (s"conflicting requirements for $name" :: lines ::: notes.map("  " + _)).mkString("\n")

  private def renderConstraintLines(
    name: String,
    constraints: List[(PackageConstraint, Node)],
    graph: DependencyGraph,
    rootSpecPath: Path,
  ): List[String] =
    constraints
      .flatMap: (constraint, parent) =>
        renderConstraintPaths(name, parent, graph, rootSpecPath).map(path => (constraint.spec.show, path))
      .distinct
      .sortBy((spec, path) => (path, spec))
      .take(4)
      .map: (spec, path) =>
        s"  $path($spec)"

  private def renderConstraintPaths(
    name: String,
    parent: Node,
    graph: DependencyGraph,
    rootSpecPath: Path,
  ): List[String] =
    pathsToRoots(parent, graph).map: path =>
      (path.map(labelOf(_, rootSpecPath)) :+ name).mkString(" -> ")

  private def pathsToRoots(node: Node, graph: DependencyGraph): List[List[Node]] =
    val memo = mutable.Map.empty[Node, List[List[Node]]]

    def find(current: Node): List[List[Node]] =
      memo.getOrElseUpdate(
        current,
        current match
          case _: Node.Root =>
            List(List(current))

          case _ =>
            graph.get(current).toList.flatMap: parents =>
              parents.toList.flatMap(find).map(_ :+ current)
      )

    find(node)

  private def labelOf(node: Node, rootSpecPath: Path): String = node match
    case Node.Root(module) =>
      Project.moduleLabelFromSpec(rootSpecPath, rootSpecPath, module)
    case Node.Module(specPath, module) =>
      Project.moduleLabelFromSpec(rootSpecPath, specPath, module)
    case Node.Package(name) => name

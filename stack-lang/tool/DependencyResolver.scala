package tool

import java.nio.file.Path
import scala.collection.mutable

case class ResolvedPackage(
  name: String,
  version: Version,
  meta: PackageMeta,
  path: Path,
)

case class ResolutionResult(
  packages: List[ResolvedPackage],
  mainPackageDepth: Int,
  mainDeepestPath: List[String],
  testPackageDepth: Int,
  testDeepestPath: List[String],
)

case class PackageConstraint(name: String, spec: VersionSpec)

object DependencyResolver:
  private enum Node:
    case Root(name: String, module: ModuleKind)
    case Project(dir: Path, name: String)
    case Package(name: String)

  private type DependencyGraph = mutable.LinkedHashMap[Node, mutable.ArrayBuffer[Node]]

  /** Resolve registry/package dependencies for a resolved local project.
   *
   *  Algorithm:
   *
   *  1. Build the local project/module part of the dependency graph.
   *  2. Seed a work queue with the graph edges from local project/module nodes to direct package constraints.
   *  3. For each package name, accumulate all version constraints seen so far.
   *  4. The first time a package is processed, ask the PackageProvider for all available versions.
   *  5. Select the highest available version satisfying every collected constraint known at that moment.
   *  6. That version choice is fixed for the rest of resolution.
   *  7. Load that version's meta.toml and record both metadata and artifact path.
   *  8. Add reversed edges from each dependency package to its dependent package in the graph.
   *  9. If a later-discovered constraint does not match the already selected version,
   *     fail explicitly with a conflict error instead of revising the earlier choice.
   *  10. When the queue is exhausted, compute the final depth/path summaries from the graph.
   *
   *  Error behavior is explicit: failures are returned as Result.Err rather than
   *  being used for control flow via exceptions.
   */
  def resolveProject(project: Project)(using provider: PackageProvider): Result[ResolutionResult] =
    resolve(project, Map.empty)

  def resolveProject(project: Project, lock: LockFile)(using provider: PackageProvider): Result[ResolutionResult] =
    resolve(project, lock.packages.map(pkg => pkg.name -> pkg).toMap)

  private def resolve(
    project: Project,
    locked: Map[String, LockedPackage],
  )(using provider: PackageProvider): Result[ResolutionResult] =
    val (pendingSeeds, graph) = seedGraph(project)
    val packageConstraints = mutable.LinkedHashMap.empty[String, mutable.ArrayBuffer[(PackageConstraint, Node)]]
    val selectedPackages = mutable.LinkedHashMap.empty[String, ResolvedPackage]
    val queue = mutable.Queue.empty[(PackageConstraint, Node)]

    def addEdge(from: Node, to: Node): Unit =
      val parents = graph.getOrElseUpdate(from, mutable.ArrayBuffer.empty)

      if !parents.contains(to) then
        parents += to

    def addConstraint(
      name: String,
      constraint: PackageConstraint,
      parent: Node,
    ): mutable.ArrayBuffer[(PackageConstraint, Node)] =
      val current = packageConstraints.getOrElseUpdate(name, mutable.ArrayBuffer.empty)
      val alreadySeen = current.exists((existing, existingParent) =>
        existing.spec == constraint.spec && existingParent == parent
      )

      if !alreadySeen then
        current += ((constraint, parent))

      current

    def enqueueDeps(meta: PackageMeta, parent: Node): Unit =
      meta.dependencies.foreach: (depName, depConstraint) =>
        queue.enqueue(PackageConstraint(depName, depConstraint) -> parent)

    pendingSeeds.foreach(queue.enqueue(_))

    while queue.nonEmpty do
      val (current, parent) = queue.dequeue()
      val name = current.name
      val allConstraints = addConstraint(name, current, parent)
      addEdge(Node.Package(name), parent)

      selectedPackages.get(name) match
        case Some(selectedPackage) =>
          val version = selectedPackage.version

          if !current.spec.contains(version) then
            return Result.Err(formatMonotonicConflict(name, version, allConstraints.toList, graph))

        case None =>
          selectVersion(name, project.joVersion, allConstraints.toList, locked.get(name), graph).flatMap:
            (version, meta) =>
              provider.path(name, version).flatMap: archivePath =>
                val digestCheck = locked.get(name) match
                  case Some(pkg) if pkg.version == version.toString =>
                    validateLockedDigest(name, version, pkg)

                  case _ =>
                    Result.unit

                digestCheck.map: _ =>
                  selectedPackages(name) = ResolvedPackage(name, version, meta, archivePath)
                  enqueueDeps(meta, Node.Package(name))
          match
            case Result.Ok(_) =>

            case Result.Err(msg) => return Result.Err(msg)

    val packageNames = selectedPackages.keys.toList.sorted
    val packages = packageNames.map(selectedPackages)

    val (mainPackageDepth, mainDeepestPath) =
      deepestPath(graph, packageNames, ModuleKind.Main)

    val (testPackageDepth, testDeepestPath) =
      deepestPath(graph, packageNames, ModuleKind.Test)

    Result.Ok(
      ResolutionResult(
        packages,
        mainPackageDepth,
        mainDeepestPath,
        testPackageDepth,
        testDeepestPath,
      )
    )

  /** Pick the highest available version of `name` satisfying all collected constraints. */
  private def selectVersion(
    name: String,
    joVersion: Version,
    constraints: List[(PackageConstraint, Node)],
    locked: Option[LockedPackage],
    graph: DependencyGraph,
  )(using provider: PackageProvider): Result[(Version, PackageMeta)] =
    locked match
      case Some(pkg) =>
        parseLockedVersion(name, pkg) match
          case Result.Err(msg) =>
            return Result.Err(msg)

          case Result.Ok(version) if constraints.forall((constraint, _) => constraint.spec.contains(version)) =>
            provider.meta(name, version) match
              case Result.Ok(meta) if meta.jo.contains(joVersion) =>
                return Result.Ok(version -> meta)

              case Result.Ok(meta) =>
                return Result.Err(formatLockedJoMismatch(name, version, joVersion, meta.jo, constraints, graph))

              case Result.Err(msg) =>
                return Result.Err(msg)

          case Result.Ok(version) =>
            return Result.Err(formatLockedConstraintMismatch(name, version, constraints, graph))

      case None =>

    provider.versions(name) match
      case Result.Ok(versions) =>
        val sorted = versions.sorted.reverse
        val compatibleByConstraint = sorted.filter(v => constraints.forall((constraint, _) => constraint.spec.contains(v)))
        var firstMetaError: Option[String] = None
        var selected: Option[(Version, PackageMeta)] = None
        val it = compatibleByConstraint.iterator

        while it.hasNext && selected.isEmpty do
          val version = it.next()
          provider.meta(name, version) match
            case Result.Ok(meta) if meta.jo.contains(joVersion) =>
              selected = Some(version -> meta)

            case Result.Ok(_) =>
              ()

            case Result.Err(msg) if firstMetaError.isEmpty =>
              firstMetaError = Some(msg)

            case Result.Err(_) =>
              ()

        selected match
          case Some(result) =>
            Result.Ok(result)

          case None =>
            firstMetaError match
              case Some(msg) =>
                Result.Err(msg)

              case None if compatibleByConstraint.nonEmpty =>
                Result.Err(formatNoCompatibleJoVersion(name, joVersion, constraints, graph))

              case None =>
                Result.Err(formatNoSatisfiableVersion(name, constraints, graph))

      case Result.Err(msg) if msg == s"package not found: $name" =>
        Result.Err(formatMissingPackage(name, constraints, graph))

      case Result.Err(msg) =>
        Result.Err(msg)

  private def parseLockedVersion(name: String, pkg: LockedPackage): Result[Version] =
    Version.parse(pkg.version) match
      case Some(version) => Result.Ok(version)
      case None          => Result.Err(s"invalid locked version '${pkg.version}' for package '$name'")

  private def validateLockedDigest(
    name: String,
    version: Version,
    pkg: LockedPackage,
  )(using provider: PackageProvider): Result[Unit] =
    provider.digest(name, version).flatMap: actual =>
      if actual == pkg.sha512 then
        Result.unit
      else
        Result.Err(
          s"lock file digest mismatch for $name ${pkg.version}: expected ${pkg.sha512}, got $actual"
        )

  private def formatLockedConstraintMismatch(
    name: String,
    version: Version,
    constraints: List[(PackageConstraint, Node)],
    graph: DependencyGraph,
  ): String =
    val lines = renderConstraintLines(name, constraints, graph)
    val note = List(
      s"The lock file had already fixed $name to $version.",
      "Run `jo lock` to refresh the lock file.",
    )

    (s"lock file version mismatch for $name" :: lines ::: "" :: note.map("  " + _)).mkString("\n")

  private def formatLockedJoMismatch(
    name: String,
    version: Version,
    joVersion: Version,
    requiredJo: VersionSpec,
    constraints: List[(PackageConstraint, Node)],
    graph: DependencyGraph,
  ): String =
    val lines = renderConstraintLines(name, constraints, graph)
    val note = List(
      s"The lock file had already fixed $name to $version.",
      s"That package requires Jo ${requiredJo.show}, but the selected compiler is $joVersion.",
      "Run `jo lock` after selecting a compatible Jo compiler.",
    )

    (s"lock file Jo compiler mismatch for $name" :: lines ::: "" :: note.map("  " + _)).mkString("\n")

  private def formatMissingPackage(
    name: String,
    constraints: List[(PackageConstraint, Node)],
    graph: DependencyGraph,
  ): String =
    val lines = renderConstraintLines(name, constraints, graph)

    (s"package not found: $name" :: lines) match
      case header :: details if details.nonEmpty => (header :: details).mkString("\n")

      case _ => s"package not found: $name"

  private def formatNoSatisfiableVersion(
    name: String,
    constraints: List[(PackageConstraint, Node)],
    graph: DependencyGraph,
  ): String =
    val lines = renderConstraintLines(name, constraints, graph)

    (s"no satisfiable version available for $name" :: lines) match
      case header :: details if details.nonEmpty => (header :: details).mkString("\n")

      case _ => s"no satisfiable version available for $name"

  private def formatNoCompatibleJoVersion(
    name: String,
    joVersion: Version,
    constraints: List[(PackageConstraint, Node)],
    graph: DependencyGraph,
  ): String =
    val lines = renderConstraintLines(name, constraints, graph)
    val note = List(
      s"The selected Jo compiler is $joVersion.",
      "All package versions satisfying the dependency constraints require a different Jo version.",
    )

    (s"no Jo-compatible version available for $name" :: lines ::: "" :: note.map("  " + _)).mkString("\n")

  private def formatMonotonicConflict(
    name: String,
    selected: Version,
    constraints: List[(PackageConstraint, Node)],
    graph: DependencyGraph,
  ): String =
    val lines = renderConstraintLines(name, constraints, graph)
    val note = List(
      s"Jo had already fixed $name to $selected when it was first selected.",
      "Jo resolves dependencies level by level and does not later switch to a larger version.",
    )

    (s"conflicting requirements for $name" :: lines) match
      case header :: details if details.nonEmpty => (header :: details ::: "" :: note.map("  " + _)).mkString("\n")

      case _ => s"conflicting requirements for $name"

  private def renderConstraintLines(
    name: String,
    constraints: List[(PackageConstraint, Node)],
    graph: DependencyGraph,
  ): List[String] =
    val distinct = constraints
      .flatMap: (constraint, parent) =>
        renderConstraintPaths(name, parent, graph).map(path => (constraint.spec.show, path))
      .distinct
      .groupBy(identity)
      .keys
      .toList
      .map(identity)
      .sortBy((show, spec) => (show, spec))

    distinct.take(2).map: (spec, show) =>
      s"  $show requires $spec"

  private def renderConstraintPaths(name: String, parent: Node, graph: DependencyGraph): List[String] =
    pathsToRoots(parent, graph).map: path =>
      val labels = (path.map:
        case Node.Root(rootName, ModuleKind.Main) => rootName
        case Node.Root(rootName, ModuleKind.Test) => s"$rootName [test]"
        case Node.Project(_, projectName)         => projectName
        case Node.Package(packageName)            => packageName
      ) :+ name
      labels.mkString(" -> ")

  private def pathsToRoots(node: Node, graph: DependencyGraph): List[List[Node]] =
    val memo = mutable.Map.empty[Node, List[List[Node]]]

    def find(current: Node): List[List[Node]] =
      memo.getOrElseUpdate(
        current,
        current match
          case root: Node.Root =>
            List(List(root))

          case _ =>
            graph.get(current).toList.flatMap: parents =>
              parents.toList.flatMap(find).map(_ :+ current)
      )

    find(node)

  private def seedGraph(project: Project): (List[(PackageConstraint, Node)], DependencyGraph) =
    val graph = mutable.LinkedHashMap.empty[Node, mutable.ArrayBuffer[Node]]
    val pending = mutable.ListBuffer.empty[(PackageConstraint, Node)]

    def addEdge(from: Node, to: Node): Unit =
      val parents = graph.getOrElseUpdate(from, mutable.ArrayBuffer.empty)

      if !parents.contains(to) then
        parents += to

    def walkMain(current: Project, parent: Node, seen: mutable.Set[Path]): Unit =
      if seen.add(current.dir) then
        current.main.dependencies.foreach:
          case (name, DepSpec(DepSource.Registry(constraint), _)) =>
            pending += (PackageConstraint(name, constraint) -> parent)

          case _ =>
            ()

        current.deps.foreach: dep =>
          val child = Node.Project(dep.project.dir, dep.project.name)
          addEdge(child, parent)
          walkMain(dep.project, child, seen)

    val mainRoot: Node.Root = Node.Root(project.name, ModuleKind.Main)
    walkMain(project, mainRoot, mutable.Set.empty)

    project.test.foreach: test =>
      val testRoot: Node.Root = Node.Root(project.name, ModuleKind.Test)

      test.dependencies.foreach:
        case (name, DepSpec(DepSource.Registry(constraint), _)) =>
          pending += (PackageConstraint(name, constraint) -> testRoot)

        case _ =>
          ()

      project.testDeps.foreach: dep =>
        val child = Node.Project(dep.project.dir, dep.project.name)
        addEdge(child, testRoot)
        walkMain(dep.project, child, mutable.Set.empty)

    (pending.toList, graph)

  private def deepestPath(graph: DependencyGraph, selectedPackages: List[String], kind: ModuleKind): (Int, List[String]) =
    val memo = mutable.Map.empty[Node, (Int, List[Node])]

    def longest(node: Node): (Int, List[Node]) =
      memo.getOrElseUpdate(node, computeLongest(node))

    def computeLongest(node: Node): (Int, List[Node]) =
      node match
        case Node.Root(_, k) =>
          if k == kind then
            0 -> List(node)

          else
            // ignore path that has a different root
            -10000 -> List(node)

        case _ =>
          val parents = graph(node)
          parents.map: parent =>
            val (depth, path) = longest(parent)
            val nextDepth = node match
              case Node.Package(_) => depth + 1
              case _               => depth
            (nextDepth, path :+ node)
          .maxBy(_._1)

    end computeLongest

    val deepest = selectedPackages
      .map(name => longest(Node.Package(name)))
      .maxByOption(_._1)

    deepest match
      case Some(depth, path) =>
        val labels = path.map:
          case Node.Root(name, _)     => name
          case Node.Project(_, name)  => name
          case Node.Package(name)  => name
        .drop(1)

        (depth, labels)

      case _ =>
        (0, Nil)

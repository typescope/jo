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

  private case class Origin(root: Node.Root, parent: Node)

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
   *  10. When the queue is exhausted, topologically order the resolved packages so
   *      dependencies appear before dependents.
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
    val packageConstraints = mutable.LinkedHashMap.empty[String, mutable.ArrayBuffer[(PackageConstraint, Origin)]]
    val selectedPackages = mutable.LinkedHashMap.empty[String, ResolvedPackage]
    val queue = mutable.Queue.empty[(PackageConstraint, Origin)]

    def addEdge(from: Node, to: Node): Unit =
      val parents = graph.getOrElseUpdate(from, mutable.ArrayBuffer.empty)

      if !parents.contains(to) then
        parents += to

    def addConstraint(
      name: String,
      constraint: PackageConstraint,
      origin: Origin,
    ): mutable.ArrayBuffer[(PackageConstraint, Origin)] =
      val current = packageConstraints.getOrElseUpdate(name, mutable.ArrayBuffer.empty)
      val alreadySeen = current.exists((existing, existingOrigin) =>
        existing.spec == constraint.spec && existingOrigin == origin
      )

      if !alreadySeen then
        current += ((constraint, origin))

      current

    def enqueueDeps(meta: PackageMeta, root: Node.Root, parent: Node): Unit =
      meta.dependencies.foreach: (depName, depConstraint) =>
        queue.enqueue(PackageConstraint(depName, depConstraint) -> Origin(root, parent))

    pendingSeeds.foreach(queue.enqueue(_))

    while queue.nonEmpty do
      val (current, origin) = queue.dequeue()
      val name = current.name
      val allConstraints = addConstraint(name, current, origin)
      addEdge(Node.Package(name), origin.parent)

      selectedPackages.get(name) match
        case Some(selectedPackage) =>
          val version = selectedPackage.version

          if !current.spec.contains(version) then
            return Result.Err(formatMonotonicConflict(name, version, allConstraints.toList, graph))

        case None =>
          selectVersion(name, allConstraints.toList, locked.get(name), graph).flatMap: version =>
            provider.meta(name, version).flatMap: meta =>
              provider.path(name, version).flatMap: archivePath =>
                val digestCheck = locked.get(name) match
                  case Some(pkg) if pkg.version == version.toString =>
                    validateLockedDigest(name, version, pkg)

                  case _ =>
                    Result.unit

                digestCheck.map: _ =>
                  selectedPackages(name) = ResolvedPackage(name, version, meta, archivePath)
                  enqueueDeps(meta, origin.root, Node.Package(name))
          match
            case Result.Ok(_) =>

            case Result.Err(msg) => return Result.Err(msg)

    val allMetas = selectedPackages.view.mapValues(_.meta).toMap

    topoOrder(allMetas).map: orderedNames =>
      val packages = orderedNames.map(selectedPackages)
      val (mainPackageDepth, mainDeepestPath) =
        deepestPath(graph, selectedPackages, Node.Root(project.name, ModuleKind.Main))
      val (testPackageDepth, testDeepestPath) =
        deepestPath(graph, selectedPackages, Node.Root(project.name, ModuleKind.Test))
      ResolutionResult(
        packages,
        mainPackageDepth,
        mainDeepestPath,
        testPackageDepth,
        testDeepestPath,
      )

  /** Pick the highest available version of `name` satisfying all collected constraints. */
  private def selectVersion(
    name: String,
    constraints: List[(PackageConstraint, Origin)],
    locked: Option[LockedPackage],
    graph: DependencyGraph,
  )(using provider: PackageProvider): Result[Version] =
    locked match
      case Some(pkg) =>
        parseLockedVersion(name, pkg) match
          case Result.Err(msg) =>
            return Result.Err(msg)

          case Result.Ok(version) if constraints.forall((constraint, _) => constraint.spec.contains(version)) =>
            return Result.Ok(version)

          case Result.Ok(version) =>
            return Result.Err(formatLockedConstraintMismatch(name, version, constraints, graph))

      case None =>

    provider.versions(name) match
      case Result.Ok(versions) =>
        val sorted = versions.sorted.reverse
        sorted.find(v => constraints.forall((constraint, _) => constraint.spec.contains(v))) match
          case Some(v) => Result.Ok(v)

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
    constraints: List[(PackageConstraint, Origin)],
    graph: DependencyGraph,
  ): String =
    val lines = renderConstraintLines(name, constraints, graph)
    val note = List(
      s"The lock file had already fixed $name to $version.",
      "Run `jo lock` to refresh the lock file.",
    )

    (s"lock file version mismatch for $name" :: lines ::: "" :: note.map("  " + _)).mkString("\n")

  private def formatMissingPackage(
    name: String,
    constraints: List[(PackageConstraint, Origin)],
    graph: DependencyGraph,
  ): String =
    val lines = renderConstraintLines(name, constraints, graph)

    (s"package not found: $name" :: lines) match
      case header :: details if details.nonEmpty => (header :: details).mkString("\n")

      case _ => s"package not found: $name"

  private def formatNoSatisfiableVersion(
    name: String,
    constraints: List[(PackageConstraint, Origin)],
    graph: DependencyGraph,
  ): String =
    val lines = renderConstraintLines(name, constraints, graph)

    (s"no satisfiable version available for $name" :: lines) match
      case header :: details if details.nonEmpty => (header :: details).mkString("\n")

      case _ => s"no satisfiable version available for $name"

  private def formatMonotonicConflict(
    name: String,
    selected: Version,
    constraints: List[(PackageConstraint, Origin)],
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
    constraints: List[(PackageConstraint, Origin)],
    graph: DependencyGraph,
  ): List[String] =
    val distinct = constraints
      .groupBy((constraint, origin) => (constraint.spec.show, renderConstraintPath(name, origin, graph)))
      .keys
      .toList
      .map((spec, show) => (spec, show))
      .sortBy((show, spec) => (show, spec))

    distinct.take(2).map: (spec, show) =>
      s"  $show requires $spec"

  private def renderConstraintPath(name: String, origin: Origin, graph: DependencyGraph): String =
    val path = pathToRoot(origin.parent, origin.root, graph).getOrElse(List(origin.root))
    val labels = (path.map:
      case Node.Root(rootName, ModuleKind.Main) => rootName
      case Node.Root(rootName, ModuleKind.Test) => s"$rootName [test]"
      case Node.Project(_, projectName)         => projectName
      case Node.Package(packageName)            => packageName
    ) :+ name
    labels.mkString(" -> ")

  private def pathToRoot(node: Node, root: Node.Root, graph: DependencyGraph): Option[List[Node]] =
    val memo = mutable.Map.empty[Node, Option[List[Node]]]

    def find(current: Node): Option[List[Node]] =
      memo.getOrElseUpdate(
        current,
        if current == root then
          Some(List(root))
        else
          graph.get(current).flatMap: parents =>
            parents.iterator.flatMap(find).toList.headOption.map(_ :+ current)
      )

    find(node)

  /** Order resolved packages so each package appears after all of its resolved dependencies. */
  private def topoOrder(metas: Map[String, PackageMeta]): Result[List[String]] =
    val ordered = mutable.ListBuffer.empty[String]
    val visiting = mutable.Set.empty[String]
    val visited = mutable.Set.empty[String]

    def visit(name: String): Result[Unit] =
      if visited.contains(name) then return Result.unit
      if visiting.contains(name) then return Result.Err(s"circular registry dependency detected: $name")

      visiting += name
      val deps = metas.get(name).toList.flatMap(_.dependencies.keys).filter(metas.contains).toList.sorted
      val it = deps.iterator

      while it.hasNext do
        visit(it.next()) match
          case Result.Ok(_) =>
          case err @ Result.Err(_) => return err

      visiting -= name
      visited += name
      ordered += name
      Result.unit

    val names = metas.keys.toList.sorted
    val it = names.iterator

    while it.hasNext do
      visit(it.next()) match
        case Result.Ok(_) =>
        case Result.Err(msg) => return Result.Err(msg)

    Result.Ok(ordered.toList)

  private def seedGraph(project: Project): (List[(PackageConstraint, Origin)], DependencyGraph) =
    val graph = mutable.LinkedHashMap.empty[Node, mutable.ArrayBuffer[Node]]
    val pending = mutable.ListBuffer.empty[(PackageConstraint, Origin)]
    val seenMainProjects = mutable.Set.empty[Path]

    def addEdge(from: Node, to: Node): Unit =
      val parents = graph.getOrElseUpdate(from, mutable.ArrayBuffer.empty)

      if !parents.contains(to) then
        parents += to

    def walkMain(current: Project, parent: Node, root: Node.Root): Unit =
      if !seenMainProjects.add(current.dir) then
        ()
      else
        current.main.dependencies.foreach:
          case (name, DepSpec(DepSource.Registry(constraint), _)) =>
            pending += (PackageConstraint(name, constraint) -> Origin(root, parent))

          case _ =>
            ()

        current.deps.foreach: dep =>
          val child = Node.Project(dep.project.dir, dep.project.name)
          addEdge(child, parent)
          walkMain(dep.project, child, root)

    val mainRoot: Node.Root = Node.Root(project.name, ModuleKind.Main)
    walkMain(project, mainRoot, mainRoot)

    project.test.foreach: test =>
      val testRoot: Node.Root = Node.Root(project.name, ModuleKind.Test)

      test.dependencies.foreach:
        case (name, DepSpec(DepSource.Registry(constraint), _)) =>
          pending += (PackageConstraint(name, constraint) -> Origin(testRoot, testRoot))

        case _ =>
          ()

      project.testDeps.foreach: dep =>
        val child = Node.Project(dep.project.dir, dep.project.name)
        addEdge(child, testRoot)
        walkMain(dep.project, child, testRoot)

    (pending.toList, graph)

  private def deepestPath(
    graph: DependencyGraph,
    selectedPackages: collection.Map[String, ResolvedPackage],
    root: Node.Root,
  ): (Int, List[String]) =
    val memo = mutable.Map.empty[Node, Option[(Int, List[Node])]]

    def best(node: Node): Option[(Int, List[Node])] =
      memo.getOrElseUpdate(
        node,
        if node == root then
          Some(0 -> List(root))
        else
          graph.get(node).flatMap: parents =>
            parents.iterator
              .flatMap(best)
              .map: (depth, path) =>
                val nextDepth = node match
                  case Node.Package(_) => depth + 1
                  case _               => depth
                (nextDepth, path :+ node)
              .toList
              .sortBy(-_._1)
              .headOption
      )

    val deepest = selectedPackages.keysIterator
      .flatMap(name => best(Node.Package(name)))
      .toList
      .sortBy(-_._1)
      .headOption

    deepest match
      case Some((depth, path)) =>
        val labels = path.drop(1).map:
          case Node.Root(_, _)     => ""
          case Node.Project(_, n)  => n
          case Node.Package(name)  => name
        .filter(_.nonEmpty)
        (depth, labels)

      case None =>
        (0, Nil)

package tool

import scala.collection.mutable

case class ResolvedPackage(
  name: String,
  version: Version,
  meta: PackageMeta,
  path: java.nio.file.Path,
)

case class PackageConstraint(name: String, spec: VersionSpec)

object DependencyResolver:
  private case class Trace(rootName: String, module: ModuleKind, path: List[String]):
    def append(name: String): Trace =
      copy(path = path :+ name)

    def render: String =
      val rootLabel = module match
        case ModuleKind.Main => rootName
        case ModuleKind.Test => s"$rootName [test]"

      (rootLabel :: path).mkString(" -> ")

  /** Resolve registry/package dependencies for a root build spec.
   *
   *  Algorithm:
   *
   *  1. Seed a work queue with the root spec's direct registry dependencies.
   *  2. For each package name, accumulate all version constraints seen so far.
   *  3. The first time a package is processed, ask the PackageProvider for all available versions.
   *  4. Select the highest available version satisfying every collected constraint known at that moment.
   *  5. That version choice is fixed for the rest of resolution.
   *  6. Load that version's meta.toml and record both metadata and artifact path.
   *  7. Enqueue that package version's direct dependencies from meta.toml so their
   *     constraints are incorporated into the workspace-wide package set.
   *  8. If a later-discovered constraint does not match the already selected version,
   *     fail explicitly with a conflict error instead of revising the earlier choice.
   *  9. When the queue is exhausted, topologically order the resolved packages so
   *     dependencies appear before dependents.
   *
   *  Error behavior is explicit: failures are returned as Result.Err rather than
   *  being used for control flow via exceptions.
   */
  def resolveSpec(spec: BuildSpec)(using provider: PackageProvider): Result[List[ResolvedPackage]] =
    resolve(rootSeeds(spec), Map.empty)

  def resolveProject(project: Project)(using provider: PackageProvider): Result[List[ResolvedPackage]] =
    resolve(projectSeeds(project), Map.empty)

  def resolveProject(project: Project, lock: LockFile)(using provider: PackageProvider): Result[List[ResolvedPackage]] =
    resolve(projectSeeds(project), lock.packages.map(pkg => pkg.name -> pkg).toMap)

  private def resolve(
    seeds: List[(PackageConstraint, Trace)],
    locked: Map[String, LockedPackage],
  )(using provider: PackageProvider): Result[List[ResolvedPackage]] =
    val constraints = mutable.LinkedHashMap.empty[String, List[(PackageConstraint, Trace)]]
    val seen = mutable.LinkedHashSet.empty[(String, VersionSpec, Trace)]
    val selected = mutable.LinkedHashMap.empty[String, Version]
    val metas = mutable.LinkedHashMap.empty[String, PackageMeta]
    val paths = mutable.LinkedHashMap.empty[String, java.nio.file.Path]
    val queue = mutable.Queue.empty[(PackageConstraint, Trace)]

    seeds.foreach: seed =>
      queue.enqueue(seed)

    while queue.nonEmpty do
      val (current, trace) = queue.dequeue()
      val name = current.name
      val currentConstraints = constraints.getOrElse(name, Nil)
      val alreadySeen = seen.contains((name, current.spec, trace))

      if !alreadySeen then
        seen += ((name, current.spec, trace))
        constraints(name) = (current, trace) :: currentConstraints

      selected.get(name) match
        case Some(version) =>
          if !current.spec.contains(version) then
            return Result.Err(formatMonotonicConflict(name, version, constraints(name).reverse))

        case None =>
          selectVersion(name, constraints(name).reverse, locked.get(name)).flatMap: version =>
            selected(name) = version
            provider.meta(name, version).flatMap: meta =>
              provider.path(name, version).flatMap: archivePath =>
                val digestCheck = locked.get(name) match
                  case Some(pkg) if pkg.version == version.toString =>
                    validateLockedDigest(name, version, pkg)

                  case _ =>
                    Result.unit

                digestCheck.map: _ =>
                  metas(name) = meta
                  paths(name) = archivePath

                  meta.dependencies.foreach: (depName, depConstraint) =>
                    val dep = PackageConstraint(depName, depConstraint)
                    queue.enqueue(dep -> trace.append(depName))
          match
            case Result.Ok(_) =>

            case Result.Err(msg) => return Result.Err(msg)

    topoOrder(metas.toMap).map: orderedNames =>
      orderedNames.map: name =>
        ResolvedPackage(name, selected(name), metas(name), paths(name))

  /** Pick the highest available version of `name` satisfying all collected constraints. */
  private def selectVersion(
    name: String,
    constraints: List[(PackageConstraint, Trace)],
    locked: Option[LockedPackage],
  )(using provider: PackageProvider): Result[Version] =
    locked match
      case Some(pkg) =>
        parseLockedVersion(name, pkg) match
          case Result.Err(msg) =>
            return Result.Err(msg)

          case Result.Ok(version) if constraints.forall((constraint, _) => constraint.spec.contains(version)) =>
            return Result.Ok(version)

          case Result.Ok(version) =>
            return Result.Err(formatLockedConstraintMismatch(name, version, constraints))

      case None =>

    provider.versions(name) match
      case Result.Ok(versions) =>
        val sorted = versions.sorted.reverse
        sorted.find(v => constraints.forall((constraint, _) => constraint.spec.contains(v))) match
          case Some(v) => Result.Ok(v)

          case None =>
            Result.Err(formatNoSatisfiableVersion(name, constraints))

      case Result.Err(msg) if msg == s"package not found: $name" =>
        Result.Err(formatMissingPackage(name, constraints))

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
    constraints: List[(PackageConstraint, Trace)],
  ): String =
    val distinct = constraints
      .groupBy((constraint, trace) => (constraint.spec.show, trace.render))
      .keys
      .toList
      .map((spec, show) => (spec, show))
      .sortBy((show, spec) => (show, spec))

    val lines = distinct.take(2).map: (spec, show) =>
      s"  $show requires $spec"

    val note = List(
      s"The lock file had already fixed $name to $version.",
      "Run `jo lock` to refresh the lock file.",
    )

    (s"lock file version mismatch for $name" :: lines ::: "" :: note.map("  " + _)).mkString("\n")

  private def formatMissingPackage(name: String, constraints: List[(PackageConstraint, Trace)]): String =
    val distinct = constraints
      .groupBy((constraint, trace) => (constraint.spec.show, trace.render))
      .keys
      .toList
      .map((spec, show) => (spec, show))
      .sortBy((show, spec) => (show, spec))

    val lines = distinct.take(2).map: (spec, show) =>
      s"  $show requires $spec"

    (s"package not found: $name" :: lines) match
      case header :: details if details.nonEmpty => (header :: details).mkString("\n")

      case _ => s"package not found: $name"

  private def formatNoSatisfiableVersion(name: String, constraints: List[(PackageConstraint, Trace)]): String =
    val distinct = constraints
      .groupBy((constraint, trace) => (constraint.spec.show, trace.render))
      .keys
      .toList
      .map((spec, show) => (spec, show))
      .sortBy((show, spec) => (show, spec))

    val lines = distinct.take(2).map: (spec, show) =>
      s"  $show requires $spec"

    (s"no satisfiable version available for $name" :: lines) match
      case header :: details if details.nonEmpty => (header :: details).mkString("\n")

      case _ => s"no satisfiable version available for $name"

  private def formatMonotonicConflict(name: String, selected: Version, constraints: List[(PackageConstraint, Trace)]): String =
    val distinct = constraints
      .groupBy((constraint, trace) => (constraint.spec.show, trace.render))
      .keys
      .toList
      .map((spec, show) => (spec, show))
      .sortBy((show, spec) => (show, spec))

    val lines = distinct.take(2).map: (spec, show) =>
      s"  $show requires $spec"

    val note = List(
      s"Jo had already fixed $name to $selected when it was first selected.",
      "Jo resolves dependencies level by level and does not later switch to a larger version.",
    )

    (s"conflicting requirements for $name" :: lines) match
      case header :: details if details.nonEmpty => (header :: details ::: "" :: note.map("  " + _)).mkString("\n")

      case _ => s"conflicting requirements for $name"

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

  private def rootSeeds(spec: BuildSpec): List[(PackageConstraint, Trace)] =
    val rootMain = moduleSeeds(spec.name, ModuleKind.Main, spec.main.dependencies)
    val rootTest = spec.test.toList.flatMap: test =>
      moduleSeeds(spec.name, ModuleKind.Test, test.dependencies)

    (rootMain ++ rootTest).distinct

  private def moduleSeeds(rootName: String, module: ModuleKind, deps: Map[String, DepSpec]): List[(PackageConstraint, Trace)] =
    deps.toList.flatMap:
      case (name, DepSpec(DepSource.Registry(constraint), _)) =>
        Some(PackageConstraint(name, constraint) -> Trace(rootName, module, List(name)))

      case _ =>
        None

  private def projectSeeds(project: Project): List[(PackageConstraint, Trace)] =
    def walk(project: Project, trace: Trace, test: Boolean = false): List[(PackageConstraint, Trace)] =
      val depEntries =
        if test then project.spec.test.toList.flatMap(_.dependencies)
        else project.spec.main.dependencies.toList

      depEntries.flatMap:
        case (name, DepSpec(DepSource.Registry(constraint), _)) =>
          val nextTrace = trace.append(name)
          Some(PackageConstraint(name, constraint) -> nextTrace)

        case (_, DepSpec(DepSource.Path(relPath, _), _)) =>
          val depDir = project.dir.resolve(relPath).normalize().toRealPath()
          val candidates = if test then project.testDeps else project.deps
          candidates.find(_.dir == depDir).toList.flatMap(dep => walk(dep, trace.append(dep.spec.name)))

    val rootMain = walk(project, Trace(project.spec.name, ModuleKind.Main, Nil))
    val rootTest = project.spec.test.toList.flatMap: _ =>
      walk(project, Trace(project.spec.name, ModuleKind.Test, Nil), test = true)

    (rootMain ++ rootTest).distinct

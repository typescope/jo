package tool

import scala.collection.mutable

case class ResolvedPackage(
  name: String,
  version: Version,
  meta: PackageMeta,
  path: java.nio.file.Path,
)

private case class ConstraintSource(
  constraint: String,
  path: List[String],
)

object DependencyResolver:
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
   *     constraints are incorporated into the graph.
   *  8. If a later-discovered constraint does not match the already selected version,
   *     fail explicitly with a conflict error instead of revising the earlier choice.
   *  9. When the queue is exhausted, topologically order the resolved packages so
   *     dependencies appear before dependents.
   *
   *  Error behavior is explicit: failures are returned as Result.Err rather than
   *  being used for control flow via exceptions.
   */
  def resolve(spec: BuildSpec)(using provider: PackageProvider): Result[List[ResolvedPackage]] =
    val constraints = mutable.LinkedHashMap.empty[String, List[ConstraintSource]]
    val selected = mutable.LinkedHashMap.empty[String, Version]
    val metas = mutable.LinkedHashMap.empty[String, PackageMeta]
    val paths = mutable.LinkedHashMap.empty[String, java.nio.file.Path]
    val queue = mutable.Queue.empty[(String, ConstraintSource)]

    spec.main.dependencies.foreach:
      case (name, DepSpec(DepSource.Registry(constraint), _)) =>
        queue.enqueue((name, ConstraintSource(constraint, List(spec.name, name))))

      case _ =>

    while queue.nonEmpty do
      val (name, source) = queue.dequeue()
      val currentConstraints = constraints.getOrElse(name, Nil)
      if !currentConstraints.exists(s => s.constraint == source.constraint && s.path == source.path) then
        constraints(name) = source :: currentConstraints

      selected.get(name) match
        case Some(version) =>
          if !Version.satisfiesConstraint(version, source.constraint) then
            return Result.Err(formatMonotonicConflict(name, version, constraints(name).reverse))

        case None =>
          selectVersion(name, constraints(name).reverse).flatMap: version =>
            selected(name) = version
            provider.meta(name, version).flatMap: meta =>
              provider.path(name, version).map: path =>
                metas(name) = meta
                paths(name) = path

                val parentPath = source.path.dropRight(1) :+ name
                meta.dependencies.foreach: (depName, depConstraint) =>
                  queue.enqueue((depName, ConstraintSource(depConstraint, parentPath :+ depName)))
          match
            case Result.Ok(_) =>

            case Result.Err(msg) => return Result.Err(msg)

    topoOrder(metas.toMap).map: orderedNames =>
      orderedNames.map: name =>
        ResolvedPackage(name, selected(name), metas(name), paths(name))

  /** Pick the highest available version of `name` satisfying all collected constraints. */
  private def selectVersion(name: String, constraints: List[ConstraintSource])(using provider: PackageProvider): Result[Version] =
    provider.versions(name) match
      case Result.Ok(versions) =>
        val sorted = versions.sorted.reverse
        sorted.find(v => constraints.forall(c => Version.satisfiesConstraint(v, c.constraint))) match
          case Some(v) => Result.Ok(v)

          case None =>
            Result.Err(formatNoSatisfiableVersion(name, constraints))

      case Result.Err(msg) if msg == s"package not found: $name" =>
        Result.Err(formatMissingPackage(name, constraints))

      case Result.Err(msg) =>
        Result.Err(msg)

  private def formatMissingPackage(name: String, constraints: List[ConstraintSource]): String =
    val distinct = constraints
      .groupBy(c => (c.constraint, c.path))
      .keys
      .toList
      .map((constraint, path) => ConstraintSource(constraint, path))
      .sortBy(c => (c.path.mkString("\u0000"), c.constraint))

    val lines = distinct.take(2).map: source =>
      s"  ${source.path.mkString(" -> ")} requires ${source.constraint}"

    (s"package not found: $name" :: lines) match
      case header :: details if details.nonEmpty => (header :: details).mkString("\n")

      case _ => s"package not found: $name"

  private def formatNoSatisfiableVersion(name: String, constraints: List[ConstraintSource]): String =
    val distinct = constraints
      .groupBy(c => (c.constraint, c.path))
      .keys
      .toList
      .map((constraint, path) => ConstraintSource(constraint, path))
      .sortBy(c => (c.path.mkString("\u0000"), c.constraint))

    val lines = distinct.take(2).map: source =>
      s"  ${source.path.mkString(" -> ")} requires ${source.constraint}"

    (s"no satisfiable version available for $name" :: lines) match
      case header :: details if details.nonEmpty => (header :: details).mkString("\n")

      case _ => s"no satisfiable version available for $name"

  private def formatMonotonicConflict(name: String, selected: Version, constraints: List[ConstraintSource]): String =
    val distinct = constraints
      .groupBy(c => (c.constraint, c.path))
      .keys
      .toList
      .map((constraint, path) => ConstraintSource(constraint, path))
      .sortBy(c => (c.path.mkString("\u0000"), c.constraint))

    val lines = distinct.take(2).map: source =>
      s"  ${source.path.mkString(" -> ")} requires ${source.constraint}"

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

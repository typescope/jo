package tool

object Info:
  def run(args: Array[String]): Unit =
    given PackageProvider = PackageProvider.default()
    print(result(args).orExit)

  def result(args: Array[String])(using provider: PackageProvider): Result[String] =
    parseQuery(args).flatMap: (name, versionOpt) =>
      provider.versions(name).flatMap: versions =>
        if versions.isEmpty then
          Result.Err(s"package not found: $name")
        else
          val selectedOpt = versionOpt match
            case Some(version) =>
              if versions.contains(version) then Some(version)
              else None

            case None =>
              Some(versions.max)

          selectedOpt match
            case None =>
              Result.Err(s"package version not found: $name@${versionOpt.nn}")

            case Some(version) =>
              provider.meta(name, version).map: meta =>
                render(meta, versions.sorted, versionOpt.isEmpty)

  private def parseQuery(args: Array[String]): Result[(String, Option[Version])] =
    args.toList match
      case query :: Nil =>
        query.indexOf('@') match
          case -1 =>
            Result.Ok(query -> None)

          case i =>
            val name = query.take(i)
            val rawVersion = query.drop(i + 1)

            if name.isEmpty || rawVersion.isEmpty then
              Result.Err("usage: jo info <pkg>[@<version>]\n")
            else
              Version.parse(rawVersion) match
                case Some(version) => Result.Ok(name -> Some(version))
                case None          => Result.Err(s"invalid package version '$rawVersion'")

      case _ =>
        Result.Err("usage: jo info <pkg>[@<version>]\n")

  private def render(meta: PackageMeta, versions: List[Version], inferredLatest: Boolean): String =
    val out = StringBuilder()
    out.append(s"name = ${str(meta.name)}\n")
    out.append(s"version = ${str(meta.version)}\n")

    if inferredLatest then
      out.append("selected = latest\n")

    out.append(s"available = ${versions.map(_.toString).map(str).mkString("[", ", ", "]")}\n")
    out.append(s"namespace = ${str(meta.namespace)}\n")
    out.append(s"ffi = ${str(meta.ffi)}\n")
    meta.description.foreach(d => out.append(s"description = ${str(d)}\n"))

    if meta.authors.nonEmpty then
      out.append(s"authors = ${meta.authors.map(str).mkString("[", ", ", "]")}\n")

    meta.homepage.foreach(h => out.append(s"homepage = ${str(h)}\n"))
    meta.license.foreach(l => out.append(s"license = ${str(l)}\n"))

    if meta.keywords.nonEmpty then
      out.append(s"keywords = ${meta.keywords.map(str).mkString("[", ", ", "]")}\n")

    if meta.dependencies.nonEmpty then
      out.append("dependencies:\n")

      meta.dependencies.toSeq.sortBy(_._1).foreach: (name, spec) =>
        out.append(s"  $name = ${str(spec.show)}\n")

    out.toString

  private def str(s: String): String =
    s"\"$s\""

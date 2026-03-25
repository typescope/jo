package tool

object ToolPrinter:
  def print(spec: BuildSpec): String =
    val sb = new StringBuilder
    sb.append(s"jo = ${str(spec.jo.show)}\n")
    sb.append(s"name = ${str(spec.name)}\n")
    spec.depth.foreach(d => sb.append(s"depth = $d\n"))

    spec.pkg match
      case None    => sb.append("build = app\n")
      case Some(p) =>
        sb.append("build = lib\n")
        sb.append(s"package.version = ${str(p.version)}\n")
        p.description.foreach(d => sb.append(s"package.description = ${str(d)}\n"))
        if p.authors.nonEmpty then sb.append(s"package.authors = ${strList(p.authors)}\n")
        p.homepage.foreach(h => sb.append(s"package.homepage = ${str(h)}\n"))
        p.license.foreach(l => sb.append(s"package.license = ${str(l)}\n"))
        if p.keywords.nonEmpty then sb.append(s"package.keywords = ${strList(p.keywords)}\n")
        p.ffi.foreach(f => sb.append(s"package.ffi = ${str(f)}\n"))

    sb.append("main:\n")
    appendSection(sb, spec.main, "  ")

    spec.test match
      case None    => sb.append("test = none\n")
      case Some(t) =>
        sb.append("test:\n")
        appendSection(sb, t, "  ")

    sb.toString.stripTrailing()

  def print(lock: LockFile): String =
    if lock.packages.isEmpty then return "packages = []"

    val sb = new StringBuilder

    for p <- lock.packages do
      sb.append(s"package:\n")
      sb.append(s"  name = ${str(p.name)}\n")
      sb.append(s"  version = ${str(p.version)}\n")
      sb.append(s"  sha512 = ${str(p.sha512)}\n")

    sb.toString.stripTrailing()

  def print(meta: PackageMeta): String =
    val sb = new StringBuilder
    sb.append(s"namespace = ${str(meta.namespace)}\n")
    sb.append(s"name = ${str(meta.name)}\n")
    sb.append(s"version = ${str(meta.version)}\n")
    sb.append(s"ffi = ${str(meta.ffi)}\n")
    meta.description.foreach(d => sb.append(s"description = ${str(d)}\n"))
    if meta.authors.nonEmpty then sb.append(s"authors = ${strList(meta.authors)}\n")
    meta.homepage.foreach(h => sb.append(s"homepage = ${str(h)}\n"))
    meta.license.foreach(l => sb.append(s"license = ${str(l)}\n"))
    if meta.keywords.nonEmpty then sb.append(s"keywords = ${strList(meta.keywords)}\n")

    if meta.dependencies.nonEmpty then
      sb.append("dependencies:\n")
      for (k, v) <- meta.dependencies.toSeq.sortBy(_._1) do
        sb.append(s"  $k = ${str(v.show)}\n")

    sb.toString.stripTrailing()

  private def appendSection(sb: StringBuilder, s: ModuleSpec, pad: String): Unit =
    val src = if s.src.isEmpty then "(default)" else strList(s.src)
    sb.append(s"${pad}src = $src\n")
    s.target.foreach(t => sb.append(s"${pad}target = ${str(t.flag)}\n"))

    if s.dependencies.nonEmpty then
      sb.append(s"${pad}dependencies:\n")

      for (k, d) <- s.dependencies.toSeq.sortBy(_._1) do
        val linkTag = if d.link == DepLink.Link then " [link]" else ""

        val sourceStr = d.source match
          case DepSource.Path(p, None)       => s"path ${str(p)}"
          case DepSource.Path(p, Some(spec)) => s"path ${str(p)} spec ${str(spec)}"
          case DepSource.Registry(c)         => s"registry ${str(c.show)}"

        sb.append(s"$pad  $k = $sourceStr$linkTag\n")

    if s.links.nonEmpty then
      sb.append(s"${pad}links:\n")

      for (k, v) <- s.links.toSeq.sortBy(_._1) do
        sb.append(s"$pad  ${str(k)} = ${str(v)}\n")

  private def str(s: String)              = s"\"$s\""
  private def strList(xs: List[String])   = xs.map(str).mkString("[", ", ", "]")

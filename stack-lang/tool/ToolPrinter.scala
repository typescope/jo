package tool

object ToolPrinter:
  def print(spec: BuildSpec): String =
    val sb = new StringBuilder
    sb.append(s"jo = ${str(spec.jo)}\n")
    sb.append(s"name = ${str(spec.name)}\n")
    spec.depth.foreach(d => sb.append(s"depth = $d\n"))

    spec.pkg match
      case None    => sb.append("build = app\n")
      case Some(p) =>
        sb.append("build = lib\n")
        sb.append(s"package.version = ${str(p.version)}\n")
        p.description.foreach(d => sb.append(s"package.description = ${str(d)}\n"))
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
    s"""|namespace = ${str(meta.namespace)}
        |name = ${str(meta.name)}
        |version = ${str(meta.version)}
        |ffi = ${str(meta.ffi)}""".stripMargin

  private def appendSection(sb: StringBuilder, s: ModuleSpec, pad: String): Unit =
    val src = if s.src.isEmpty then "(default)" else strList(s.src)
    sb.append(s"${pad}src = $src\n")
    s.target.foreach(t => sb.append(s"${pad}target = ${str(t)}\n"))

    if s.dependencies.nonEmpty then
      sb.append(s"${pad}dependencies:\n")

      for (k, d) <- s.dependencies.toSeq.sortBy(_._1) do
        val linkTag = if d.link == DepLink.Link then " [link]" else ""

        val sourceStr = d.source match
          case DepSource.Path(p, None)       => s"path ${str(p)}"
          case DepSource.Path(p, Some(spec)) => s"path ${str(p)} spec ${str(spec)}"
          case DepSource.Registry(c)         => s"registry ${str(c)}"

        sb.append(s"$pad  $k = $sourceStr$linkTag\n")

    if s.links.nonEmpty then
      sb.append(s"${pad}links:\n")

      for (k, v) <- s.links.toSeq.sortBy(_._1) do
        sb.append(s"$pad  ${str(k)} = ${str(v)}\n")

  private def str(s: String)              = s"\"$s\""
  private def strList(xs: List[String])   = xs.map(str).mkString("[", ", ", "]")

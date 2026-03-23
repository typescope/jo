package tool

object ToolPrinter:
  def print(spec: BuildSpec): String =
    val sb = new StringBuilder
    sb.append(s"namespace = ${str(spec.namespace)}\n")
    sb.append(s"version = ${str(spec.version)}\n")
    sb.append(s"ffi = ${str(spec.ffi)}\n")
    sb.append("main:\n")
    appendSection(sb, spec.main, "  ")
    spec.test match
      case None       => sb.append("test = none\n")
      case Some(test) => sb.append("test:\n"); appendSection(sb, test, "  ")
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

  private def appendSection(sb: StringBuilder, s: SectionSpec, pad: String): Unit =
    sb.append(s"${pad}src = ${strList(s.src)}\n")
    if s.dependencies.nonEmpty then
      sb.append(s"${pad}dependencies:\n")
      for (k, v) <- s.dependencies.toSeq.sortBy(_._1) do
        val depStr = v match
          case DepSpec.Path(p)         => s"path ${str(p)}"
          case DepSpec.Registry(c)     => s"registry ${str(c)}"
        sb.append(s"$pad  $k = $depStr\n")
    if s.links.nonEmpty then
      sb.append(s"${pad}links:\n")
      for (k, v) <- s.links.toSeq.sortBy(_._1) do
        sb.append(s"$pad  ${str(k)} = ${str(v)}\n")

  private def str(s: String)          = s"\"$s\""
  private def strList(xs: List[String]) = xs.map(str).mkString("[", ", ", "]")

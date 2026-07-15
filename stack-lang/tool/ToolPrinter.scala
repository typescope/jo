package tool

object ToolPrinter:
  def print(spec: BuildSpec): String =
    val sb = new StringBuilder
    sb.append(s"jo = ${str(spec.jo.show)}\n")
    spec.defaultModule.foreach(id => sb.append(s"default = ${str(id.value)}\n"))
    if spec.pinning.nonEmpty then
      sb.append("pinning:\n")
      for (name, version) <- spec.pinning.toSeq.sortBy(_._1) do
        sb.append(s"  $name = ${str(version.toString)}\n")

    spec.doc.foreach: d =>
      d.title.foreach(t => sb.append(s"doc.title = ${str(t)}\n"))
      if d.includePrivate then sb.append("doc.include-private = true\n")
      if d.includeSource then sb.append("doc.include-source = true\n")

    for module <- spec.modules do
      sb.append(s"module.${module.id.value}:\n")
      appendSection(sb, module.spec, "  ")

    if spec.commands.nonEmpty then
      sb.append("commands:\n")
      for (name, cmd) <- spec.commands.toSeq.sortBy(_._1) do
        sb.append(s"  $name = ${str(cmd)}\n")

    sb.toString.stripTrailing()

  def print(lock: LockFile): String =
    if lock.jo.isEmpty && lock.packages.isEmpty then return "packages = []"

    val sb = new StringBuilder

    lock.jo.foreach(v => sb.append(s"jo = ${str(v.toString)}\n"))

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
    sb.append(s"jo = ${str(meta.jo.show)}\n")
    sb.append(s"version = ${str(meta.version)}\n")
    sb.append(s"platform = ${str(meta.platform.value)}\n")
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
    sb.append(s"${pad}kind = ${str(s.kind.toString.toLowerCase)}\n")
    sb.append(s"${pad}src = ${strList(s.src)}\n")
    s.platform.foreach(p => sb.append(s"${pad}platform = ${str(p.value)}\n"))
    if s.enableFfi then sb.append(s"${pad}enable-ffi = true\n")
    s.depth.foreach(d => sb.append(s"${pad}depth = $d\n"))
    if s.compileOptions.nonEmpty then sb.append(s"${pad}compile-options = ${strList(s.compileOptions)}\n")

    s.pkg.foreach: p =>
      sb.append(s"${pad}package:\n")
      sb.append(s"${pad}  name = ${str(p.name)}\n")
      sb.append(s"${pad}  version = ${str(p.version)}\n")
      p.description.foreach(d => sb.append(s"${pad}  description = ${str(d)}\n"))
      if p.authors.nonEmpty then sb.append(s"${pad}  authors = ${strList(p.authors)}\n")
      p.homepage.foreach(h => sb.append(s"${pad}  homepage = ${str(h)}\n"))
      p.license.foreach(l => sb.append(s"${pad}  license = ${str(l)}\n"))
      if p.keywords.nonEmpty then sb.append(s"${pad}  keywords = ${strList(p.keywords)}\n")

    if s.dependencies.nonEmpty then
      sb.append(s"${pad}dependencies:\n")

      for d <- s.dependencies do
        val linkTag = if d.link == DepLink.Link then " [link]" else ""

        val sourceStr = d.source match
          case DepSource.Module(module, None)       => s"module ${str(module.value)}"
          case DepSource.Module(module, Some(path)) => s"path ${str(path)} module ${str(module.value)}"
          case DepSource.Registry(name, c)          => s"package ${str(name)} ${str(c.show)}"

        sb.append(s"$pad  - $sourceStr$linkTag\n")

    if s.links.nonEmpty then
      sb.append(s"${pad}links:\n")

      for link <- s.links do
        sb.append(s"$pad  - ${str(link.from)} -> ${str(link.to)}\n")

  private def str(s: String)              = s"\"$s\""
  private def strList(xs: List[String])   = xs.map(str).mkString("[", ", ", "]")

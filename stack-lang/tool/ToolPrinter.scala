package tool

object ToolPrinter:
  def print(spec: BuildSpec): String =
    val sb = new StringBuilder
    sb.append(s"jo = ${str(spec.jo.show)}\n")
    sb.append(s"name = ${str(spec.name)}\n")
    spec.depth.foreach(d => sb.append(s"depth = $d\n"))
    if spec.pinning.nonEmpty then
      sb.append("pinning:\n")
      for (name, version) <- spec.pinning.toSeq.sortBy(_._1) do
        sb.append(s"  $name = ${str(version.toString)}\n")

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
        p.runtime.foreach(r => sb.append(s"package.runtime = ${str(r)}\n"))

    spec.doc.foreach: d =>
      d.title.foreach(t => sb.append(s"doc.title = ${str(t)}\n"))
      if d.includePrivate then sb.append("doc.include-private = true\n")
      if d.includeSource then sb.append("doc.include-source = true\n")

    sb.append("main:\n")
    appendSection(sb, spec.main, "  ")

    spec.test match
      case None    => sb.append("test = none\n")
      case Some(t) =>
        sb.append("test:\n")
        appendSection(sb, t, "  ")

    sb.toString.stripTrailing()

  def print(lock: LockFile): String =
    if lock.jo.isEmpty && lock.packages.isEmpty && lock.gitDeps.isEmpty then return "packages = []"

    val sb = new StringBuilder

    lock.jo.foreach(v => sb.append(s"jo = ${str(v.toString)}\n"))

    for p <- lock.packages do
      sb.append(s"package:\n")
      sb.append(s"  name = ${str(p.name)}\n")
      sb.append(s"  version = ${str(p.version)}\n")
      sb.append(s"  sha512 = ${str(p.sha512)}\n")

    for dep <- lock.gitDeps do
      sb.append(s"git:\n")
      sb.append(s"  name = ${str(dep.name)}\n")
      sb.append(s"  url = ${str(dep.url)}\n")
      dep.source match
        case LockedGitSource.Precompiled(joyUrl, sha512) =>
          sb.append(s"  joy-url = ${str(joyUrl)}\n")
          sb.append(s"  sha512 = ${str(sha512)}\n")
        case LockedGitSource.Source(rev) =>
          sb.append(s"  rev = ${str(rev)}\n")

    sb.toString.stripTrailing()

  def print(meta: PackageMeta): String =
    val sb = new StringBuilder
    sb.append(s"namespace = ${str(meta.namespace)}\n")
    sb.append(s"name = ${str(meta.name)}\n")
    sb.append(s"jo = ${str(meta.jo.show)}\n")
    sb.append(s"version = ${str(meta.version)}\n")
    sb.append(s"runtime = ${str(meta.runtime)}\n")
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
    s.depth.foreach(d => sb.append(s"${pad}depth = $d\n"))

    if s.dependencies.nonEmpty then
      sb.append(s"${pad}dependencies:\n")

      for (k, d) <- s.dependencies.toSeq.sortBy(_._1) do
        val linkTag = if d.link == DepLink.Link then " [link]" else ""

        val sourceStr = d.source match
          case DepSource.Path(p, None)       => s"path ${str(p)}"
          case DepSource.Path(p, Some(spec)) => s"path ${str(p)} spec ${str(spec)}"
          case DepSource.Registry(c)         => s"registry ${str(c.show)}"
          case DepSource.Git(url, None)                    => s"git ${str(url)}"
          case DepSource.Git(url, Some(GitRef.Tag(t)))     => s"git ${str(url)} tag ${str(t)}"
          case DepSource.Git(url, Some(GitRef.Branch(b)))  => s"git ${str(url)} branch ${str(b)}"
          case DepSource.Git(url, Some(GitRef.Rev(r)))     => s"git ${str(url)} rev ${str(r)}"

        sb.append(s"$pad  $k = $sourceStr$linkTag\n")

    if s.links.nonEmpty then
      sb.append(s"${pad}links:\n")

      for (k, v) <- s.links.toSeq.sortBy(_._1) do
        sb.append(s"$pad  ${str(k)} = ${str(v)}\n")

  private def str(s: String)              = s"\"$s\""
  private def strList(xs: List[String])   = xs.map(str).mkString("[", ", ", "]")

package tool

/** Handles `jo versions` subcommands. */
object Versions:

  def run(args: Array[String], installer: Installer): Result[Unit] =
    args.headOption match
      case None | Some("list")  => list(installer)
      case Some("install")      => install(args.drop(1), installer)
      case Some("remove")       => remove(args.drop(1), installer)
      case Some("use")          => use(args.drop(1), installer)
      case Some(sub)            => Result.Err(s"unknown subcommand '$sub'\n$usage")

  private def list(installer: Installer): Result[Unit] =
    installer.getInstalledVersions().flatMap: installed =>
      val availableResult = installer.getVersions()

      val availableEmpty = availableResult match
        case Result.Ok(vs) => vs.isEmpty
        case Result.Err(_) => true

      if installed.isEmpty && availableEmpty then
        println("No compiler versions installed.")
        println(s"\nRun 'jo versions install <version>' to install one.")
        return Result.Ok(())

      if installed.nonEmpty then
        val active: Option[Version] = installer.activeVersion() match
          case Result.Err(msg) =>
            println(s"${Ansi.yellow("warning:")} could not determine active version: $msg")
            None
          case Result.Ok(v) => Some(v)

        println("Installed:\n")
        for v <- installed do
          val marker = if active.contains(v) then s" ${Ansi.green("(active)")}" else ""
          println(s"  $v$marker")

      availableResult match
        case Result.Err(msg) =>
          if installed.nonEmpty then println()
          println(s"${Ansi.yellow("warning:")} could not fetch available versions: $msg")

        case Result.Ok(vs) if vs.nonEmpty =>
          if installed.nonEmpty then println()
          println("Available:\n")
          printVersions(vs, None)

        case _ => ()

      Result.Ok(())

  private def printVersions(versions: List[Version], active: Option[Version]): Unit =
    if versions.length < 10 then
      for v <- versions do
        val marker = if active.contains(v) then s" ${Ansi.green("(active)")}" else ""
        println(s"  $v$marker")
    else
      val byMinor = versions.groupBy(v => (v.major, v.minor)).toList
        .sortBy((k, _) => k)
        .reverse
      for ((major, minor), vs) <- byMinor do
        val patches  = vs.map(_.patch).sorted
        val isActive = vs.exists(active.contains)
        val marker   = if isActive then s" ${Ansi.green("(active)")}" else ""
        println(s"  $major.$minor.{${compactPatches(patches)}}$marker")

  private def compactPatches(patches: List[Int]): String =
    val sorted = patches.sorted
    val runs = sorted.foldLeft(List.empty[List[Int]]):
      case (Nil, p)                              => List(List(p))
      case (run :: rest, p) if p == run.last + 1 => (run :+ p) :: rest
      case (runs, p)                             => List(p) :: runs
    runs.reverse.map:
      case List(p) => p.toString
      case run     => s"${run.head}-${run.last}"
    .mkString(", ")

  private def install(args: Array[String], installer: Installer): Result[Unit] =
    parseVersion(args, "install").flatMap: version =>
      print(s"Installing Jo $version...")
      installer.install(version) match
        case Result.Ok(_) =>
          println(s" ${Ansi.green("done")}")
          println(s"\nRun 'jo versions use $version' to activate it.")
          Result.Ok(())
        case Result.Err(msg) =>
          println()
          Result.Err(msg)

  private def remove(args: Array[String], installer: Installer): Result[Unit] =
    parseVersion(args, "remove").flatMap: version =>
      installer.activeVersion() match
        case Result.Ok(active) if active == version =>
          Result.Err(s"Jo $version is the active version — run 'jo versions use <other>' first")
        case _ =>
          installer.remove(version) match
            case Result.Ok(_)    => println(s"Removed Jo $version."); Result.Ok(())
            case Result.Err(msg) => Result.Err(msg)

  private def use(args: Array[String], installer: Installer): Result[Unit] =
    parseVersion(args, "use").flatMap: version =>
      installer.use(version) match
        case Result.Ok(_)    => println(s"Now using Jo $version."); Result.Ok(())
        case Result.Err(msg) => Result.Err(msg)

  private def parseVersion(args: Array[String], cmd: String): Result[Version] =
    args.headOption match
      case None    => Result.Err(s"'jo versions $cmd' requires a version argument")
      case Some(s) =>
        Version.parse(s) match
          case Some(v) => Result.Ok(v)
          case None    => Result.Err(s"invalid version '$s'")

  val usage: String =
    """|Usage:
       |  jo versions list                 List installed and available compiler versions
       |  jo versions install <version>    Download and install a compiler version
       |  jo versions use <version>        Switch the active compiler version
       |  jo versions remove <version>     Remove an installed compiler version
       |""".stripMargin

end Versions

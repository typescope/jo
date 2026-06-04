package tool

/** Handles `jo versions` subcommands. */
object Versions:

  def run(args: Array[String], installer: Installer): Unit =
    args.headOption match
      case None | Some("list")    => list(installer)
      case Some("install")        => install(args.drop(1), installer)
      case Some("remove")         => remove(args.drop(1), installer)
      case Some("use")            => use(args.drop(1), installer)
      case Some(sub)              =>
        System.err.println(s"error: unknown subcommand '$sub'")
        System.err.println(usage)
        sys.exit(1)

  private def list(installer: Installer): Unit =
    val installed = installer.getInstalledVersions()
    val active    = installer.activeVersion()

    if installed.isEmpty then
      println("No compiler versions installed.")
      println(s"\nRun 'jo versions install <version>' to install one.")
      return

    println("Installed compiler versions:\n")
    if installed.length < 10 then
      for v <- installed do
        val marker = if active.contains(v) then s" ${Ansi.green("(active)")}" else ""
        println(s"  $v$marker")
    else
      val byMinor = installed.groupBy(v => (v.major, v.minor)).toList
        .sortBy((k, _) => k)
        .reverse
      for ((major, minor), versions) <- byMinor do
        val patches  = versions.map(_.patch).sorted
        val isActive = versions.exists(active.contains)
        val marker   = if isActive then s" ${Ansi.green("(active)")}" else ""
        println(s"  $major.$minor.{${compactPatches(patches)}}$marker")

  private def compactPatches(patches: List[Int]): String =
    val sorted = patches.sorted
    val runs = sorted.foldLeft(List.empty[List[Int]]):
      case (Nil, p)                          => List(List(p))
      case (run :: rest, p) if p == run.last + 1 => (run :+ p) :: rest
      case (runs, p)                         => List(p) :: runs
    runs.reverse.map:
      case List(p)    => p.toString
      case run        => s"${run.head}-${run.last}"
    .mkString(", ")

  private def install(args: Array[String], installer: Installer): Unit =
    args.headOption match
      case None =>
        System.err.println("error: 'jo versions install' requires a version argument")
        sys.exit(1)

      case Some(versionStr) =>
        Version.parse(versionStr) match
          case None =>
            System.err.println(s"error: invalid version '$versionStr'")
            sys.exit(1)

          case Some(version) =>
            print(s"Installing Jo $version... ")
            installer.install(version) match
              case Result.Ok(_) =>
                println(Ansi.green("done"))
                println(s"\nRun 'jo versions use $version' to activate it.")

              case Result.Err(msg) =>
                println()
                System.err.println(s"error: $msg")
                sys.exit(1)

  private def remove(args: Array[String], installer: Installer): Unit =
    args.headOption match
      case None =>
        System.err.println("error: 'jo versions remove' requires a version argument")
        sys.exit(1)

      case Some(versionStr) =>
        Version.parse(versionStr) match
          case None =>
            System.err.println(s"error: invalid version '$versionStr'")
            sys.exit(1)

          case Some(version) =>
            installer.remove(version) match
              case Result.Ok(_)    => println(s"Removed Jo $version.")
              case Result.Err(msg) => System.err.println(s"error: $msg"); sys.exit(1)

  private def use(args: Array[String], installer: Installer): Unit =
    args.headOption match
      case None =>
        System.err.println("error: 'jo versions use' requires a version argument")
        sys.exit(1)

      case Some(versionStr) =>
        Version.parse(versionStr) match
          case None =>
            System.err.println(s"error: invalid version '$versionStr'")
            sys.exit(1)

          case Some(version) =>
            installer.use(version) match
              case Result.Ok(_)    => println(s"Now using Jo $version.")
              case Result.Err(msg) => System.err.println(s"error: $msg"); sys.exit(1)

  val usage: String =
    """|Usage:
       |  jo versions list                 List installed compiler versions
       |  jo versions install <version>    Download and install a compiler version
       |  jo versions use <version>        Switch the active compiler version
       |  jo versions remove <version>     Remove an installed compiler version
       |""".stripMargin

end Versions

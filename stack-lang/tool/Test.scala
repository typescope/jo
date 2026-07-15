package tool

import java.nio.file.{Files, FileSystems, Path, Paths}
import java.io.{ByteArrayOutputStream, PrintStream}
import scala.jdk.CollectionConverters.*
import tool.toml.{TomlError, TomlParser}

/** Runs all file-based regression tests for the build tool.
 *
 *  For each .toml input: compares actual output against the paired check file,
 *  or generates the check file if it does not exist yet.
 */
@main def runTests(filters: String*): Unit =
  val activeFilters = filters.toList

  val suites = List(
    ("TOML parser",  "tests/tool-toml/toml/*.toml",          (f: Path) => tool.toml.tomlCheck(f.toString)),
    ("BuildSpec",    "tests/tool-toml/build-spec/*.toml",    (f: Path) => printModel("build-spec", f.toString)),
    ("LockFile",     "tests/tool-toml/lock-file/*.toml",     (f: Path) => printModel("lock-file", f.toString)),
    ("PackageMeta",  "tests/tool-toml/package-meta/*.toml",  (f: Path) => printModel("package-meta", f.toString)),
    ("Project + Plan", "tests/tool-graph/*/jo.toml",         (f: Path) => printPlan(f.toString)),
    ("Resolver",     "tests/tool-resolver/*/jo.toml",        (f: Path) => printResolved(f.toString)),
    ("Lock",         "tests/tool-lock/*/jo.toml",            (f: Path) => print(lockCheck(f.toString))),
  )

  var failed = List.empty[Path]

  for (title, glob, run) <- suites do
    println(s"=== $title ===")
    for file <- findFiles(glob).filter(matchesFilter(_, activeFilters)) do
      val txtFile = file.resolveSibling(file.getFileName.toString.stripSuffix(".toml") + ".txt")
      val actual  = capture { run(file) }
      if !Files.exists(txtFile) then
        Files.writeString(txtFile, actual)
        println(s"  generated: $txtFile")
      else
        val expected = Files.readString(txtFile)
        if actual == expected then
          println(s"  ok: $file")
        else
          val actualFile = txtFile.resolveSibling(txtFile.getFileName.toString + ".out")
          Files.writeString(actualFile, actual)
          println(s"FAIL: $file")
          diff(expected, actual).foreach(println)
          println(s"  wrote actual output to: $actualFile")
          println(s"  compare with: diff -u ${txtFile} ${actualFile}")
          failed ::= file
  println()

  println("=== Build + Run ===")
  failed :::= runBuildTests(activeFilters)
  println()

  println("=== Info ===")
  failed :::= runInfoTests(activeFilters)
  println()

  println("=== Versions ===")
  failed :::= runVersionsTests(activeFilters)
  println()

  println("=== JoResolver ===")
  failed :::= runResolverTests()
  println()

  if failed.isEmpty then println("All tool tests passed.")
  else
    println(s"FAILED: ${failed.reverse.mkString(" ")}")
    sys.exit(1)

// ---- Build suite -------------------------------------------------------------

/** Each test project must have a jo.steps file (see parseSteps for format). */
private def runBuildTests(filters: List[String]): List[Path] =
  val joBin = Paths.get("bin/jo").toAbsolutePath()
  if !Files.exists(joBin) then
    println("  skipped: bin/jo not found")
    return Nil

  var failed = List.empty[Path]
  given Logger = Logger(LogLevel.Log)
  for stepsFile <- findFiles("tests/tool-build/*/jo.steps").filter(matchesFilter(_, filters)) do
    failed :::= runStepsFile(stepsFile, stepsFile.getParent)
  failed

private def runInfoTests(filters: List[String]): List[Path] =
  var failed = List.empty[Path]

  for file <- findFiles("tests/tool-info/*/*.txt").filter(matchesFilter(_, filters)) do
    val actual = infoOutput(file)
    val expected = Files.readString(file)
    if actual == expected then
      println(s"  ok: $file")
    else
      val actualFile = file.resolveSibling(file.getFileName.toString + ".out")
      Files.writeString(actualFile, actual)
      println(s"FAIL: $file")
      diff(expected, actual).foreach(println)
      println(s"  wrote actual output to: $actualFile")
      println(s"  compare with: diff -u ${file} ${actualFile}")
      failed ::= file

  failed

private def runVersionsTests(filters: List[String]): List[Path] =
  val joBin = Paths.get("bin/jo").toAbsolutePath()
  if !Files.exists(joBin) then
    println("  skipped: bin/jo not found")
    return Nil

  var failed = List.empty[Path]
  given Logger = Logger(LogLevel.Log)
  for stepsFile <- findFiles("tests/tool-versions/*/jo.steps").filter(matchesFilter(_, filters)) do
    failed :::= runStepsFile(stepsFile, stepsFile.getParent)
  failed

private def runResolverTests(): List[Path] =
  val current    = JoVersion.current
  val matching   = VersionSpec(current.copy(patch = 0))
  val tooNew     = VersionSpec(Version(current.major, current.minor + 1, 0))
  val wrongMajor = VersionSpec(Version(current.major + 1, 0, 0))
  var failed     = false

  def checkMismatch(label: String)(result: Result[?]): Unit =
    result match
      case Result.Err(msg) if msg.contains("jo versions use") =>
        println(s"  ok: $label")

      case other =>
        println(s"FAIL: $label")
        println(s"  expected version mismatch error, got: $other")
        failed = true

  def checkNoMismatch(label: String)(result: Result[?]): Unit =
    result match
      case Result.Err(msg) if msg.contains("jo versions use") =>
        println(s"FAIL: $label")
        println(s"  unexpected version mismatch error: $msg")
        failed = true

      case _ =>
        println(s"  ok: $label")

  checkNoMismatch("resolve: matching constraint passes version check"):
    JoResolver.resolve(matching)

  checkMismatch("resolve: requires newer minor → version mismatch"):
    JoResolver.resolve(tooNew)

  checkMismatch("resolve: requires higher major → version mismatch"):
    JoResolver.resolve(wrongMajor)

  checkNoMismatch("resolveExact: current version passes version check"):
    JoResolver.resolveExact(current)

  val other = current.copy(patch = current.patch + 1)

  checkMismatch("resolveExact: wrong version → version mismatch"):
    JoResolver.resolveExact(other)

  if failed then List(Paths.get("JoResolver")) else Nil

private def matchesFilter(path: Path, filters: List[String]): Boolean =
  if filters.isEmpty then true
  else
    val normalized = path.normalize().toString
    filters.exists: filter =>
      normalized.startsWith(Path.of(filter).normalize().toString)

// ---- jo.steps DSL ------------------------------------------------------------

/** A group of commands whose combined stdout may be checked.
 *
 *  expected = None  → run for side effects only (exit 0 required)
 *  expected = Some  → compare combined stdout to expected string
 */
private case class Step(cmds: List[String], expected: Option[String])

/** Parse a jo.steps file into a list of Steps.
 *
 *  Format (also a valid bash script):
 *    - Non-empty, non-comment lines are commands
 *    - Lines starting with `#` are comments
 *    - `: ''` is a compact form asserting empty output
 *    - `: '` opens a multi-line expected-output block; a lone `'` closes it
 *      (null-command string literals in bash — content is taken literally)
 *    - Commands before a `: ''` or `: '` block belong to that step
 *    - Commands without a following block form a step with no expected output
 *    - `{{JO_VERSION}}` in an expected block expands to the current major.minor
 *      version, so scaffolding output survives version bumps
 */
private def parseSteps(content: String): List[Step] =
  val lines  = content.linesIterator.toList
  val steps  = collection.mutable.ListBuffer.empty[Step]
  var cmds   = List.empty[String]
  var i      = 0

  while i < lines.length do
    val line = lines(i)
    if line == ": ''" then
      steps += Step(cmds.reverse, Some(""))
      cmds = Nil
      i += 1
    else if line == ": '" then
      i += 1
      val buf = collection.mutable.ListBuffer.empty[String]
      while i < lines.length && lines(i) != "'" do
        buf += lines(i)
        i += 1
      if i < lines.length then i += 1   // skip closing '
      steps += Step(cmds.reverse, Some(buf.mkString("\n") + "\n"))
      cmds = Nil
    else if line.trim.isEmpty || line.startsWith("#") then
      i += 1
    else
      cmds = line :: cmds
      i += 1

  if cmds.nonEmpty then steps += Step(cmds.reverse, None)
  steps.toList

private def runStepsFile(stepsFile: Path, specDir: Path)(using Logger): List[Path] =
  val steps   = parseSteps(Files.readString(stepsFile))
  var failed  = List.empty[Path]
  println(s"\n--- ${specDir.getFileName} ---")

  // Clean once before the whole scenario
  val buildDir = specDir.resolve(".build")
  if Files.exists(buildDir) then deleteDir(buildDir)

  for step <- steps do
    var stepOk = true
    val outputs = step.cmds.map: cmd =>
      if cmd.startsWith("jo ") then
        runJoCmd(cmd.drop(3).trim, specDir) match
          case Result.Ok(out)  => out

          case Result.Err(out) =>
            stepOk = false
            out

      else
        runShellCmd(cmd, specDir) match
          case Result.Ok(out)  => out

          case Result.Err(out) =>
            stepOk = false
            out

    val actual = outputs.mkString

    step.expected match
      case None =>
        if !stepOk then
          println(s"FAIL: $stepsFile [${step.cmds.mkString("; ")}]")
          if actual.nonEmpty then
            println(actual)
          failed ::= stepsFile

      case Some(expectedRaw) =>
        // `{{JO_VERSION}}` expands to the current major.minor, so scaffolding
        // expectations (e.g. the generated jo.toml) survive version bumps.
        val expected = expectedRaw.replace("{{JO_VERSION}}", joVersionShort)
        if actual == expected then
          println(s"  ok: $stepsFile [${step.cmds.mkString("; ")}]")
        else
          println(s"FAIL: $stepsFile [${step.cmds.mkString("; ")}]")
          diff(expected, actual).foreach(println)
          if actual.nonEmpty then
            println("-- actual --")
            print(actual)
          failed ::= stepsFile

  failed

// ---- Command runners ---------------------------------------------------------

private def runJoCmd(subcmd: String, specDir: Path)(using Logger): Result[String] =
  val parts = subcmd.trim.split("\\s+").toList.filter(_.nonEmpty)
  if parts.isEmpty then return Result.Err("empty jo command in test")
  val command = parts.head
  val cmdArgs = parts.tail.toArray
  val joBin = Paths.get("bin/jo").toAbsolutePath
  val resolveJo = (constraint: VersionSpec) => Result.Ok((constraint.minimumVersion, joBin))
  given PackageProvider = testPackageProvider(specDir)

  // Commands that don't need a build plan
  if command == "new" then
    return New.parseArgs(cmdArgs).flatMap: parsed =>
      New.scaffold(parsed.name, parsed.isLib, specDir)

  if command == "package" then
    val parsed = Build.parseProjectArgs(cmdArgs) match
      case Result.Ok(parsed) => parsed
      case Result.Err(msg)   => return Result.Err(s"$msg\n")
    val specPath = Paths.get(resolveSpecDir(parsed.specFile, specDir)).toAbsolutePath
    val result = Project.load(specPath, resolveJo).flatMap: project =>
      Release.buildPackage(project, Build.selectedModule(project, parsed))
    return result match
      case Result.Ok(_)    => Result.Ok("")
      case Result.Err(msg) => Result.Err(s"error: $msg\n")

  if command == "lock" then
    val parsed = Build.parseProjectArgs(cmdArgs) match
      case Result.Ok(parsed) => parsed
      case Result.Err(msg)   => return Result.Err(s"$msg\n")
    if parsed.module.isDefined then
      return Result.Err("error: 'jo lock' resolves all modules and does not take a module argument\n")
    val specPath = Paths.get(resolveSpecDir(parsed.specFile, specDir)).toAbsolutePath
    return Project.load(specPath, resolveJo).flatMap(Build.lockResult(_)) match
      case Result.Ok(_)    => Result.Ok("")
      case Result.Err(msg) => Result.Err(s"error: $msg\n")

  if command == "clean" then
    val parsed = Build.parseProjectArgs(cmdArgs) match
      case Result.Ok(parsed) => parsed
      case Result.Err(msg)   => return Result.Err(s"$msg\n")
    val specPath = Paths.get(resolveSpecDir(parsed.specFile, specDir)).toAbsolutePath
    return Project.load(specPath, resolveJo).flatMap(project => Build.clean(project, parsed.module)) match
      case Result.Ok(_)    => Result.Ok("")
      case Result.Err(msg) => Result.Err(s"error: $msg\n")

  if command == "deps" then
    val parsed = Build.parseProjectArgs(cmdArgs) match
      case Result.Ok(parsed) => parsed
      case Result.Err(msg)   => return Result.Err(s"$msg\n")
    val specPath = Paths.get(resolveSpecDir(parsed.specFile, specDir)).toAbsolutePath
    val result = Project.load(specPath, resolveJo).flatMap: project =>
      Build.depsResult(project, Build.selectedModule(project, parsed))
    return result match
      case Result.Ok(out)   => Result.Ok(out)
      case Result.Err(msg)  => Result.Err(s"error: $msg\n")

  if command == "info" then
    return Info.result(cmdArgs) match
      case Result.Ok(out)   => Result.Ok(out)
      case Result.Err(msg)  => Result.Err(s"error: $msg\n")

  if command == "doc" then
    val parsed = Build.parseProjectArgs(cmdArgs) match
      case Result.Ok(parsed) => parsed
      case Result.Err(msg)   => return Result.Err(s"$msg\n")
    val specPath = Paths.get(resolveSpecDir(parsed.specFile, specDir)).toAbsolutePath
    val result = Project.load(specPath, resolveJo).flatMap: project =>
      Build.buildDoc(project, Build.selectedModule(project, parsed))
    return result match
      case Result.Ok(_)    => Result.Ok("")
      case Result.Err(msg) => Result.Err(s"error: $msg\n")

  if command == "versions" then
    val installer = MockInstaller.fromYaml(specDir.resolve("versions.yaml"))
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true, "UTF-8")
    Console.withOut(ps) { Versions.run(cmdArgs, installer) } match
      case Result.Ok(_)    => return Result.Ok(buf.toString("UTF-8"))
      case Result.Err(msg) => return Result.Err(buf.toString("UTF-8") + s"error: $msg\n")

  if command == "test" then
    return Result.Err("error: 'jo test' was removed; define a test app module and run it with 'jo run <module>'\n")

  val (specFile0, moduleArg, appArgs) = command match
    case "run" =>
      Build.parseRunArgs(cmdArgs) match
        case Result.Ok(parsed) => (parsed.specFile, parsed.module, parsed.appArgs)
        case Result.Err(msg)   => return Result.Err(s"$msg\n")

    case _ =>
      Build.parseProjectArgs(cmdArgs) match
        case Result.Ok(parsed) => (parsed.specFile, parsed.module, Nil)
        case Result.Err(msg)   => return Result.Err(s"$msg\n")
  val specPath = Paths.get(resolveSpecDir(specFile0, specDir)).toAbsolutePath
  val plan = Project.load(specPath, resolveJo).flatMap: project =>
    val module = moduleArg.getOrElse(project.defaultModuleId)
    Build.makePlanResult(project, List(module)).map(project -> _)

  val (_, plans) = plan match
    case Result.Ok(value) => value
    case Result.Err(msg)  => return Result.Err(s"error: $msg\n")

  val selectedPlan = plans.modules.head

  command match
    case "run" =>
      Runner.run(selectedPlan).flatMap: _ =>
        selectedPlan.task match
          case app: CompileTask.AppTask => Runner.execute(app, appArgs)
          case _: CompileTask.LibTask   => Result.Ok("")

    case "build" | "check" =>
      val result =
        if command == "build" then Runner.run(selectedPlan)
        else Runner.check(selectedPlan, "check")
      result.map(_ => "")

    case other => Result.Err(s"unknown jo subcommand '$other' in test")

private def resolveSpecDir(specFile: String, specDir: Path): String =
  val specPath = Path.of(specFile)
  val resolved = if specPath.isAbsolute then specPath else specDir.resolve(specPath).normalize()
  resolved.toString

private def testPackageProvider(specDir: Path): PackageProvider =
  val repoSrc = specDir.resolve("repo-src")
  val repoDir = specDir.resolve("repo")
  val cacheHome = specDir.resolve(".cache")

  if Files.isDirectory(repoSrc) then
    FixtureRepo.rebuild(repoSrc, repoDir)
    LocalPackageProvider(repoDir, cacheHome)
  else
    LocalPackageProvider(repoDir, cacheHome)

private def runShellCmd(cmd: String, workDir: Path): Result[String] =
  val pb = ProcessBuilder(List("sh", "-c", cmd).asJava)
  pb.directory(workDir.toFile)
  val proc = pb.start()
  val out  = String(proc.getInputStream.readAllBytes(), "UTF-8")
  val exit = proc.waitFor()
  if exit != 0 then Result.Err(s"shell command failed (exit $exit): $cmd")
  else Result.Ok(out)

private def infoOutput(expectedFile: Path): String =
  val outputPath = expectedFile.toAbsolutePath
  val specDir = outputPath.getParent
  val repoFile = specDir.resolve("repo.yaml")
  val query = outputPath.getFileName.toString.stripSuffix(".txt")

  try
    given PackageProvider = YamlPackageProvider(repoFile, specDir.resolve(".cache"))
    Info.result(Array(query)) match
      case Result.Ok(output)  => output
      case Result.Err(msg)    => s"error: $msg\n"
  catch
    case e: YamlRepoError => s"error: ${e.getMessage}\n"

private def printResolved(specFile: String): Unit =
  val specPath = Path.of(specFile).toAbsolutePath
  val specDir = specPath.getParent
  val repoFile = specDir.resolve("repo.yaml")
  val joBin = Paths.get("bin/jo").toAbsolutePath
  val resolveJo = (constraint: VersionSpec) => Result.Ok((constraint.minimumVersion, joBin))

  val provider = YamlPackageProvider(repoFile, specDir.resolve(".cache"))
  given PackageProvider = provider
  Project.load(specPath, resolveJo).flatMap(DependencyResolver.resolveProject(_)) match
    case Result.Ok(resolved) =>
      resolved.unusedPins.foreach: (name, version) =>
        println(s"warning: unused [pinning] entry $name = \"$version\"")
      resolved.packages.foreach: pkg =>
        println(s"${pkg.name} = ${pkg.version}")
        provider.path(pkg.name, pkg.version) match
          case Result.Ok(path) =>
            println(s"  path = ${specDir.relativize(path)}")
          case Result.Err(msg) =>
            println(s"  path = error: $msg")
    case Result.Err(msg) =>
      println(s"error: $msg")

private def lockCheck(specFile: String): String =
  val specPath = Path.of(specFile).toAbsolutePath
  val specDir = specPath.getParent
  val repoFile = specDir.resolve("repo.yaml")
  val lockPath = LockFile.pathForSpec(specPath)
  val joBin = Paths.get("bin/jo").toAbsolutePath
  val resolveJo = (constraint: VersionSpec) => Result.Ok((constraint.minimumVersion, joBin))

  val provider = YamlPackageProvider(repoFile, specDir.resolve(".cache"))
  given PackageProvider = provider

  val resolved = Project.load(specPath, resolveJo).flatMap: project =>
    LockFile.load(lockPath).flatMap:
      case Some(lock) => DependencyResolver.resolveProject(project, lock).map(resolved => (project, resolved))
      case None       => DependencyResolver.resolveProject(project).map(resolved => (project, resolved))

  val result = resolved.flatMap: (project, resolved) =>
    validateLockPackageDepths(project, resolved).flatMap: _ =>
      val locked = collection.mutable.ListBuffer.empty[LockedPackage]
      val sorted = resolved.packages.sortBy(_.name)
      val it = sorted.iterator
      var digestErr: String | Null = null
      while it.hasNext && digestErr == null do
        val pkg = it.next()
        provider.digest(pkg.name, pkg.version) match
          case Result.Ok(value) =>
            locked += LockedPackage(pkg.name, pkg.version.toString, value)

          case Result.Err(msg) =>
            digestErr = msg

      if digestErr != null then
        Result.Err(digestErr)
      else
        val lock = LockFile(Some(project.joVersion), locked.toList)
        LockFile.write(lockPath, lock).map(_ => LockFile.render(lock))

  result match
    case Result.Ok(output)  => output
    case Result.Err(msg)    => s"error: $msg\n"

private def validateLockPackageDepths(project: Project, resolved: ResolutionResult): Result[Unit] =
  project.moduleIds.foldLeft(Result.unit): (acc, module) =>
    acc.flatMap: _ =>
      val info = resolved.packageDepthByModule.getOrElse(module, DepthInfo(0, Nil))
      val allowedDepth = project.depthOf(module)

      if info.depth > allowedDepth then
        Result.Err(
          s"""package dependency depth exceeded for module '${module.value}': actual ${info.depth}, allowed $allowedDepth
             |
             |  Path: ${(module.value :: info.deepestPath).mkString(" -> ")}""".stripMargin
        )
      else
        Result.unit

private def printPlan(specFile: String): Unit =
  try
    given PackageProvider = testPackageProvider(Paths.get(specFile).toAbsolutePath.getParent)
    given Logger = Logger.stderr
    val joBin = Paths.get("bin/jo").toAbsolutePath
    val resolveJo = (constraint: VersionSpec) => Result.Ok((constraint.minimumVersion, joBin))
    val result = Project.load(Paths.get(specFile).toAbsolutePath, resolveJo).flatMap: project =>
      Build.makePlanResult(project, List(project.defaultModuleId)).map((project, _))

    result match
      case Result.Ok((project, plans)) =>
        val specDir = project.specPath.getParent
        println(PlanPrinter.print(plans, specDir))

      case Result.Err(msg) =>
        println(s"error: $msg")
  catch
    case e: TomlError => println(s"error: ${e.getMessage}")

private def printModel(kind: String, path: String): Unit =
  try
    val src = Files.readString(Path.of(path))
    val doc = TomlParser.parse(src)

    val output = kind match
      case "build-spec"   => ToolPrinter.print(BuildSpec.decode(doc))
      case "lock-file"    => ToolPrinter.print(LockFile.decode(doc))
      case "package-meta" => ToolPrinter.print(PackageMeta.decode(doc))
      case _              => sys.error(s"unknown kind '$kind'")

    println(output)
  catch
    case e: TomlError => println(s"error: ${e.getMessage}")


// ---- Shared helpers ----------------------------------------------------------

/** The `major.minor` constraint emitted by scaffolding (`jo new`). */
private def joVersionShort: String =
  s"${JoVersion.current.major}.${JoVersion.current.minor}"

private def findFiles(pattern: String): List[Path] =
  val i       = pattern.indexWhere(c => c == '*' || c == '?')
  val baseDir = Paths.get(pattern.substring(0, pattern.lastIndexOf('/', i)))
  val matcher = FileSystems.getDefault.getPathMatcher(s"glob:$pattern")
  Files.walk(baseDir).iterator.asScala
    .filter(matcher.matches)
    .toList.sortBy(_.toString)

private def capture(f: => Unit): String =
  val buf = ByteArrayOutputStream()
  val ps  = PrintStream(buf, true, "UTF-8")
  Console.withOut(ps)(f)
  buf.toString("UTF-8")

private def diff(expected: String, actual: String): List[String] =
  val exp = expected.linesIterator.toIndexedSeq
  val act = actual.linesIterator.toIndexedSeq
  val dp = Array.ofDim[Int](exp.length + 1, act.length + 1)

  var i = exp.length - 1
  while i >= 0 do
    var j = act.length - 1
    while j >= 0 do
      dp(i)(j) =
        if exp(i) == act(j) then dp(i + 1)(j + 1) + 1
        else dp(i + 1)(j).max(dp(i)(j + 1))
      j -= 1
    i -= 1

  val out = collection.mutable.ListBuffer.empty[String]
  i = 0
  var j = 0
  while i < exp.length || j < act.length do
    if i < exp.length && j < act.length && exp(i) == act(j) then
      i += 1
      j += 1
    else if j == act.length || (i < exp.length && dp(i + 1)(j) >= dp(i)(j + 1)) then
      out += s"< ${exp(i)}"
      i += 1
    else
      out += s"> ${act(j)}"
      j += 1

  out.toList

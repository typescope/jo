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
@main def runTests(): Unit =
  val suites = List(
    ("TOML parser",  "tests/tool-toml/toml/*.toml",          (f: Path) => tool.toml.tomlCheck(f.toString)),
    ("BuildSpec",    "tests/tool-toml/build-spec/*.toml",    (f: Path) => printModel("build-spec", f.toString)),
    ("LockFile",     "tests/tool-toml/lock-file/*.toml",     (f: Path) => printModel("lock-file", f.toString)),
    ("PackageMeta",  "tests/tool-toml/package-meta/*.toml",  (f: Path) => printModel("package-meta", f.toString)),
    ("Project + Plan", "tests/tool-graph/*/jo.toml",         (f: Path) => printPlan(f.toString)),
    ("Resolver",     "tests/tool-resolver/*/jo.toml",        (f: Path) => printResolved(f.toString)),
    ("Lock",         "tests/tool-lock/*/jo.toml",            (f: Path) => print(lockCheck(f.toString))),
    ("Info",         "tests/tool-info/*/query.txt",          (f: Path) => printInfo(f.toString)),
  )

  var failed = List.empty[Path]

  for (title, glob, run) <- suites do
    println(s"=== $title ===")
    for file <- findFiles(glob) do
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
          println(s"FAIL: $file")
          diff(expected, actual).foreach(println)
          failed ::= file
    println()

  println("=== Build + Run ===")
  failed :::= runBuildTests()
  println()

  if failed.isEmpty then println("All tool tests passed.")
  else
    println(s"FAILED: ${failed.reverse.mkString(" ")}")
    sys.exit(1)

// ---- Build suite -------------------------------------------------------------

/** Each test project must have a jo.steps file (see parseSteps for format). */
private def runBuildTests(): List[Path] =
  val joBin = Paths.get("bin/jo").toAbsolutePath()
  if !Files.exists(joBin) then
    println("  skipped: bin/jo not found")
    return Nil

  var failed = List.empty[Path]
  given Logger = Logger.stderr
  for stepsFile <- findFiles("tests/tool-build/*/jo.steps") do
    failed :::= runStepsFile(stepsFile, stepsFile.getParent, joBin)
  failed

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

private def runStepsFile(stepsFile: Path, specDir: Path, joBin: Path)(using Logger): List[Path] =
  val steps   = parseSteps(Files.readString(stepsFile))
  var failed  = List.empty[Path]
  println(s"\n--- ${specDir.getFileName} ---")

  // Clean once before the whole scenario
  val buildDir = specDir.resolve(".build")
  if Files.exists(buildDir) then deleteDir(buildDir)

  for step <- steps do
    var stepOk = true
    val actual = step.cmds.map: cmd =>
      if cmd.startsWith("jo ") then
        runJoCmd(cmd.drop(3).trim, specDir, joBin) match
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
    .mkString

    step.expected match
      case None =>
        if !stepOk then
          println(s"FAIL: $stepsFile [${step.cmds.mkString("; ")}]")
          failed ::= stepsFile

      case Some(expected) =>
        if actual == expected then
          println(s"  ok: $stepsFile [${step.cmds.mkString("; ")}]")
        else
          println(s"FAIL: $stepsFile [${step.cmds.mkString("; ")}]")
          diff(expected, actual).foreach(println)
          failed ::= stepsFile

  failed

// ---- Command runners ---------------------------------------------------------

private def runJoCmd(subcmd: String, specDir: Path, joBin: Path)(using Logger): Result[String] =
  val parts = subcmd.trim.split("\\s+").toList.filter(_.nonEmpty)
  if parts.isEmpty then return Result.Err("empty jo command in test")
  val command = parts.head
  val cmdArgs = parts.tail.toArray
  given PackageProvider = testPackageProvider(specDir)

  // Commands that don't need a build plan
  if command == "new" then
    val newArgs = cmdArgs
    val name    = newArgs(0)
    val isLib   = newArgs.contains("--lib")
    return New.scaffold(name, isLib, specDir)

  if command == "package" then
    try
      val specFile = resolveSpecDir(Build.parseSpecFile(cmdArgs), specDir)
      Release.buildPackage(Array("--spec", specFile)): constraint =>
        (constraint.minimumVersion, joBin)
      return Result.Ok("")
    catch
      case e: ToolError => return Result.Err(s"error: ${e.getMessage}\n")

  if command == "lock" then
    val specFile = resolveSpecDir(Build.parseSpecFile(cmdArgs), specDir)
    return Build.lockResult(specFile) match
      case Result.Ok(_)    => Result.Ok("")
      case Result.Err(msg) => Result.Err(s"error: $msg\n")

  if command == "deps" then
    val specFile = resolveSpecDir(Build.parseSpecFile(cmdArgs), specDir)
    return Build.depsResult(specFile) match
      case Result.Ok(out)   => Result.Ok(out)
      case Result.Err(msg)  => Result.Err(s"error: $msg\n")

  if command == "info" then
    return Info.result(cmdArgs) match
      case Result.Ok(out)   => Result.Ok(out)
      case Result.Err(msg)  => Result.Err(s"error: $msg\n")

  val (specFile0, _) = Build.parseRunArgs(cmdArgs)
  val specFile = resolveSpecDir(specFile0, specDir)
  val modules = command match
    case "test" => List(ModuleKind.Main, ModuleKind.Test)
    case _      => List(ModuleKind.Main)
  val plan = Build.makePlanResult(specFile, modules): constraint =>
    Result.Ok((constraint.minimumVersion, joBin))

  val (plans, joBin2) = plan match
    case Result.Ok(value) => value
    case Result.Err(msg)  => return Result.Err(s"error: $msg\n")

  command match
    case "run" =>
      val main = plans.main
      Runner.run(main, joBin2).flatMap: _ =>
        main.task match
          case app: CompileTask.AppTask => Runner.execute(app, Nil)
          case _: CompileTask.LibTask   => Result.Ok("")

    case "test" =>
      plans.test match
        case None => Result.Ok("no tests defined\n")
        case Some(tp) =>
          Runner.run(tp, joBin2).flatMap: _ =>
            tp.task match
              case app: CompileTask.AppTask => Runner.execute(app, Nil)
              case _                        => Result.Ok("")

    case "build" | "check" =>
      val run: (ModulePlan, Path) => Result[Unit] =
        if command == "build" then Runner.run else Runner.check
      run(plans.main, joBin2).map(_ => "")

    case other => Result.Err(s"unknown jo subcommand '$other' in test")

private def resolveSpecDir(specFile: String, specDir: Path): String =
  val specPath = Path.of(specFile)
  val resolved = if specPath.isAbsolute then specPath else specDir.resolve(specPath).normalize()
  resolved.toString

private def testPackageProvider(specDir: Path): PackageProvider =
  val repoSrc = specDir.resolve("repo-src")
  val repoDir = specDir.resolve("repo")

  if Files.isDirectory(repoSrc) then
    FixtureRepo.rebuild(repoSrc, repoDir)
    LocalPackageProvider(repoDir)
  else if Files.isDirectory(repoDir) then
    LocalPackageProvider(repoDir)
  else PackageProvider.default()

private def runShellCmd(cmd: String, workDir: Path): Result[String] =
  val pb = ProcessBuilder(List("sh", "-c", cmd).asJava)
  pb.directory(workDir.toFile)
  val proc = pb.start()
  val out  = String(proc.getInputStream.readAllBytes(), "UTF-8")
  val exit = proc.waitFor()
  if exit != 0 then Result.Err(s"shell command failed (exit $exit): $cmd")
  else Result.Ok(out)

private def printInfo(queryFile: String): Unit =
  val queryPath = Path.of(queryFile).toAbsolutePath
  val specDir = queryPath.getParent
  val repoFile = specDir.resolve("repo.yaml")

  try
    given PackageProvider = YamlPackageProvider(repoFile)
    val query = Files.readString(queryPath).trim
    Info.result(Array(query)) match
      case Result.Ok(output)  => print(output)
      case Result.Err(msg)    => print(s"error: $msg\n")
  catch
    case e: ToolError => print(s"error: ${e.getMessage}\n")

private def printResolved(specFile: String): Unit =
  val specPath = Path.of(specFile).toAbsolutePath
  val specDir = specPath.getParent
  val repoFile = specDir.resolve("repo.yaml")

  try
    given PackageProvider = YamlPackageProvider(repoFile)
    val project = Project.load(specPath)
    DependencyResolver.resolveProject(project) match
      case Result.Ok(resolved) =>
        resolved.packages.foreach: pkg =>
          println(s"${pkg.name} = ${pkg.version}")
          println(s"  path = ${specDir.relativize(pkg.path)}")
      case Result.Err(msg) =>
        println(s"error: $msg")
  catch
    case e: ToolError => println(s"error: ${e.getMessage}")

private def lockCheck(specFile: String): String =
  val specPath = Path.of(specFile).toAbsolutePath
  val specDir = specPath.getParent
  val repoFile = specDir.resolve("repo.yaml")
  val lockPath = specPath.resolveSibling(specPath.getFileName.toString.stripSuffix(".toml") + ".lock")

  try
    val provider = YamlPackageProvider(repoFile)
    given PackageProvider = provider
    val project = Project.load(specPath)

    val resolved = LockFile.load(lockPath).flatMap:
      case Some(lock) => DependencyResolver.resolveProject(project, lock)
      case None       => DependencyResolver.resolveProject(project)

    val result = resolved.flatMap: resolved =>
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
          val lock = LockFile(locked.toList)
          LockFile.write(lockPath, lock).map(_ => LockFile.render(lock))

    result match
      case Result.Ok(output)  => output
      case Result.Err(msg)    => s"error: $msg\n"
  catch
    case e: ToolError => s"error: ${e.getMessage}\n"

private def validateLockPackageDepths(project: Project, resolved: ResolutionResult): Result[Unit] =
  val modules =
    if project.test.isDefined then List(ModuleKind.Main, ModuleKind.Test)
    else List(ModuleKind.Main)

  modules.foldLeft(Result.unit): (acc, module) =>
    acc.flatMap: _ =>
      val (actualDepth, deepestPath) = module match
        case ModuleKind.Main => (resolved.mainPackageDepth, resolved.mainDeepestPath)
        case ModuleKind.Test => (resolved.testPackageDepth, resolved.testDeepestPath)
      val allowedDepth = project.depthOf(module)

      if actualDepth > allowedDepth then
        val moduleName = module match
          case ModuleKind.Main => "main"
          case ModuleKind.Test => "test"

        Result.Err(
          s"""package dependency depth exceeded for '${project.name}' $moduleName module: actual $actualDepth, allowed $allowedDepth
             |
             |  Path: ${(project.name :: deepestPath).mkString(" -> ")}""".stripMargin
        )
      else
        Result.unit

private def printPlan(specFile: String): Unit =
  try
    given PackageProvider = PackageProvider.default()
    Build.makePlanResult(specFile, List(ModuleKind.Main)): constraint =>
      val joVersion = constraint.minimumVersion
      val joPath = Paths.get("jo")
      Result.Ok((joVersion, joPath))
    match
      case Result.Ok((plans, _)) =>
        val specDir = Paths.get(specFile).toAbsolutePath.getParent
        println(PlanPrinter.print(plans, specDir))

      case Result.Err(msg) =>
        println(s"error: $msg")
  catch
    case e: ToolError => println(s"error: ${e.getMessage}")
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
  (0 until (exp.length max act.length)).flatMap: i =>
    (exp.lift(i), act.lift(i)) match
      case (Some(e), Some(a)) if e != a => List(s"< $e", s"> $a")
      case (Some(e), None)              => List(s"< $e")
      case (None, Some(a))              => List(s"> $a")
      case _                            => Nil
  .toList

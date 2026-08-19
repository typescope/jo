package tool

import java.nio.file.{Files, FileSystems, Path, Paths}
import java.io.{ByteArrayOutputStream, PrintStream}
import java.util.zip.{ZipEntry, ZipOutputStream}

import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import scala.util.Using


import tool.toml.{TomlError, TomlParser}
import tool.template.{GithubTemplateProvider, LocalTemplateProvider, TemplateArchive, TemplateEntry, TemplateManifest, TemplateProvider, TemplateRef}

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

  println("=== Json ===")
  failed :::= runJsonTests()
  println()

  println("=== ReleaseJson ===")
  failed :::= runReleaseJsonTests()
  println()

  println("=== New (name validation) ===")
  failed :::= runNewNameValidationTests()
  println()

  println("=== TemplateRef ===")
  failed :::= runTemplateRefTests()
  println()

  println("=== TemplateManifest ===")
  failed :::= runTemplateManifestTests()
  println()

  println("=== TemplateArchive ===")
  failed :::= runTemplateArchiveTests()
  println()

  println("=== GithubTemplateProvider (HTTP) ===")
  failed :::= runGithubTemplateProviderTests()
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

  if failed then List(Paths.get("JoResolver")) else Nil

// ---- Json suite ----------------------------------------------------------------

/** Input is untrusted (a registry index, or a third-party
 *  `jo-templates.jsonl`), so the parser is deliberately strict rather than
 *  permissive — each check here corresponds to a specific leniency bug that
 *  was found and fixed: trailing garbage, unknown escapes, undecoded
 *  `\uXXXX`, unescaped control characters, invalid numbers like a bare `-`,
 *  silently-merged duplicate keys, and numbers being indistinguishable from
 *  strings in the result.
 */
private def runJsonTests(): List[Path] =
  var failed = false

  def check(label: String)(body: => Boolean): Unit =
    val ok =
      try body
      catch
        case e: Exception =>
          println(s"  threw: ${e.getMessage}")
          false

    if ok then println(s"  ok: $label")
    else
      println(s"FAIL: $label")
      failed = true

  def isErr(r: Either[String, ?]): Boolean = r.isLeft

  check("a well-formed object parses"):
    Json.parseObj("""{"a": "x", "b": true, "c": null, "d": [1, 2.5]}""") ==
      Right(Map("a" -> "x", "b" -> true, "c" -> null, "d" -> List(1.0, 2.5)))

  check("insignificant trailing whitespace is accepted"):
    Json.parseObj("{\"a\":\"x\"}  \n") == Right(Map("a" -> "x"))

  check("trailing non-whitespace garbage is rejected, not silently ignored"):
    isErr(Json.parseObj("""{"name":"x"} garbage"""))

  check("an unknown escape sequence like '\\q' is rejected"):
    isErr(Json.parseObj("""{"a":"\q"}"""))

  check("'\\uXXXX' escapes are decoded, not left as literal characters"):
    // Built via concatenation, not a literal "A" in source: Scala (like
    // Java) pre-processes \uXXXX unicode escapes in raw source text before
    // any tokenization runs, so a literal backslash-u sequence can't survive
    // as text in a string literal written directly in this file.
    val bs = "\\"
    val encoded = bs + "u0041" + bs + "u0042" + bs + "u0043"
    Json.parseObj(s"""{"a":"$encoded"}""") == Right(Map("a" -> "ABC"))

  check("an unescaped control character inside a string is rejected"):
    isErr(Json.parseObj("{\"a\":\"x\ty\"}"))

  check("a bare '-' is rejected, not accepted as a truncated number"):
    isErr(Json.parseObj("""{"a":-}"""))

  check("a duplicate object key is rejected, not silently overwritten"):
    isErr(Json.parseObj("""{"a":"x","a":"y"}"""))

  check("numbers are returned as Double, not String, so a string-typed field check can reject them"):
    Json.parseObj("""{"a":1}""") match
      case Right(obj) => obj.get("a").exists(_.isInstanceOf[Double]) && !obj.get("a").exists(_.isInstanceOf[String])
      case Left(_)     => false

  if failed then List(Paths.get("Json")) else Nil

// ---- ReleaseJson suite -------------------------------------------------------

/** Guards the registry JSONL wire format against the shape the registry daemon actually
 *  writes. See sync.py in typescope/packages: it emits `runtime`, not `platform`.
 */
private def runReleaseJsonTests(): List[Path] =
  var failed = false

  def check(label: String)(body: => Boolean): Unit =
    val ok =
      try body
      catch
        case e: Exception =>
          println(s"  threw: ${e.getMessage}")
          false

    if ok then println(s"  ok: $label")
    else
      println(s"FAIL: $label")
      failed = true

  // A line in the exact shape sync.py writes.
  val daemonLine =
    """{"version":"1.2.0","url":"https://x/p-v1.2.0.joy","sha512":"abc","runtime":"python","jo":"1.0","deps":{"core":"1.1"}}"""

  check("parses a record written by the registry daemon"):
    ReleaseJson.parse(daemonLine).isRight

  check("reads the platform from the 'runtime' key"):
    ReleaseJson.parse(daemonLine) match
      case Right(rec) => rec.platform == "python"
      case Left(_)    => false

  check("'platform' is not accepted in place of 'runtime'"):
    val renamed = daemonLine.replace("\"runtime\"", "\"platform\"")
    ReleaseJson.parse(renamed).isLeft

  check("runtime is required"):
    val missing = """{"version":"1.2.0","url":"u","sha512":"s","jo":"1.0"}"""
    ReleaseJson.parse(missing).isLeft

  check("deps and yanked are optional"):
    val minimal = """{"version":"1.0.0","url":"u","sha512":"s","runtime":"pure","jo":"1.0"}"""
    ReleaseJson.parse(minimal) match
      case Right(rec) => rec.deps.isEmpty && !rec.yanked
      case Left(_)    => false

  if failed then List(Paths.get("ReleaseJson")) else Nil

// ---- New name-validation suite ---------------------------------------------------

/** `requireName` is the one place both `New.scaffold` (built-in) and
 *  `New.scaffoldFromTemplate` get their target directory name from, so
 *  testing it through `parseArgs` covers the containment fix for both.
 */
private def runNewNameValidationTests(): List[Path] =
  var failed = false

  def check(label: String)(body: => Boolean): Unit =
    val ok =
      try body
      catch
        case e: Exception =>
          println(s"  threw: ${e.getMessage}")
          false

    if ok then println(s"  ok: $label")
    else
      println(s"FAIL: $label")
      failed = true

  def isErr(r: Result[?], contains: String): Boolean = r match
    case Result.Err(msg) => msg.contains(contains)
    case _                => false

  check("a plain name is accepted"):
    New.parseArgs(Array("myapp")) == Result.Ok(New.Args.Scaffold("myapp", false, None))

  check("'..' escaping baseDir via a relative name is rejected"):
    isErr(New.parseArgs(Array("../evil")), "invalid project name")

  check("an absolute path as name is rejected (Path.resolve would otherwise ignore baseDir entirely)"):
    isErr(New.parseArgs(Array("/etc/evil")), "invalid project name")

  check("a bare '..' is rejected"):
    isErr(New.parseArgs(Array("..")), "invalid project name")

  check("a bare '.' is rejected"):
    isErr(New.parseArgs(Array(".")), "invalid project name")

  check("an empty name is rejected"):
    isErr(New.parseArgs(Array("")), "requires a project name")

  check("the same containment check applies on the --template path"):
    isErr(New.parseArgs(Array("--template", "gh:acme/repo", "../evil")), "invalid project name")

  if failed then List(Paths.get("New")) else Nil

// ---- TemplateRef suite ---------------------------------------------------------

private def runTemplateRefTests(): List[Path] =
  var failed = false

  def check(label: String)(body: => Boolean): Unit =
    val ok =
      try body
      catch
        case e: Exception =>
          println(s"  threw: ${e.getMessage}")
          false

    if ok then println(s"  ok: $label")
    else
      println(s"FAIL: $label")
      failed = true

  def isErr(r: Result[?], contains: String): Boolean = r match
    case Result.Err(msg) => msg.contains(contains)
    case _                => false

  check("bare identifier defaults to host 'gh', no name, gitref HEAD"):
    TemplateRef.parse("acme/repo") == Result.Ok(TemplateRef("gh", "acme/repo", None, "HEAD"))

  check("':name' selects a template"):
    TemplateRef.parse("acme/repo:web-app") == Result.Ok(TemplateRef("gh", "acme/repo", Some("web-app"), "HEAD"))

  check("'#gitref' sits between identifier and ':name'"):
    TemplateRef.parse("acme/repo#v2:web-app") == Result.Ok(TemplateRef("gh", "acme/repo", Some("web-app"), "v2"))

  check("'#gitref' alone, no name"):
    TemplateRef.parse("acme/repo#v1") == Result.Ok(TemplateRef("gh", "acme/repo", None, "v1"))

  check("explicit 'gh:' host prefix, '#gitref', and ':name' all together"):
    TemplateRef.parse("gh:acme/repo#v2:name") == Result.Ok(TemplateRef("gh", "acme/repo", Some("name"), "v2"))

  check("canonical: unpinned, default host — no noise from either default"):
    TemplateRef("gh", "acme/repo", None, "HEAD").canonical == "acme/repo"

  check("canonical: a pinned gitref is never dropped, unlike the old success message"):
    TemplateRef("gh", "acme/repo", None, "v2").canonical == "acme/repo#v2"

  check("canonical: pinned gitref plus a name"):
    TemplateRef("gh", "acme/repo", Some("web-app"), "v2").canonical == "acme/repo#v2:web-app"

  check("unsupported host is a hard error, not a hostname fallback"):
    isErr(TemplateRef.parse("gitlab:acme/repo"), "unsupported template host 'gitlab'")

  check("empty ref is an error"):
    isErr(TemplateRef.parse(""), "empty template ref")

  check("host prefix with nothing after it is an error"):
    isErr(TemplateRef.parse("gh:"), "missing identifier")

  check("empty name after ':' is an error"):
    isErr(TemplateRef.parse("acme/repo:"), "empty template name")

  check("empty gitref after '#' is an error"):
    isErr(TemplateRef.parse("acme/repo#"), "empty git ref")

  if failed then List(Paths.get("TemplateRef")) else Nil

// ---- TemplateManifest suite -----------------------------------------------------

private def runTemplateManifestTests(): List[Path] =
  var failed = false

  def check(label: String)(body: => Boolean): Unit =
    val ok =
      try body
      catch
        case e: Exception =>
          println(s"  threw: ${e.getMessage}")
          false

    if ok then println(s"  ok: $label")
    else
      println(s"FAIL: $label")
      failed = true

  def isErr(r: Result[?], contains: String): Boolean = r match
    case Result.Err(msg) => msg.contains(contains)
    case _                => false

  val valid =
    """{"name": "web-app", "path": "templates/web-app", "description": "Web app"}
      |{"name": "cli", "path": "templates/cli"}
      |""".stripMargin

  val parsedValid = List(
    TemplateEntry("web-app", "templates/web-app", Some("Web app")),
    TemplateEntry("cli", "templates/cli", None),
  )

  check("parses a valid manifest"):
    TemplateManifest.parse(valid) == Result.Ok(parsedValid)

  check("blank lines are skipped"):
    TemplateManifest.parse("\n" + valid + "\n") == Result.Ok(parsedValid)

  check("malformed JSON reports the 1-based line number"):
    isErr(TemplateManifest.parse("not json"), "malformed line 1 in jo-templates.jsonl")

  check("a missing required field is reported as malformed"):
    isErr(TemplateManifest.parse("""{"name": "web-app"}"""), "malformed line 1")

  check("duplicate name is an error"):
    val dup =
      """{"name": "web-app", "path": "a"}
        |{"name": "web-app", "path": "b"}""".stripMargin
    isErr(TemplateManifest.parse(dup), "duplicate template name 'web-app'")

  check("invalid template name is an error"):
    isErr(TemplateManifest.parse("""{"name": "-bad", "path": "a"}"""), "invalid template name")

  check("absolute path is an error"):
    isErr(TemplateManifest.parse("""{"name": "web-app", "path": "/etc/passwd"}"""), "invalid path")

  check("'..' segment in path is an error"):
    isErr(TemplateManifest.parse("""{"name": "web-app", "path": "a/../../b"}"""), "invalid path")

  check("an empty path is an error ('.' is the only spelling for the repo root)"):
    isErr(TemplateManifest.parse("""{"name": "web-app", "path": ""}"""), "invalid path")

  check("'.' as a path is accepted (the repo root)"):
    TemplateManifest.parse("""{"name": "web-app", "path": "."}""") ==
      Result.Ok(List(TemplateEntry("web-app", ".", None)))

  check("a trailing '/' in a path is accepted (harmless, common directory notation)"):
    TemplateManifest.parse("""{"name": "web-app", "path": "templates/web-app/"}""") ==
      Result.Ok(List(TemplateEntry("web-app", "templates/web-app/", None)))

  check("a doubled '/' in a path is an error"):
    isErr(TemplateManifest.parse("""{"name": "web-app", "path": "templates//web-app"}"""), "invalid path")

  check("a present but wrong-typed description is an error, not silently treated as absent"):
    isErr(TemplateManifest.parse("""{"name": "web-app", "path": ".", "description": 123}"""), "malformed line 1")

  check("fields beyond name/path/description are allowed and ignored"):
    TemplateManifest.parse("""{"name": "web-app", "path": ".", "minJoVersion": "1.5"}""") ==
      Result.Ok(List(TemplateEntry("web-app", ".", None)))

  check("resolve: explicit name found"):
    TemplateManifest.resolve(parsedValid, Some("cli"), "acme/repo") == Result.Ok(parsedValid(1))

  check("resolve: explicit name not found lists available names"):
    isErr(TemplateManifest.resolve(parsedValid, Some("nope"), "acme/repo"), "Available: web-app, cli")

  check("resolve: no name, zero entries is an error"):
    isErr(TemplateManifest.resolve(Nil, None, "acme/repo"), "declares no templates")

  check("resolve: no name, exactly one entry resolves to it"):
    TemplateManifest.resolve(List(parsedValid.head), None, "acme/repo") == Result.Ok(parsedValid.head)

  check("resolve: no name, multiple entries is an ambiguity error, not a guess"):
    isErr(TemplateManifest.resolve(parsedValid, None, "acme/repo"), "declares multiple templates, pick one: web-app, cli")

  if failed then List(Paths.get("TemplateManifest")) else Nil

// ---- TemplateArchive suite -------------------------------------------------------

private def runTemplateArchiveTests(): List[Path] =
  var failed = false

  def check(label: String)(body: => Boolean): Unit =
    val ok =
      try body
      catch
        case e: Exception =>
          println(s"  threw: ${e.getMessage}")
          false

    if ok then println(s"  ok: $label")
    else
      println(s"FAIL: $label")
      failed = true

  def buildZip(entries: Map[String, String]): Path =
    val zipFile = Files.createTempFile("jo-template-test-", ".zip")
    val out = ZipOutputStream(Files.newOutputStream(zipFile))
    try
      for (name, content) <- entries do
        out.putNextEntry(ZipEntry(name))
        out.write(content.getBytes("UTF-8"))
        out.closeEntry()
    finally out.close()
    zipFile

  val manifest =
    """{"name": "web-app", "path": "templates/web-app"}
      |{"name": "root", "path": "."}
      |{"name": "broken-path", "path": "does/not/exist"}
      |{"name": "file-path", "path": "README.md"}
      |""".stripMargin

  val repoZip = buildZip(Map(
    "repo-main/jo-templates.jsonl"            -> manifest,
    "repo-main/README.md"                     -> "hello",
    "repo-main/templates/web-app/src/Main.jo" -> "def main = println \"web-app\"\n",
  ))

  check("resolves ':name' against the manifest found inside the archive, extracts only that subtree, and leaves no staging directory behind"):
    val destParent = Files.createTempDirectory("jo-template-test-dest-")
    val dest = destParent.resolve("myapp")
    TemplateArchive.extract(repoZip, Some("web-app"), dest, "acme/repo at main") match
      case Result.Ok(_) =>
        Files.readString(dest.resolve("src/Main.jo")) == "def main = println \"web-app\"\n"
          && !Files.exists(dest.resolve("README.md"))
          && Files.list(destParent).iterator.asScala.toList == List(dest)
      case Result.Err(_) => false

  check("a failure writing the destination (e.g. it's non-empty and can't be replaced) leaves it untouched and no staging debris behind"):
    val parent = Files.createTempDirectory("jo-template-test-parent-")
    val dest = parent.resolve("existing")
    Files.createDirectory(dest)
    Files.writeString(dest.resolve("keep.txt"), "do not touch")

    val source = Files.createTempDirectory("jo-template-test-source-")
    Files.writeString(source.resolve("a.txt"), "hi")

    TemplateArchive.copyTree(source, dest) match
      case Result.Err(_) =>
        Files.list(dest).iterator.asScala.toList.map(_.getFileName.toString) == List("keep.txt")
          && Files.list(parent).iterator.asScala.toList == List(dest)
      case Result.Ok(_) => false

  check("a manifest entry with 'path: .' copies the whole repo root, including jo-templates.jsonl itself"):
    val dest = Files.createTempDirectory("jo-template-test-dest-")
    TemplateArchive.extract(repoZip, Some("root"), dest, "acme/repo at main") match
      case Result.Ok(_)  => Files.exists(dest.resolve("README.md")) && Files.exists(dest.resolve("jo-templates.jsonl"))
      case Result.Err(_) => false

  check("no name given, multiple entries, is an ambiguity error, not a guess"):
    val dest = Files.createTempDirectory("jo-template-test-dest-")
    TemplateArchive.extract(repoZip, None, dest, "acme/repo at main") match
      case Result.Err(msg) => msg.contains("declares multiple templates")
      case _                => false

  check("a requested name absent from the manifest is an error"):
    val dest = Files.createTempDirectory("jo-template-test-dest-")
    TemplateArchive.extract(repoZip, Some("nope"), dest, "acme/repo at main") match
      case Result.Err(msg) => msg.contains("no template 'nope'")
      case _                => false

  check("a manifest path absent from the archive is a 'not found' error"):
    val dest = Files.createTempDirectory("jo-template-test-dest-")
    TemplateArchive.extract(repoZip, Some("broken-path"), dest, "acme/repo at main") match
      case Result.Err(msg) => msg.contains("template path 'does/not/exist' not found")
      case _                => false

  check("a manifest path pointing at a file, not a directory, is a 'not found' error"):
    val dest = Files.createTempDirectory("jo-template-test-dest-")
    TemplateArchive.extract(repoZip, Some("file-path"), dest, "acme/repo at main") match
      case Result.Err(msg) => msg.contains("not found")
      case _                => false

  check("an archive with no jo-templates.jsonl is not a valid Jo template repo"):
    val bareZip = buildZip(Map("repo-main/src/Main.jo" -> "def main = println \"hi\"\n"))
    val dest = Files.createTempDirectory("jo-template-test-dest-")
    TemplateArchive.extract(bareZip, None, dest, "acme/bare at main") match
      case Result.Err(msg) => msg.contains("has no jo-templates.jsonl")
      case _                => false

  check("zip-slip entries are rejected before the manifest is even read"):
    val evilZip = buildZip(Map("repo-main/../../evil.txt" -> "pwned"))
    val dest = Files.createTempDirectory("jo-template-test-dest-")
    TemplateArchive.extract(evilZip, None, dest, "acme/evil at main") match
      case Result.Err(_) => true
      case Result.Ok(_)  => false

  if failed then List(Paths.get("TemplateArchive")) else Nil

// ---- GithubTemplateProvider suite -------------------------------------------
// Covers the thin HTTP/zip-fallback layer (resolution and extraction logic
// are already covered above with no network involved) and the git-clone
// path — the latter against a real local git repo fixture, since that's
// what actually proves executable bits and symlinks survive.

private def runGithubTemplateProviderTests(): List[Path] =
  import com.sun.net.httpserver.{HttpExchange, HttpServer}
  import java.net.InetSocketAddress
  import java.nio.file.Paths
  import java.nio.file.attribute.{PosixFilePermission, PosixFilePermissions}

  var failed = false

  def check(label: String)(body: => Boolean): Unit =
    val ok =
      try body
      catch
        case e: Exception =>
          println(s"  threw: ${e.getMessage}")
          false

    if ok then println(s"  ok: $label")
    else
      println(s"FAIL: $label")
      failed = true

  def respond(exchange: HttpExchange, status: Int, bytes: Array[Byte]): Unit =
    exchange.sendResponseHeaders(status, bytes.length)
    val os = exchange.getResponseBody
    try os.write(bytes) finally os.close()

  def buildZipBytes(entries: Map[String, String]): Array[Byte] =
    val buf = ByteArrayOutputStream()
    val out = ZipOutputStream(buf)
    try
      for (name, content) <- entries do
        out.putNextEntry(ZipEntry(name))
        out.write(content.getBytes("UTF-8"))
        out.closeEntry()
    finally out.close()
    buf.toByteArray

  val zipBytes = buildZipBytes(Map(
    "repo-main/jo-templates.jsonl" -> """{"name": "default", "path": "."}""",
    "repo-main/src/Main.jo"        -> "def main = println \"ok\"\n",
  ))
  val manifestBytes = """{"name": "default", "path": "."}""".getBytes("UTF-8")

  val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
  server.createContext("/acme/repo/HEAD/jo-templates.jsonl", (ex: HttpExchange) => respond(ex, 200, manifestBytes))
  server.createContext("/acme/repo/zip/HEAD", (ex: HttpExchange) => respond(ex, 200, zipBytes))
  server.createContext("/acme/missing/HEAD/jo-templates.jsonl", (ex: HttpExchange) => respond(ex, 404, Array.emptyByteArray))
  server.createContext("/acme/missing/zip/HEAD", (ex: HttpExchange) => respond(ex, 404, Array.emptyByteArray))
  // A context path is matched against the *decoded* request path — if a gitref
  // containing '/' or '#' were embedded in the request URL unencoded (or
  // encoded incorrectly), these two would never be hit, and the requests
  // would 404 or get truncated at the '#' instead.
  server.createContext("/acme/slashref/release/1.0/jo-templates.jsonl", (ex: HttpExchange) => respond(ex, 200, manifestBytes))
  server.createContext("/acme/hashref/v1#odd/jo-templates.jsonl", (ex: HttpExchange) => respond(ex, 200, manifestBytes))
  server.start()

  // Any request gets a bare 401 with no body — enough to make git's HTTPS
  // clone attempt bail out with "could not read Username ... terminal
  // prompts disabled" (GIT_TERMINAL_PROMPT=0), the same failure a real
  // private GitHub repo produces over HTTPS with no credential helper
  // configured. Used below as a clone candidate that's guaranteed to fail,
  // to exercise the fall-through-to-the-next-candidate logic.
  val authServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
  authServer.createContext("/", (ex: HttpExchange) => respond(ex, 401, Array.emptyByteArray))
  authServer.start()

  try
    val baseUrl = s"http://127.0.0.1:${server.getAddress.getPort}"
    // gitAvailable = false so these checks deterministically exercise the
    // zip fallback regardless of whether git happens to be on PATH in the
    // environment running these tests (it is, in this repo's own dev/CI —
    // that's exactly why the git path below needs its own forced-true case).
    val provider = GithubTemplateProvider(baseUrl, baseUrl, gitAvailable = false)

    check("manifest: 200 response parses correctly"):
      provider.manifest("acme/repo", "HEAD") == Result.Ok(List(TemplateEntry("default", ".", None)))

    check("manifest: 404 is reported as 'not a valid Jo template repo'"):
      provider.manifest("acme/missing", "HEAD") match
        case Result.Err(msg) => msg.contains("acme/missing has no jo-templates.jsonl")
        case _                => false

    check("fetch: 200 response downloads the zip once and resolves the manifest from inside it"):
      val dest = Files.createTempDirectory("jo-template-http-test-")
      provider.fetch("acme/repo", "HEAD", None, dest) match
        case Result.Ok(_)  => Files.exists(dest.resolve("src/Main.jo"))
        case Result.Err(_) => false

    check("fetch: 404 is reported with the identifier and ref"):
      val dest = Files.createTempDirectory("jo-template-http-test-")
      provider.fetch("acme/missing", "HEAD", None, dest) match
        case Result.Err(msg) => msg.contains("acme/missing") && msg.contains("HEAD")
        case _                => false

    check("manifest: connection failure is a fetch error, not a crash"):
      val deadProvider = GithubTemplateProvider("http://127.0.0.1:1", "http://127.0.0.1:1", gitAvailable = false)
      deadProvider.manifest("acme/repo", "HEAD") match
        case Result.Err(msg) => msg.contains("failed to fetch")
        case _                => false

    check("manifest: a '/' in gitref (a slash-containing branch name) reaches the right URL"):
      provider.manifest("acme/slashref", "release/1.0") == Result.Ok(List(TemplateEntry("default", ".", None)))

    check("manifest: a '#' in gitref is percent-encoded, not misread as a URL fragment"):
      provider.manifest("acme/hashref", "v1#odd") == Result.Ok(List(TemplateEntry("default", ".", None)))

    check("manifest: an identifier with characters outside the safe charset is rejected before any request"):
      provider.manifest("ac me/repo", "HEAD") match
        case Result.Err(msg) => msg.contains("invalid GitHub identifier")
        case _                => false

    // `git` is assumed available, not merely probed for — this repo can't be
    // built, tested, or even checked out without it, so any environment
    // capable of running this suite already has it. If that assumption is
    // ever wrong, these checks should fail loudly, not silently skip.
    //
    // A real local git repo, not a mock — only an actual `git checkout`
    // proves executable bits and symlinks survive. cloneBaseUrl + owner/repo
    // must line up with GithubTemplateProvider's "$cloneBaseUrl/$owner/$repo.git"
    // URL construction, so the fixture path has to end in ".git" too.
    val gitReposParent = Files.createTempDirectory("jo-template-git-test-")
    val gitRepoDir = gitReposParent.resolve("acme").resolve("repo.git")
    Files.createDirectories(gitRepoDir.resolve("bin"))
    Files.writeString(gitRepoDir.resolve("bin/run"), "#!/bin/sh\necho hi\n")
    Files.setPosixFilePermissions(gitRepoDir.resolve("bin/run"), PosixFilePermissions.fromString("rwxr-xr-x"))
    Files.createSymbolicLink(gitRepoDir.resolve("run-link"), Paths.get("bin/run"))
    Files.writeString(gitRepoDir.resolve("jo-templates.jsonl"), """{"name": "default", "path": "."}""" + "\n")

    val gitSetup = runShellCmd(
      "git init -q && git config user.email t@example.com && git config user.name Test " +
        "&& git add -A && git commit -q -m init",
      gitRepoDir,
    )

    val gitProvider = GithubTemplateProvider(
      rawBaseUrl = baseUrl,
      archiveBaseUrl = baseUrl,
      cloneBaseUrls = List(s"$gitReposParent/"),
      gitAvailable = true,
    )

    check("fetch (git): fixture repo committed cleanly, with the executable bit and symlink actually set"):
      gitSetup.isInstanceOf[Result.Ok[?]]
        && Files.getPosixFilePermissions(gitRepoDir.resolve("bin/run")).contains(PosixFilePermission.OWNER_EXECUTE)
        && Files.isSymbolicLink(gitRepoDir.resolve("run-link"))

    check("fetch (git): clones, preserves the executable bit and the symlink, and strips .git"):
      val dest = Files.createTempDirectory("jo-template-git-http-test-")
      gitProvider.fetch("acme/repo", "HEAD", None, dest) match
        case Result.Ok(_) =>
          val executable = Files.getPosixFilePermissions(dest.resolve("bin/run")).contains(PosixFilePermission.OWNER_EXECUTE)
          val link = dest.resolve("run-link")
          val linkOk = Files.isSymbolicLink(link) && Files.readSymbolicLink(link) == Paths.get("bin/run")
          val noGitMetadata = !Files.exists(dest.resolve(".git"))
          executable && linkOk && noGitMetadata
        case Result.Err(_) => false

    check("fetch (git): a nonexistent repo is a clean error, not a crash"):
      val dest = Files.createTempDirectory("jo-template-git-http-test-")
      gitProvider.fetch("acme/does-not-exist", "HEAD", None, dest) match
        case Result.Err(_) => true
        case Result.Ok(_)  => false

    check("manifest (git): reads jo-templates.jsonl from a git checkout, not raw HTTP, when git is available"):
      gitProvider.manifest("acme/repo", "HEAD") == Result.Ok(List(TemplateEntry("default", ".", None)))

    val unauthedHttpsUrl = s"http://127.0.0.1:${authServer.getAddress.getPort}/"

    // The second candidate here is a plain filesystem path, not a real
    // ssh:// / git@host: URL — GIT_SSH_COMMAND has no effect on a
    // local-path remote, so this doesn't exercise real SSH transport, only
    // the candidate-list logic itself (first candidate fails, second one is
    // tried and its result is what's returned).
    val multiCandidateProvider = GithubTemplateProvider(
      rawBaseUrl = baseUrl,
      archiveBaseUrl = baseUrl,
      cloneBaseUrls = List(unauthedHttpsUrl, s"$gitReposParent/"),
      gitAvailable = true,
    )

    check("fetch (git): a first candidate URL needing credentials falls through to the next one, which succeeds"):
      val dest = Files.createTempDirectory("jo-template-multi-candidate-test-")
      multiCandidateProvider.fetch("acme/repo", "HEAD", None, dest) match
        case Result.Ok(_)  => Files.exists(dest.resolve("jo-templates.jsonl"))
        case Result.Err(_) => false

    val allCandidatesFailProvider = GithubTemplateProvider(
      rawBaseUrl = baseUrl,
      archiveBaseUrl = baseUrl,
      cloneBaseUrls = List(unauthedHttpsUrl, s"${gitReposParent.resolve("no-such-dir")}/"),
      gitAvailable = true,
    )

    check("fetch (git): every candidate URL failing reports all of their failures, not just the last one"):
      val dest = Files.createTempDirectory("jo-template-multi-candidate-fail-test-")
      allCandidatesFailProvider.fetch("acme/repo", "HEAD", None, dest) match
        case Result.Err(msg) => msg.contains("could not fetch acme/repo from any configured clone URL") && msg.lines.count() >= 3
        case Result.Ok(_)    => false

  finally
    server.stop(0)
    authServer.stop(0)

  if failed then List(Paths.get("GithubTemplateProvider")) else Nil

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
  val steps  = new mutable.ArrayBuffer[Step]
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
      val buf = new mutable.ArrayBuffer[String]
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
    val result = New.parseArgs(cmdArgs).flatMap:
      case New.Args.Scaffold(name, isLib, None) =>
        New.scaffold(name, isLib, specDir)
      case New.Args.Scaffold(name, isLib, Some(ref)) =>
        New.scaffoldFromTemplate(name, ref, specDir, testTemplateProvider(specDir))
      case New.Args.ListTemplates(ref) =>
        New.listTemplates(ref, testTemplateProvider(specDir))
    return result match
      case ok @ Result.Ok(_) => ok
      case Result.Err(msg)   => Result.Err(s"error: $msg\n")

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

/** Ignores the requested host — every `jo new --template` scenario test
 *  fixture is a single local repo, so there's nothing to route between.
 */
private def testTemplateProvider(specDir: Path): String => Result[TemplateProvider] =
  _ => Result.Ok(LocalTemplateProvider(specDir.resolve("template-src")))

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
  Project.load(specPath, resolveJo).flatMap(project => DependencyResolver.resolveProject(project, project.moduleIds)) match
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
      case Some(lock) => DependencyResolver.resolveProject(project, project.moduleIds, lock).map(resolved => (project, resolved))
      case None       => DependencyResolver.resolveProject(project, project.moduleIds).map(resolved => (project, resolved))

  val result = resolved.flatMap: (project, resolved) =>
    validateLockPackageDepths(project, resolved).flatMap: _ =>
      val locked = new mutable.ArrayBuffer[LockedPackage]
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
  Using.resource(Files.walk(baseDir)): stream =>
    stream.iterator.asScala
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

  val out = new mutable.ArrayBuffer[String]
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

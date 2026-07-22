package tool

import java.io.IOException
import java.nio.file.{Files, LinkOption, Path}
import scala.collection.mutable.ArrayBuffer
import scala.jdk.CollectionConverters.*

object ResourcePaths:
  /** Expand resource paths relative to baseDir. Directories include regular files recursively. */
  def expand(entries: List[ResourceMapping], baseDir: Path): Result[List[ResourceFile]] =
    val base = baseDir.toAbsolutePath.normalize()
    val files = ArrayBuffer.empty[ResourceFile]

    entries.foldLeft(Result.unit): (acc, mapping) =>
      acc.flatMap(_ => expandEntry(mapping, base, files))
    .flatMap(_ => finishExpansion(files))

  def fromModule(owner: String, entries: List[ResourceMapping], baseDir: Path): Result[Option[ResourceGroup]] =
    expand(entries, baseDir).map: files =>
      if files.isEmpty then None else Some(ResourceGroup(owner, files))

  def fromPackage(owner: String, unpackedDir: Path): Result[Option[ResourceGroup]] =
    val root = unpackedDir.resolve("resources").normalize()
    if !Files.exists(root) then Result.Ok(None)
    else if !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) then
      Result.Err(s"package resource root is not a directory: ${LogFormat.path(root)}")
    else
      val files = ArrayBuffer.empty[ResourceFile]
      expandDir(root, root, Path.of(""), files).flatMap(_ => finishExpansion(files)).map: expandedFiles =>
        if expandedFiles.isEmpty then None else Some(ResourceGroup(owner, expandedFiles))

  def copyFiles(files: List[ResourceFile], targetRoot: Path): Result[Unit] =
    try
      files.foldLeft(Result.unit): (acc, file) =>
        acc.flatMap: _ =>
          val target = targetRoot.resolve(file.resourcePath.toString).normalize()
          if !target.startsWith(targetRoot.normalize()) then
            Result.Err(s"resource target escapes output directory: ${file.resourcePath}")
          else if Files.exists(target) then
            Result.Err(s"duplicate resource target: ${file.resourcePath}")
          else
            Files.createDirectories(target.getParent)
            Files.copy(file.inputFile, target)
            Result.unit
    catch
      case e: IOException => Result.Err(s"could not copy resource: ${e.getMessage}")

  def copyGroups(groups: List[ResourceGroup], targetRoot: Path): Result[Unit] =
    val files = groups.flatMap: group =>
      group.files.map: file =>
        ResourceFile(file.inputFile, Path.of(group.owner).resolve(file.resourcePath.toString), file.sourceArchivePath)
    copyFiles(files, targetRoot)

  private def expandEntry(mapping: ResourceMapping, base: Path, files: ArrayBuffer[ResourceFile]): Result[Unit] =
    val raw = Path.of(mapping.source)
    if raw.isAbsolute then
      return Result.Err(s"resource path must be relative: ${mapping.source}")

    val path = base.resolve(raw).normalize()
    if !path.startsWith(base) then
      return Result.Err(s"resource path escapes project directory: ${mapping.source}")

    if !Files.exists(path, LinkOption.NOFOLLOW_LINKS) then
      Result.Err(s"resource path not found: ${LogFormat.path(path)}")
    else if Files.isSymbolicLink(path) then
      Result.Err(s"resource path must not be a symlink: ${LogFormat.path(path)}")
    else if Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) then
      expandDir(path, base, Path.of(mapping.dest), files)
    else if Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) then
      makeResourceFile(path, base, Path.of(mapping.dest)).map: file =>
        files += file
        ()
    else
      Result.Err(s"resource path is not a file or directory: ${LogFormat.path(path)}")

  private def expandDir(
    path: Path,
    base: Path,
    targetBase: Path,
    files: ArrayBuffer[ResourceFile],
  ): Result[Unit] =
    val root = path.toAbsolutePath.normalize()
    val relBase = base.toAbsolutePath.normalize()
    try
      val stream = Files.walk(root)
      try
        val iter = stream.iterator.asScala
        var error: Option[String] = None
        while error.isEmpty && iter.hasNext do
          val file = iter.next()
          if Files.isSymbolicLink(file) then
            error = Some(s"resource path must not be a symlink: ${LogFormat.path(file)}")
          else if Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) then
            val nested = root.relativize(file)
            makeResourceFile(file, relBase, targetBase.resolve(nested.toString)) match
              case Result.Ok(resource) =>
                files += resource
              case Result.Err(msg) =>
                error = Some(msg)
        error match
          case Some(msg) => Result.Err(msg)
          case None      => Result.unit
      finally stream.close()
    catch case e: IOException =>
      Result.Err(s"could not read resource directory ${LogFormat.path(path)}: ${e.getMessage}")

  private def makeResourceFile(inputFile: Path, base: Path, resourcePath: Path): Result[ResourceFile] =
    val sourceArchivePath = base.relativize(inputFile)
    validatePathObject(sourceArchivePath, "resource file path").flatMap: _ =>
      validatePathObject(resourcePath.normalize(), "resource target").map: _ =>
        ResourceFile(inputFile, resourcePath.normalize(), sourceArchivePath)

  private def finishExpansion(files: ArrayBuffer[ResourceFile]): Result[List[ResourceFile]] =
    requireDistinctTargets(files).map: _ =>
      files.toList.sortBy(_.resourcePath.toString)

  private def requireDistinctTargets(files: Iterable[ResourceFile]): Result[Unit] =
    val seen = collection.mutable.Set.empty[String]
    var duplicate: Option[String] = None
    for file <- files do
      val key = normalizeRel(file.resourcePath)
      if !seen.add(key) && duplicate.isEmpty then
        duplicate = Some(key)
    duplicate match
      case Some(key) => Result.Err(s"duplicate resource target: $key")
      case None      => Result.unit

  private def normalizeRel(path: Path): String =
    logicalRel(path.normalize())

  private def logicalRel(path: Path): String =
    path.iterator.asScala.map(_.toString).mkString("/")

  def parseMapping(entry: String): Result[ResourceMapping] =
    if isWindowsAbsolute(entry) then
      return validatePathSyntax(entry, "resource path").map(path => ResourceMapping(path, path))

    val colonCount = entry.count(_ == ':')
    if colonCount > 1 then
      return Result.Err(s"resource mapping must contain at most one ':' separator: $entry")

    if colonCount == 0 then
      validatePathSyntax(entry, "resource path").map(path => ResourceMapping(path, path))
    else
      val idx = entry.indexOf(':')
      val sourceRaw = entry.substring(0, idx)
      val destRaw = entry.substring(idx + 1)
      validatePathSyntax(sourceRaw, "resource source path").flatMap: source =>
        validatePathSyntax(destRaw, "resource destination path").map: dest =>
          ResourceMapping(source, dest)

  def validateEntrySyntax(entry: String): Result[Unit] =
    parseMapping(entry).map(_ => ())

  private def validatePathObject(path: Path, label: String): Result[Unit] =
    validatePathSyntax(logicalRel(path), label).map(_ => ())

  private def validatePathSyntax(entry: String, label: String): Result[String] =
    if entry.isEmpty then
      return Result.Err(s"$label must not be empty")
    if entry.contains("\\") then
      return Result.Err(s"$label '$entry' uses backslash; use '/' separators")
    if entry.startsWith("/") || isWindowsAbsolute(entry) then
      return Result.Err(s"$label must be relative: $entry")
    if entry.contains(":") then
      return Result.Err(s"$label '$entry' contains ':'; ':' is reserved for resource mappings")
    if hasGlobSyntax(entry) then
      return Result.Err(s"$label '${entry}' uses glob syntax; use a directory or file path")
    if entry.endsWith("//") then
      return Result.Err(s"$label contains empty segment: $entry")

    val logical = if entry.endsWith("/") then entry.dropRight(1) else entry
    if logical.isEmpty then
      return Result.Err(s"$label must not be empty")

    logical.split("/", -1).find(segment => segment.isEmpty || segment == "." || segment == "..") match
      case Some("")   => Result.Err(s"$label contains empty segment: $entry")
      case Some(".")  => Result.Err(s"$label contains '.' segment: $entry")
      case Some("..") => Result.Err(s"$label contains '..' segment: $entry")
      case Some(_)    => Result.Ok(logical)
      case None       => Result.Ok(logical)

  private def isWindowsAbsolute(entry: String): Boolean =
    entry.length >= 3 && entry(1) == ':' && entry(2) == '/' && entry(0).isLetter

  private def hasGlobSyntax(entry: String): Boolean =
    entry.exists(ch => ch == '*' || ch == '?' || ch == '[' || ch == ']' || ch == '{' || ch == '}')

package tool

import java.io.IOException
import java.nio.file.{Files, LinkOption, Path}
import scala.jdk.CollectionConverters.*

object ResourcePaths:
  /** Expand resource paths relative to baseDir. Directories include regular files recursively. */
  def expand(entries: List[ResourceMapping], baseDir: Path): Result[List[ResourceFile]] =
    val base = baseDir.toAbsolutePath.normalize()

    entries.foldLeft(Result.Ok(List.empty[ResourceFile]): Result[List[ResourceFile]]): (acc, mapping) =>
      acc.flatMap: files =>
        expandEntry(mapping, base).map(files ++ _)
    .flatMap(requireDistinctTargets)
    .map(_.sortBy(_.resourcePath.toString))

  def fromModule(owner: String, entries: List[ResourceMapping], baseDir: Path): Result[Option[ResourceGroup]] =
    expand(entries, baseDir).map: files =>
      if files.isEmpty then None else Some(ResourceGroup(owner, files))

  def fromPackage(owner: String, unpackedDir: Path): Result[Option[ResourceGroup]] =
    val root = unpackedDir.resolve("resources").normalize()
    if !Files.exists(root) then Result.Ok(None)
    else if !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) then
      Result.Err(s"package resource root is not a directory: ${LogFormat.path(root)}")
    else
      expandDir(root, root, Path.of("")).flatMap(requireDistinctTargets).map: files =>
        if files.isEmpty then None else Some(ResourceGroup(owner, files))

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

  private def expandEntry(mapping: ResourceMapping, base: Path): Result[List[ResourceFile]] =
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
      expandDir(path, base, Path.of(mapping.dest))
    else if Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) then
      makeResourceFile(path, base, Path.of(mapping.dest)).map(List(_))
    else
      Result.Err(s"resource path is not a file or directory: ${LogFormat.path(path)}")

  private def expandDir(path: Path, base: Path, targetBase: Path): Result[List[ResourceFile]] =
    val root = path.toAbsolutePath.normalize()
    val relBase = base.toAbsolutePath.normalize()
    try
      val stream = Files.walk(root)
      try
        val allFiles = stream.iterator.asScala.toList
        val badSymlink = allFiles.find(file => Files.isSymbolicLink(file))
        badSymlink match
          case Some(link) =>
            Result.Err(s"resource path must not be a symlink: ${LogFormat.path(link)}")
          case None =>
            allFiles
              .filter(file => Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
              .foldLeft(Result.Ok(List.empty[ResourceFile]): Result[List[ResourceFile]]): (acc, file) =>
                acc.flatMap: files =>
                  val nested = root.relativize(file)
                  makeResourceFile(file, relBase, targetBase.resolve(nested.toString)).map(files :+ _)
      finally stream.close()
    catch case e: IOException =>
      Result.Err(s"could not read resource directory ${LogFormat.path(path)}: ${e.getMessage}")

  private def makeResourceFile(inputFile: Path, base: Path, resourcePath: Path): Result[ResourceFile] =
    val sourceArchivePath = base.relativize(inputFile)
    validatePathObject(sourceArchivePath, "resource file path").flatMap: _ =>
      validatePathObject(resourcePath.normalize(), "resource target").map: _ =>
        ResourceFile(inputFile, resourcePath.normalize(), sourceArchivePath)

  private def requireDistinctTargets(files: List[ResourceFile]): Result[List[ResourceFile]] =
    val seen = collection.mutable.Set.empty[String]
    var duplicate: Option[String] = None
    for file <- files do
      val key = normalizeRel(file.resourcePath)
      if !seen.add(key) && duplicate.isEmpty then
        duplicate = Some(key)
    duplicate match
      case Some(key) => Result.Err(s"duplicate resource target: $key")
      case None      => Result.Ok(files)

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

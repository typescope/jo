package tool.template

import java.nio.file.{Files, Path}

import tool.Result

/** Filesystem-backed [[TemplateProvider]]: reads `jo-templates.jsonl` and
 *  template directories straight off `root`, no network, no zip.
 *
 *  Used by scenario tests to exercise the full `jo new --template` pipeline
 *  (ref parsing through manifest resolution to files landing on disk)
 *  without a server — mirrors how `LocalPackageProvider` stands in for
 *  `HttpPackageProvider` in the existing package-manager tests. `identifier`
 *  and `gitref` only appear in error messages; the fixture content always
 *  comes from `root`.
 */
case class LocalTemplateProvider(root: Path) extends TemplateProvider:
  def manifest(identifier: String, gitref: String): Result[List[TemplateEntry]] =
    val manifestFile = root.resolve("jo-templates.jsonl")

    if !Files.exists(manifestFile) then
      Result.Err(s"error: $identifier has no jo-templates.jsonl — not a valid Jo template repo")
    else
      TemplateManifest.parse(Files.readString(manifestFile))

  def fetch(identifier: String, gitref: String, path: String, destDir: Path): Result[Unit] =
    val source = if path == "." then root else root.resolve(path).normalize()

    if !source.startsWith(root) || !Files.isDirectory(source) then
      Result.Err(s"error: template path '$path' not found in $identifier at $gitref")
    else
      TemplateArchive.copyTree(source, destDir)
      Result.unit

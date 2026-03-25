package tool

import java.nio.file.Paths
import tool.toml.{TomlParser, TomlError}

/** Loads a jo.toml, resolves path deps, and prints the resolved graph followed by
 *  the build plan. Used by bin/test-tool for file-based regression tests. */
@main def printPlan(specFile: String): Unit =
  try
    given PackageProvider = PackageProvider.default()
    Build.makePlanResult(specFile): constraint =>
      val joVersion = constraint.minimumVersion
      val joPath    = Paths.get("jo")
      Result.Ok((joVersion, joPath))
    match
      case Result.Ok(plan) =>
        val specDir = Paths.get(specFile).toAbsolutePath.getParent
        println(PlanPrinter.print(plan, specDir))

      case Result.Err(msg) =>
        println(s"error: $msg")
  catch
    case e: ToolError  => println(s"error: ${e.getMessage}")
    case e: TomlError  => println(s"error: ${e.getMessage}")

/** Reads a .toml file, decodes it as the given kind, and prints the result.
 *  kind: build-spec | lock-file | package-meta
 *  Used by bin/test-toml for file-based decode regression tests. */
@main def printModel(kind: String, path: String): Unit =
  try
    val src = java.nio.file.Files.readString(java.nio.file.Path.of(path))
    val doc = TomlParser.parse(src)

    val output = kind match
      case "build-spec"   => ToolPrinter.print(BuildSpec.decode(doc))
      case "lock-file"    => ToolPrinter.print(LockFile.decode(doc))
      case "package-meta" => ToolPrinter.print(PackageMeta.decode(doc))
      case _              => sys.error(s"unknown kind '$kind'")

    println(output)
  catch case e: TomlError =>
    println(s"error: ${e.getMessage}")

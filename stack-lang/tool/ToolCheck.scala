package tool

import java.nio.file.Paths
import tool.toml.{TomlParser, TomlError}

/** Loads a jo.toml, resolves path deps, and prints the resolved graph followed by
 *  the build plan. Used by bin/test-tool for file-based regression tests. */
@main def printPlan(specFile: String): Unit =
  try
    val path    = Paths.get(specFile).toAbsolutePath
    val specDir = path.getParent
    val stem    = path.getFileName.toString.stripSuffix(".toml")
    val spec    = Graph.loadSpec(specDir, path.getFileName.toString)
    val graph  = Graph.resolve(spec, specDir)
    val plan   = Planner.plan(graph, stem, java.nio.file.Paths.get("jo"))
    println(GraphPrinter.print(graph, specDir))
    println()
    println(PlanPrinter.print(plan, specDir))
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

package tool

import java.nio.file.Paths
import tool.toml.{TomlParser, TomlError}

/** Loads a jo.toml, resolves path deps, and prints the resolved graph followed by
 *  the build plan. Used by bin/test-tool for file-based regression tests. */
@main def toolCheck(specFile: String): Unit =
  try
    val path    = Paths.get(specFile).toAbsolutePath
    val specDir = path.getParent
    val stem    = path.getFileName.toString.stripSuffix(".toml")
    val spec    = Graph.loadSpec(specDir, path.getFileName.toString)
    val graph   = Graph.resolve(spec, specDir)
    val plan    = Planner.plan(graph, stem)
    println(GraphPrinter.print(graph, specDir))
    println()
    println(PlanPrinter.print(plan, specDir))
  catch
    case e: ToolError  => println(s"error: ${e.getMessage}")
    case e: TomlError  => println(s"error: ${e.getMessage}")

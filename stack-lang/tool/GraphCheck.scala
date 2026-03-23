package tool

import java.nio.file.Paths

/** Loads a jo.toml at the given path, resolves path deps, and prints the ResolvedGraph.
 *  Used by bin/test-tool for file-based graph tests. */
@main def graphCheck(specFile: String): Unit =
  try
    val path    = Paths.get(specFile).toAbsolutePath
    val specDir = path.getParent
    val spec    = Graph.loadSpec(specDir, path.getFileName.toString)
    val graph   = Graph.resolve(spec, specDir)
    println(GraphPrinter.print(graph, specDir))
  catch
    case e: ToolError  => println(s"error: ${e.getMessage}")
    case e: tool.toml.TomlError => println(s"error: ${e.getMessage}")

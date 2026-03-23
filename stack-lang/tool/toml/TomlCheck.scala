package tool.toml

/** Reads a .toml file and prints its parsed AST to stdout.
 *  Used by bin/test-toml for file-based regression tests. */
@main def tomlCheck(path: String): Unit =
  val src = java.nio.file.Files.readString(java.nio.file.Path.of(path))
  val doc = TomlParser.parse(src)
  println(TomlPrinter.print(doc))

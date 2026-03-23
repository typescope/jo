package tool

import tool.toml.TomlParser

/** Reads a .toml file, decodes it as the given kind, and prints the result.
 *  kind: build-spec | lock-file | package-meta
 *  Used by bin/test-toml for file-based decode regression tests. */
@main def toolCheck(kind: String, path: String): Unit =
  val src = java.nio.file.Files.readString(java.nio.file.Path.of(path))
  val doc = TomlParser.parse(src)
  val output = kind match
    case "build-spec"   => ToolPrinter.print(BuildSpec.decode(doc))
    case "lock-file"    => ToolPrinter.print(LockFile.decode(doc))
    case "package-meta" => ToolPrinter.print(PackageMeta.decode(doc))
    case _              => sys.error(s"unknown kind '$kind'")
  println(output)

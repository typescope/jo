package tool

import java.nio.file.{Path, Paths}

/** Canonical paths under ~/.jo/cache/. */
object Cache:
  val root: Path      = Paths.get(System.getProperty("user.home")).resolve(".jo")
  val compilers: Path = root.resolve("cache/compilers")
  val packages: Path  = root.resolve("cache/packages")

  def packageDir(name: String, version: String): Path =
    packages.resolve(name).resolve(version)

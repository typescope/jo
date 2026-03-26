package tool

import java.nio.file.{Path, Paths}

/** Canonical Jo tool defaults and cache paths under ~/.jo/. */
object Config:
  val root: Path = Paths.get(System.getProperty("user.home")).resolve(".jo")
  val cache: Path = root.resolve("cache")
  val compilers: Path = cache.resolve("compilers")
  val packages: Path = cache.resolve("packages")
  val registryUrl = "https://pkg.jo-lang.org"

  def packageDir(name: String, version: String): Path =
    packages.resolve(name).resolve(version)

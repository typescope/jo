package tool

import java.nio.file.{Path, Paths}

/** Canonical Jo tool defaults and install/cache paths. */
object Config:
  val home: Path      = Paths.get(System.getProperty("user.home"))
  val root: Path      = home.resolve(".jo")
  val cache: Path     = root.resolve("cache")
  val compilers: Path = root.resolve("compilers")
  val activeBin: Path = home.resolve(".local/bin/jo")
  val packages: Path  = cache.resolve("packages")
  val index: Path     = cache.resolve("index")
  val versionsUrl     = "https://jo-lang.org/versions.jsonl"
  val registryUrl     = "https://pkg.jo-lang.org"

  def packageDir(name: String, version: String): Path =
    packages.resolve(name).resolve(version)

  def packageArchive(name: String, version: String): Path =
    packageDir(name, version).resolve(s"$name-v$version.joy")

  def packageUnpackedDir(name: String, version: String): Path =
    packageDir(name, version).resolve("unpacked")

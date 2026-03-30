package tool

import java.nio.file.{Path, Paths}

object LogFormat:
  private lazy val cwd = Paths.get("").toAbsolutePath.normalize()

  def path(path: Path): String =
    val absolute = path.toAbsolutePath.normalize()
    if absolute.startsWith(cwd) then cwd.relativize(absolute).toString
    else absolute.toString

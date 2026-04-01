package tool

import java.nio.file.{Files, Path}
import java.security.MessageDigest

object Digest:
  def sha512Hex(path: Path): String =
    val md = MessageDigest.getInstance("SHA-512")
    val in = Files.newInputStream(path)

    try
      val buf = new Array[Byte](8192)
      var n = in.read(buf)

      while n >= 0 do
        if n > 0 then md.update(buf, 0, n)
        n = in.read(buf)

    finally in.close()

    md.digest().map("%02x".format(_)).mkString

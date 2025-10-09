package common


import java.nio.charset.StandardCharsets
/**
  * Encapsulates common input/output utilities
  *
  */
object IO:
  def withFile(path: String)(fn: ByteBuffer => Unit): Unit =
    val file = new java.io.File(path)
    file.createNewFile()
    withFile(file)(fn)

  def withFile(file: java.io.File)(fn: ByteBuffer => Unit): Unit =
    val fs = new java.io.FileOutputStream(file)
    val byteBuffer: ByteBuffer = (b: Byte) => fs.write(b)
    try
      fn(byteBuffer)
    catch case e: Throwable =>
      fs.close()
      throw e

  def writeFile(path: String, data: Array[Byte]): Unit =
    writeFile(path, data, 0, data.length)

  def writeFile(path: String, data: Array[Byte], offset: Int, len: Int): Unit =
    val fos = new java.io.FileOutputStream(path)

    try
      fos.write(data, offset, len)
      fos.flush()
    finally
      fos.close()

  def withPrintWriter(path: String)(fn: java.io.PrintWriter => Unit): Unit =
    val pw = new java.io.PrintWriter(path)
    try
      fn(pw)
    finally
      pw.close()

  def withExeFile(name: String)(fn: ByteBuffer => Unit): Unit =
    val file = new java.io.File(name)
    file.createNewFile()
    file.setExecutable(true)
    withFile(file)(fn)

  def fileAsBytes(filePath: String): Array[Byte] =
    val path = java.nio.file.Path.of(filePath)
    java.nio.file.Files.readAllBytes(path)

  def fileAsString(filePath: String): String =
    val bytes = fileAsBytes(filePath)
    new String(bytes, StandardCharsets.UTF_8)

  def fileNameNoExt(file: String): String =
    val path = java.nio.file.Paths.get(file)
    val fileName = path.getFileName.toString
    fileName.replaceAll("\\.[^.]*$", "")

  def isFile(path: String): Boolean =
    val file = new java.io.File(path)
    file.isFile()

  def isDirectory(path: String): Boolean =
    val file = new java.io.File(path)
    file.isDirectory()

  def list(dir: String): List[String] =
    val file = new java.io.File(dir)
    file.listFiles.map(_.getPath).toList

  /** Get all .sast files from a directory */
  def getSastFiles(dir: String): Array[String] =
    val path = java.nio.file.Paths.get(dir)
    if !java.nio.file.Files.exists(path) then
      throw new Exception(s"Library directory does not exist: $dir")

    if !java.nio.file.Files.isDirectory(path) then
      throw new Exception(s"Not a directory: $dir")

    import scala.jdk.CollectionConverters.*
    java.nio.file.Files.list(path)
      .iterator()
      .asScala
      .map(_.toString)
      .filter(_.endsWith(".sast"))
      .toArray

  def ensureExists(dir: String): Unit =
    val path = java.nio.file.Paths.get(dir)
    if !java.nio.file.Files.exists(path) then
      java.nio.file.Files.createDirectories(path)

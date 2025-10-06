package common

import scala.collection.mutable

import java.nio.charset.StandardCharsets
/**
  * Encapsulates common input/output utilities
  *
  */
object IO:
  /**
    * Parse options according to the option specs.
    *
    * @returns matched options and remaining positional arguments
    */
  def parseOptions(args: Seq[String], options: Map[String, Boolean]):
  (Map[String, String], List[String]) =
    val rest = new mutable.ArrayBuffer[String]
    val res = mutable.Map.empty[String, String]
    val iter = args.iterator
    while iter.hasNext do
      val arg = iter.next()
      if arg(0) != '-' then
        rest += arg
      else
        options.get(arg) match
          case Some(flag) =>
            if flag then
              if iter.hasNext then
                val value = iter.next()
                if value(0) == '-' then
                  throw new Exception("The flag " + arg + " requires an argument")
                else
                  res(arg) = value
              else
                throw new Exception("The flag " + arg + " requires an argument")
            else
              res(arg) = ""

          case None => throw new Exception("Unknown flag " + arg)
    end while
    (res.toMap, rest.toList)

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

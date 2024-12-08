import scala.collection.mutable

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

  def withFile(name: String)(fn: ByteBuffer => Unit): Unit =
    val file = new java.io.File(name)
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

  def withExeFile(name: String)(fn: ByteBuffer => Unit): Unit =
    val file = new java.io.File(name)
    file.createNewFile()
    file.setExecutable(true)
    withFile(file)(fn)

  def fileContent(name: String): String =
    val path = java.nio.file.Path.of(name)
    val bytes = java.nio.file.Files.readAllBytes(path)
    new String(bytes)

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

  /**
   * Little endian byte buffer
   */
  abstract class ByteBuffer:
    def addByte(value: Byte): Unit

    def addShort(value: Int): Unit =
      val MASK: Int = 0xFF
      addByte((value & MASK).toByte)
      addByte(((value >> 8) & MASK).toByte)

    def addInt(value: Int): Unit =
      val MASK: Int = 0xFF
      addByte((value & MASK).toByte)
      addByte(((value >> 8) & MASK).toByte)
      addByte(((value >> 16) & MASK).toByte)
      addByte(((value >> 24) & MASK).toByte)

    def addBytes(bytes: Seq[Byte]): Unit =
      for byte <- bytes do addByte(byte)

    def addBytes(byte: Byte, bytes: Byte*): Unit =
      addByte(byte)
      addBytes(bytes)

    def addZeros(n: Int): Unit =
      for _ <-0 until n do addByte(0)

import scala.collection.mutable

import Assembly.Label

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


  /**
    * Represents a patch to be applied after first pass of code generation.
    *
    * The patches are needed to resolve the address of mutually recursive functions.
    *
    * For simplicity, patches should not be nested.
    */
  case class Patch(offset: Int, size: Int, values: () => List[Byte]):
    def apply(update: (Int, Byte) => Unit): Unit =
      val bytes = this.values()
      if bytes.size != this.size then
        throw new Exception("Patch size mismatch, found = " +
            bytes.size + ", expect = " + this.size)
      end if
      var i = 0
      while i < this.size do
        update(this.offset + i, bytes(i))
        i += 1


  /**
    * A byte buffer which supports labels, patches and alignment.
    */
  class PatchableBuffer(
      baseAddr: Int,
      buffer  : mutable.ArrayBuffer[Byte],
      labelMap: mutable.Map[Label, Int],
      patches : mutable.ArrayBuffer[Patch]
  ) extends ByteBuffer:
    def this(baseAddr: Int, labelMap: mutable.Map[Label, Int]) =
      this(baseAddr, new mutable.ArrayBuffer, labelMap, new mutable.ArrayBuffer)

    /** New labels defined for the current PatchableBuffer */
    private  val newLabels : mutable.ArrayBuffer[Label] = new mutable.ArrayBuffer

    def addByte(data : Byte): Unit = buffer.addOne(data)

    def addPatch(patch: Patch): Unit =
      patches.addOne(patch)
      addZeros(patch.size)

    def getPatches(): List[Patch] = patches.toList

    def align(n: Int): Unit =
      while currentAddr() % n != 0 do
        addByte(0)

    def defineLabel(label: Label) =
      newLabels += label
      labelMap(label) = currentAddr()

    def getDefinedLabels(): List[Label] = newLabels.toList

    def resolve(label: Label): Option[Int] =
      labelMap.get(label)

    def currentAddr(): Int = baseAddr + currentOffset()

    def currentOffset(): Int = size

    def size: Int = buffer.size

    /** Applying patches and return result */
    def finish(): Array[Byte] =
      for patch <- patches do
        patch.apply { (i, b) => buffer(i) = b }
      buffer.toArray
  end PatchableBuffer

  /** Helper method to deal with patches (labels that are resolved late) */
  def withPatch(label: Label, size: Int)
               (fn: (ByteBuffer, Int) => Unit)
               (using pb: PatchableBuffer): Unit =
    pb.resolve(label) match
      case Some(loc) =>
        fn(pb, loc)

      case None =>
        val buffer = new mutable.ArrayBuffer[Byte]
        val bb: ByteBuffer = (b: Byte) => buffer.addOne(b)

        val patchFn: () => List[Byte] = () =>
          val Some(loc) = pb.resolve(label): @unchecked
          fn(bb, loc)
          buffer.toList

        pb.addPatch(Patch(pb.currentOffset(), size, patchFn))

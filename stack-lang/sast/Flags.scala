package sast

import scala.collection.mutable

type Flags = Flags.Flags

object Flags:
  opaque type Flag <: Flags = Long
  opaque type Flags = Long

  private val flagNames: Array[String] = Array.fill(64)("")
  private val MAX_INDEX = 17

  private[Flags] def defineFlag(index: Byte, name: String): Flag =
    assert(index <= MAX_INDEX, s"Maximum flags reached: at most $MAX_INDEX flags")
    assert(!name.isEmpty, "Flag name cannot be empty")

    assert(flagNames(index).isEmpty, "the index " + index + " is already used")

    flagNames(index) = name
    1 << index

  /** The encoding of flags is implementation detail.
    *
    * The encoding can change, therefore no assumptions should be made about the
    * encoding except in encoding/decoding of SASTs.
    */
  def toBits(fs: Flags): Long = fs

  /** See comment above */
  def fromBits(bits: Long): Flags = bits

  def flagStrings(fs: Flags): List[String] =
    val buf = new mutable.ArrayBuffer[String]
    for i <- 0 to MAX_INDEX do
      if (fs & (1 << i)) > 0 then
        assert(!flagNames(i).isEmpty, s"flag index $i is empty")
        buf += flagNames(i)
    end for
    buf.toList

  // TODO: keep non-encoded flags at high positions to reduce encoding size

  val Fun        : Flag = defineFlag(1,  "fun")      // symbol.info is ProcType
  val Type       : Flag = defineFlag(2,  "type")
  val Class      : Flag = defineFlag(3,  "class")
  val Pattern    : Flag = defineFlag(4,  "pattern")
  val NSpace     : Flag = defineFlag(5,  "namespace")
  val Section    : Flag = defineFlag(6,  "section")
  val Method     : Flag = defineFlag(7,  "method")
  val Branch     : Flag = defineFlag(8,  "branch")   // branch name space
  val Param      : Flag = defineFlag(9, "param")    // a parameter
  val Mutable    : Flag = defineFlag(10, "mutable")  // a mutable variable
  val Context    : Flag = defineFlag(11, "context")  // context parameter or its default function
  val Field      : Flag = defineFlag(12, "field")    // an object field
  val Default    : Flag = defineFlag(13, "default")  // context parameter with default value
  val Alias      : Flag = defineFlag(14, "alias")    // an alias symbol created by import/export
  val Auto       : Flag = defineFlag(15, "auto")     // auto function or auto value
  val Synthetic  : Flag = defineFlag(16, "synthetic") // a compiler-synthesized symbol
  val Loaded     : Flag = defineFlag(17, "loaded")    // a symbol loaded from sast

  val empty   : Flags = 0

  extension (fs: Flags)
    def is(flag: Flag) = (fs & flag) > 0

    def isOneOf(flags: Flags) =
      (fs & flags) > 0

    def isAllOf(flags: Flags) =
      (fs & flags) == flags

    def |(fs2: Flags): Flags = fs | fs2

    def &(fs2: Flags): Flags = fs & fs2

    def &~(fs2: Flags): Flags = fs & (~fs2)

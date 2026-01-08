package sast

import scala.collection.mutable

type Flags = Flags.Flags

object Flags:
  opaque type Flag <: Flags = Long
  opaque type Flags = Long

  private val flagNames: Array[String] = Array.fill(64)("")
  private val MAX_INDEX = 39

  private[Flags] def defineFlag(index: Byte, name: String): Flag =
    assert(index <= MAX_INDEX, s"Maximum flags reached: at most $MAX_INDEX flags")
    assert(!name.isEmpty, "Flag name cannot be empty")

    assert(flagNames(index).isEmpty, "the index " + index + " is already used")

    flagNames(index) = name
    1.toLong << index

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
      if (fs & (1.toLong << i)) > 0 then
        assert(!flagNames(i).isEmpty, s"flag index $i is empty")
        buf += flagNames(i)
    end for
    buf.toList

  // Keep encoded flags at low positions to reduce encoding size

  val Mutable    : Flag = defineFlag(1, "mutable"   ) // a mutable variable
  val Context    : Flag = defineFlag(2, "context"   ) // context parameter
  val Default    : Flag = defineFlag(3, "default"   ) // context parameter with default value or its default function
  val Defer      : Flag = defineFlag(4, "defer"     ) // a deferred definition
  val Alias      : Flag = defineFlag(5, "alias"     ) // an alias symbol created by import or alias
  val View       : Flag = defineFlag(6, "view"      ) // an view member of a class
  val Synthetic  : Flag = defineFlag(7, "synthetic" ) // a compiler-synthesized symbol
  val Object     : Flag = defineFlag(8, "object" )    // an object class or object accessor
  val Auto       : Flag = defineFlag(9, "auto"      ) // an auto definition for auto resolution

  val Loaded     : Flag = defineFlag(30, "loaded"   ) // a symbol loaded from sast
  val Fun        : Flag = defineFlag(31, "fun"      ) // symbol.info is ProcType
  val Class      : Flag = defineFlag(32, "class"    )
  val NSpace     : Flag = defineFlag(33, "namespace")
  val Section    : Flag = defineFlag(34, "section"  )
  val Field      : Flag = defineFlag(35, "field"    ) // an object field
  val Method     : Flag = defineFlag(36, "method"   )
  val Branch     : Flag = defineFlag(37, "branch"   ) // branch name space
  val Param      : Flag = defineFlag(38, "param"    ) // a parameter
  val Interface  : Flag = defineFlag(39, "interface") // an interface


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

    def toStrings: List[String] = flagStrings(fs)

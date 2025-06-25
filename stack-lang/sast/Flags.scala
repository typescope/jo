package sast

import scala.collection.mutable

/** The encoding of flags is implementation detail.
  *
  * The encoding can change in different compiler runs, therefore no assumptions
  * should be made about the encoding.
  *
  * In contrast, flag names are stable.
  */
type Flags = Flags.Flags

object Flags:
  opaque type Flag <: Flags = Long
  opaque type Flags = Long

  private val flagNames: Array[String] = Array.fill(64)("")
  private var flagCount: Int = 0

  private[Flags] def defineFlag(name: String): Flag =
    assert(flagCount < 64, "Maximum flags reached: at most 64 flags")
    assert(!name.isEmpty, "Flag name cannot be empty")

    val index = flagCount
    assert(flagNames(index).isEmpty, "the index " + index + " is already used")

    flagNames(index) = name
    flagCount += 1
    1 << index

  def flagStrings(fs: Flags): List[String] =
    val buf = new mutable.ArrayBuffer[String]
    for i <- 0 to flagCount do
      if (fs & (1 << i)) > 0 then
        assert(!flagNames(i).isEmpty, s"flag index $i is empty")
        buf += flagNames(i)
    end for
    buf.toList

  val Fun        : Flag = defineFlag("fun")      // symbol.info is ProcType
  val Type       : Flag = defineFlag("type")
  val Class      : Flag = defineFlag("class")
  val Pattern    : Flag = defineFlag("pattern")
  val NSpace     : Flag = defineFlag("namespace")
  val Section    : Flag = defineFlag("section")
  val Method     : Flag = defineFlag("method")
  val Constructor: Flag = defineFlag("constructor")
  val Branch     : Flag = defineFlag("branch")   // branch name space
  val Param      : Flag = defineFlag("param")    // a parameter
  val Mutable    : Flag = defineFlag("mutable")  // a mutable variable
  val Context    : Flag = defineFlag("context")  // context parameter or its default function
  val Field      : Flag = defineFlag("field")    // an object field
  val Default    : Flag = defineFlag("default")  // context parameters with default value
  val Alias      : Flag = defineFlag("alias")    // an alias symbol created by import/export
  val Auto       : Flag = defineFlag("auto")     // auto function or auto value
  val Synthetic  : Flag = defineFlag("synthetic") // a compiler-synthesized symbol

  val empty   : Flags = 0

  extension (fs: Flags)
    def is(flag: Flag) = (fs & flag) > 0

    def isOneOf(flags: Flags) =
      (fs & flags) > 0

    def isAllOf(flags: Flags) =
      (fs & flags) == flags

    def |(fs2: Flags): Flags = fs | fs2

    def &(fs2: Flags): Flags = fs & fs2

    def toStrings: List[String] = flagStrings(fs)

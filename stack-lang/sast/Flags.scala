package sast

import scala.collection.mutable

type Flags = Flags.Flags

object Flags:
  opaque type Flag <: Flags = Long
  opaque type Flags = Long

  private val flagNames: Array[String] = Array.fill(64)("")
  private val MAX_INDEX = 42

  private[Flags] def defineFlag(index: Byte, name: String): Flag =
    assert(index <= MAX_INDEX, s"Maximum flags reached: at most $MAX_INDEX flags")
    assert(!name.isEmpty, "Flag name cannot be empty")

    assert(flagNames(index).isEmpty, "the index " + index + " is already used")

    flagNames(index) = name
    1.toLong << index

  /** Persisted flags have stable bit positions in the SAST format.
    * Internal flag positions are implementation details and must not be serialized.
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

  // Persisted flags: these bit positions are part of the SAST format contract.
  // Do not renumber or reuse them without a breaking SAST version change.
  // Keep new persisted flags at low positions to reduce encoding size.

  val Mutable    : Flag = defineFlag(0, "mutable"   ) // a mutable variable
  val Default    : Flag = defineFlag(1, "default"   ) // a deferred function with a default implementation
  val Defer      : Flag = defineFlag(2, "defer"     ) // a deferred function, interface method or type definition
  val Synthetic  : Flag = defineFlag(3, "synthetic" ) // a compiler-synthesized symbol
  val Object     : Flag = defineFlag(4, "object"    ) // an object class or object accessor
  val Auto       : Flag = defineFlag(5, "auto"      ) // a local auto definition for auto resolution
  val Annotation : Flag = defineFlag(6, "annotation") // an annotation definition

  val SAST_MASK: Flags = Mutable | Default | Defer | Synthetic | Object | Auto | Annotation

  // Internal flags: reconstructed by the decoder from tree kinds and symbol names.
  val Loaded     : Flag = defineFlag(30, "loaded"   ) // a symbol loaded from sast
  val Fun        : Flag = defineFlag(31, "fun"      ) // symbol.info is ProcType
  val Class      : Flag = defineFlag(32, "class"    )
  val NSpace     : Flag = defineFlag(33, "namespace")
  val Section    : Flag = defineFlag(34, "section"  )
  val Field      : Flag = defineFlag(35, "field"    ) // an object field
  val Method     : Flag = defineFlag(36, "method"   )
  val Param      : Flag = defineFlag(37, "param"    ) // a term, type or pattern parameter
  val Interface  : Flag = defineFlag(38, "interface") // an interface
  val Alias      : Flag = defineFlag(39, "alias"    ) // an alias symbol created by import or alias
  val Context    : Flag = defineFlag(40, "context"  ) // context parameter
  val Label      : Flag = defineFlag(41, "label"    ) // a compiler-internal block label
  val Constructor: Flag = defineFlag(42, "constructor") // a constructor


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

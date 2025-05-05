package sast

object Flags:
  opaque type Flag <: Flags = Long
  opaque type Flags = Long

  val Fun     : Flag = 1 << 1
  val Type    : Flag = 1 << 2
  val Pattern : Flag = 1 << 3
  val NSpace  : Flag = 1 << 4
  val Section : Flag = 1 << 5
  val Method  : Flag = 1 << 6

  // namespace flags
  val Branch  : Flag = 1 << 7  // branch name space

  // val flags
  val Param   : Flag = 1 << 8   // a parameter
  val Mutable : Flag = 1 << 9   // a mutable variable
  val Context : Flag = 1 << 10  // context parameter or its default function
  val Field   : Flag = 1 << 11  // an object field
  val Default : Flag = 1 << 12  // context parameters with default value

  val Synthetic: Flag = 1 << 62 // a compiler-synthesized symbol

  val empty   : Flags = 0

  extension (fs: Flags)
    def is(flag: Flag) = (fs & flag) > 0

    def isOneOf(flags: Flags) =
      (fs & flags) > 0

    def isAllOf(flags: Flags) =
      (fs & flags) == flags

    def |(fs2: Flags): Flags = fs | fs2

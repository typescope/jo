object Flags:
  opaque type Flag <: Flags = Long
  opaque type Flags = Long

  val Prim    : Flag = 1
  val Fun     : Flag = 1 << 1
  val Val     : Flag = 1 << 2
  val Type    : Flag = 1 << 3
  val NSpace  : Flag = 1 << 4

  // val flags
  val Param   : Flag = 1 << 5
  val Mutable : Flag = 1 << 6

  // namespace flags
  val Branch  : Flag = 1 << 5

  val empty   : Flags = 0

  extension (fs: Flags)
    def is(flag: Flag) = (fs & flag) > 0

    def isOneOf(flags: Flags) =
      (fs & flags) > 0

    def isAllOf(flags: Flags) =
      (fs & flags) == flags

    def |(fs2: Flags): Flags = fs | fs2

package pickle

/** SAST File Format Specification
  *
  * == Overview ==
  *
  * SAST (Semantic Abstract Syntax Tree) is a binary serialization format for
  * compiled Jo modules. It enables separate compilation and fast loading of
  * precompiled libraries.
  *
  * == Design Goals ==
  *
  * - **Space Efficiency**: Variable-length encoding, string interning, delta encoding
  * - **Fast Loading**: Direct deserialization without going through type checking
  * - **Separate Compilation**: Cross-module references via symbol tables
  * - **Version Safety**: Magic number and version headers
  * - **Position Preservation**: Source positions for error reporting
  *
  * == Numeric Values ==
  *
  * Numeric values are encoded using the following schemes depending on its type:
  *
  * - byte
  * - signed base-128
  * - unsigned base-128
  * - big-endian 2's complement
  * - big-endian IEEE 754 double (floating-point constants)
  *
  * Constant payloads follow a one-byte constant tag:
  * - Bool: one byte (0 or 1)
  * - Byte: one raw unsigned byte (0 to 255)
  * - Char: unsigned base-128 Unicode scalar value (surrogates excluded)
  * - Int: signed base-128, checked to fit 32 bits
  * - Long: signed base-128, bounded to 64 bits
  * - Float: eight-byte big-endian IEEE 754 double
  * - String: unsigned base-128 index into the string table
  *
  * == File Structure ==
  *
  * ```
  * [Header]
  *   Magic Number        4 bytes fixed (0x53415354 = "SAST")
  *   Major Version       1 byte
  *   Minor Version       1 byte
  *   String Table Addr   4 bytes fixed (offset from start)
  *   Name Table Addr     4 bytes fixed (offset from start)
  *
  * [Body]
  *   Source File         unsigned base-128 (index to string table)
  *   Line Count          unsigned base-128
  *   Line Lengths        Line Count x unsigned base-128
  *   Imports Length      4 bytes fixed (byte length of imports payload)
  *   Imports             count + import records
  *   Definitions         count + definition records (tag + fixed 4-byte length + payload)
  *
  * [Name Table] @ Name Table Addr
  *   Count               unsigned base-128 (number of external symbols)
  *   For each symbol:
  *     Owner Index       signed base-128 (-1 if root, else index in this table)
  *     Name Index        unsigned base-128 (index to string table)
  *     Kind              1 byte (Type=0, Pattern=1, Term=2, Container=3, Annotation=4)
  *
  * [String Table] @ String Table Addr
  *   Count               unsigned base-128 (number of strings)
  *   For each string:
  *     Length            unsigned base-128 (UTF-8 byte length)
  *     UTF-8 Bytes       variable
  * ```
  *
  * == Encoding Conventions ==
  *
  * **Signed/Unsigned Base-128 Encoding**: Variable-length encoding for integers
  *
  * - Unsigned values < 128 and signed values with magnitude < 64 use 1 byte
  * - Bytes store seven-bit groups in big-endian order; the high bit marks the final byte
  * - Signed values encode (absolute value << 1) | sign bit
  * - For signed Long, the negative-zero representation denotes Long.MinValue
  * - Bounded integers use at most ten bytes and reject overflow
  * - Used for: IDs, indices, lengths, most numeric values
  * - See common/Base128.scala for implementation
  *
  * **Fixed 4-byte Integers**: Big-endian 2's complement
  * - Used for: Magic number, table addresses, imports and definition byte lengths
  *
  * **String Encoding**: UTF-8 with length prefix
  * - Length as unsigned base-128
  * - Bytes as UTF-8 encoded string
  *
  * == Symbol References ==
  *
  * **Internal Symbols**: Defined within this file unit (excluding namespace symbols)
  *
  * - Encoded as: byte(0) + unsigned base-128 ID
  * - IDs are sequential integers, only valid within file
  * - Used for: local variables, function parameters, internal definitions
  *
  * **External Symbols**: Defined in other file units, or namespace symbols
  *
  * - Encoded as: byte(1) + unsigned base-128 index to name table
  * - Name table stores full qualified path (e.g., ["Predef", "println"])
  * - Used for: imported symbols, library references
  *
  * == Owner Information ==
  *
  * A file unit's namespace is determined by its directory relative to the library
  * root, rather than stored in the file header. The loader creates namespace
  * symbols while traversing these directories and supplies the owner to the decoder.
  * Definition owners are reconstructed from the enclosing definitions.
  *
  * External name-table entries store their owner as an index into the same table;
  * `-1` indicates a root symbol.
  *
  * == Position Encoding ==
  *
  * Positions use delta encoding for space efficiency:
  *
  * - Definition starts: absolute unsigned base-128 offsets
  * - Nested starts: signed deltas from a parent start or preceding sibling's end
  * - Leaf and symbol lengths: unsigned base-128 lengths
  * - Composite ends: signed deltas from the final child's end where applicable
  * - Most trees fit in 1 byte per delta
  *
  * Lines table uses unsigned base-128 encoding of line lengths:
  *
  * - Most lines < 128 columns = 1 byte
  * - Enables offset → (line, column) conversion
  *
  * == Type Information ==
  *
  * Types are reconstructed from trees when possible:
  *
  * - FunDef: type from signature
  * - Fallback: explicit type encoding for complex cases
  *
  * This saves space by not duplicating type information.
  *
  * == Symbol Flags ==
  *
  * Flag masks use unsigned base-128. See sast/Flags.scala for the persisted flags
  * and their bit positions, which form part of the SAST format contract.
  * Flags.SAST_MASK selects these bits at every flag encoding site.
  * Context definitions retain a flag payload; Context itself is implied by their tag.
  * Constructor is implied by a class method named "<init>".
  * Internal flags such as Fun, Class and Param are reconstructed when decoding.
  *
  * == Version Compatibility ==
  *
  * The SAST format version is independent of the Jo compiler release version.
  * Libraries built with different compiler versions can share a SAST format.
  * Incompatibility errors use a maintained table of compiler releases supporting
  * each SAST format to recommend a compatible compiler or a library rebuild.
  *
  * Major version must match exactly:
  *
  * - Different major version = incompatible format change
  * - Decoder will abort with clear error message
  *
  * Minor version is backward compatible:
  *
  * - Older minor version can be loaded by newer compiler
  * - Newer minor version is rejected before reading the body
  * - Use for: changes that preserve reading of older minor versions
  *
  * == Validation ==
  *
  * - Magic number check: Detects non-SAST files
  * - Version check: Ensures compatibility
  * - (Future: Checksums for corruption detection)
  *
  * == Future Extensions ==
  *
  * Possible format improvements (would require version bump):
  *
  * - Section checksums (CRC32 or SHA256)
  * - Signature-only section (for IDE/tooling)
  * - Dependency metadata (for build tools)
  *
  */
object Format:
  // Version information
  //
  // Format: MAJOR.MINOR
  //
  // - Increment MAJOR for breaking changes (incompatible format)
  // - Increment MINOR for backward-compatible changes
  //
  // The MAJOR version change should never happen for minor compiler releases (except pre-1.0 releases).
  // Post-1.0 releases must ensure major-version backward compatibility.
  //
  // SAST versions are independent of compiler release versions.
  final val MAJOR_VERSION: Byte = 2
  final val MINOR_VERSION: Byte = 0

  // Update this table when releasing a compiler with a new SAST format.
  private val compilerCompatibility: Map[(Int, Int), String] = Map(
    (1, 1) -> "Jo 0.13.x",
    (2, 0) -> "Jo 0.14.x and 0.15.x"
  )

  def incompatibleVersionMessage(major: Int, minor: Int): String =
    val libraryCompiler = compilerCompatibility.get((major, minor)) match
      case Some(versions) => s"Switch to $versions, which supports SAST $major.$minor.\n"
      case None => s"Switch to a compiler that supports SAST $major.$minor.\n"

    val rebuild = compilerCompatibility.get((MAJOR_VERSION.toInt, MINOR_VERSION.toInt)) match
      case Some(versions) => s"Alternatively, rebuild the library with $versions or select a compatible version of the library."
      case None => "Alternatively, rebuild the library with this compiler or select a compatible version of the library."

    s"Incompatible SAST file version: file is $major.$minor, compiler supports $MAJOR_VERSION.$MINOR_VERSION.\n" +
      libraryCompiler + rebuild

  // Magic number to identify SAST files (ASCII: "SAST")
  final val MAGIC_NUMBER: Int = 0x53415354

  //----------------------------------------------------------------------------
  // Format Tags
  //----------------------------------------------------------------------------

  final val Literal     : Byte = 1
  final val Ident       : Byte = 2
  final val Select      : Byte = 3
  final val Assign      : Byte = 4
  final val FieldAssign : Byte = 5
  final val If          : Byte = 6
  final val While       : Byte = 7
  final val Block       : Byte = 8
  final val With        : Byte = 9
  final val Allow       : Byte = 10
  final val TypeApply   : Byte = 11
  final val Apply       : Byte = 12
  final val New         : Byte = 13
  final val Lambda      : Byte = 14
  final val Encoded     : Byte = 15
  final val Match       : Byte = 16
  final val IsExpr      : Byte = 17
  final val Labeled     : Byte = 28
  final val Return      : Byte = 29

  final val ParamDef    : Byte = 19
  final val FunDef      : Byte = 20
  final val PatDef      : Byte = 21
  final val ClassDef    : Byte = 22
  final val TypeDef     : Byte = 23
  final val Section     : Byte = 24
  final val InterfaceDef: Byte = 25
  final val PatValDef   : Byte = 26

  final val BoolConst   : Byte = 1
  final val IntConst    : Byte = 2
  final val StringConst : Byte = 3
  final val FloatConst : Byte = 4
  final val ByteConst  : Byte = 5
  final val CharConst  : Byte = 6
  final val LongConst  : Byte = 7

  final val TypePattern        : Byte = 1
  final val WildcardPattern    : Byte = 2
  final val BindPattern        : Byte = 3
  final val OrPattern          : Byte = 4
  final val AndPattern         : Byte = 5
  final val ApplyPattern       : Byte = 6
  final val ValuePattern       : Byte = 7
  final val GuardPattern       : Byte = 8
  final val SeqPattern         : Byte = 9
  final val AssignPattern      : Byte = 10
  final val NotPattern         : Byte = 11

  final val AtomPattern     : Byte = 1
  final val RepeatPattern   : Byte = 2

  final val VoidType      : Byte = 1
  final val AnyType       : Byte = 2
  final val BottomType    : Byte = 3
  final val ConstantType  : Byte = 4
  final val StaticRef     : Byte = 5
  final val MemberRef     : Byte = 6
  final val UnionType     : Byte = 7
  final val LambdaType    : Byte = 8
  final val AppliedType   : Byte = 9
  final val DuckType      : Byte = 10
  final val ExtensionType : Byte = 11
  final val AnnotType     : Byte = 12

  final val Type: Byte = 0
  final val Pattern: Byte = 1
  final val Term: Byte = 2
  final val Container: Byte = 3
  final val Annotation: Byte = 4

  final val SimpleKind: Byte = 0
  final val ArrowKind: Byte = 1

  final val VisibilityDefault: Byte = 0
  final val VisibilityPrivate: Byte = 1

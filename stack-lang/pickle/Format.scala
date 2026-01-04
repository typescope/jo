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
  *
  * == File Structure ==
  *
  * ```
  * [Header]
  *   Magic Number        4 bytes fixed (0x53415354 = "SAST")
  *   Major Version       1 byte
  *   Minor Version       1 byte
  *   Owner Index         signed base-128 (-1 or index to name table)
  *   String Table Addr   4 bytes fixed (offset from start)
  *   Name Table Addr     4 bytes fixed (offset from start)
  *
  * [Body]
  *   Root Symbol ID      unsigned base-128 (internal symbol ID)
  *   Root Symbol Name    unsigned base-128 (index to string table)
  *   Source Info         (file path, lines table)
  *   Symbol Span         2x unsigned base-128 (offset, length)
  *   Imports             variable (import statements)
  *   Definitions         variable (functions, types, etc.)
  *
  * [Name Table] @ Name Table Addr
  *   Count               unsigned base-128 (number of external symbols)
  *   For each symbol:
  *     Owner Index       signed base-128 (-1 if root, else index in this table)
  *     Name Index        unsigned base-128 (index to string table)
  *     Kind              1 byte (Term=0, Type=1, Pattern=2)
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
  * - Small values (< 128) use 1 byte
  * - Larger values use multiple bytes with continuation bit
  * - Used for: IDs, indices, lengths, most numeric values
  * - See common/Base128.scala for implementation
  *
  * **Fixed 4-byte Integers**: Big-endian 2's complement
  * - Used for: Magic number, table addresses
  *
  * **String Encoding**: UTF-8 with length prefix
  * - Length as unsigned base-128
  * - Bytes as UTF-8 encoded string
  *
  * == Symbol References ==
  *
  * **Internal Symbols**: Defined within this namespace
  *
  * - Encoded as: byte(0) + unsigned base-128 ID
  * - IDs are sequential integers, only valid within file
  * - Used for: local variables, function parameters, internal definitions
  *
  * **External Symbols**: Defined in other modules
  *
  * - Encoded as: byte(1) + unsigned base-128 index to name table
  * - Name table stores full qualified path (e.g., ["Predef", "println"])
  * - Used for: imported symbols, library references
  *
  * == Owner Information ==
  *
  * The owner of a namespace is the symbol that contains it:
  * - `-1`: Top-level namespace
  * - `>= 0`: Index to name table pointing to parent namespace
  *
  * Owner symbols are created lazily during loading if they don't exist yet.
  * This allows loading modules in any order.
  *
  * == Position Encoding ==
  *
  * Positions use delta encoding for space efficiency:
  *
  * - Offset: delta from previous tree's end position
  * - Length: delta from sum of children lengths
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
  * - ValDef: type from RHS expression
  * - FunDef: type from signature
  * - Fallback: explicit type encoding for complex cases
  *
  * This saves space by not duplicating type information.
  *
  * == Version Compatibility ==
  *
  * Major version must match exactly:
  *
  * - Different major version = incompatible format change
  * - Decoder will abort with clear error message
  *
  * Minor version is backward compatible:
  *
  * - Older minor version can be loaded by newer compiler
  * - Newer minor version warns but attempts to load
  * - Use for: new optional features, extended formats
  *
  * == Validation ==
  *
  * - Magic number check: Detects non-SAST files
  * - Version check: Ensures compatibility
  * - Owner chain validation: Ensures symbol table consistency
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
  // Format: MAJOR.MINOR
  // - Increment MAJOR for breaking changes (incompatible format)
  // - Increment MINOR for backward-compatible changes
  final val MAJOR_VERSION: Byte = 1
  final val MINOR_VERSION: Byte = 0

  // Magic number to identify SAST files (ASCII: "SAST")
  final val MAGIC_NUMBER: Int = 0x53415354

  //----------------------------------------------------------------------------
  // Format Tags
  //----------------------------------------------------------------------------

  final val Literal     : Byte = 1
  final val RecordLit   : Byte = 2
  final val Ident       : Byte = 3
  final val Select      : Byte = 4
  final val Assign      : Byte = 5
  final val FieldAssign : Byte = 6
  final val If          : Byte = 7
  final val While       : Byte = 8
  final val Block       : Byte = 9
  final val With        : Byte = 10
  final val Allow       : Byte = 11
  final val TypeApply   : Byte = 12
  final val Apply       : Byte = 13
  final val New         : Byte = 14
  final val Lambda      : Byte = 15
  final val Encoded     : Byte = 16
  final val Match       : Byte = 17
  final val IsExpr      : Byte = 18

  final val ParamDef    : Byte = 19
  final val ValDef      : Byte = 20
  final val FunDef      : Byte = 21
  final val PatDef      : Byte = 22
  final val ClassDef    : Byte = 23
  final val TypeDef     : Byte = 24
  final val Section     : Byte = 25
  final val AliasDef    : Byte = 26
  final val InterfaceDef: Byte = 27
  final val CaseDef     : Byte = 28

  final val BoolConst   : Byte = 1
  final val IntConst    : Byte = 2
  final val StringConst : Byte = 3
  final val FloatConst : Byte = 4

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

  final val AtomPattern     : Byte = 1
  final val SkipToPattern   : Byte = 2
  final val RestPattern     : Byte = 3
  final val StarPattern     : Byte = 4

  final val VoidType      : Byte = 1
  final val AnyType       : Byte = 2
  final val BottomType    : Byte = 3
  final val ErrorType     : Byte = 4
  final val ConstantType  : Byte = 5
  final val StaticRef     : Byte = 6
  final val MemberRef     : Byte = 7
  final val RecordType    : Byte = 8
  final val UnionType     : Byte = 9
  final val LambdaType    : Byte = 10
  final val ProcType      : Byte = 11
  final val TypeLambda    : Byte = 12
  final val AppliedType   : Byte = 13
  final val TypeBound     : Byte = 14
  final val ContainerInfo : Byte = 15
  final val ClassInfo     : Byte = 16
  final val TypeParamRef  : Byte = 17
  final val DuckType      : Byte = 18
  final val ViewType      : Byte = 19

  final val Type: Byte = 0
  final val Pattern: Byte = 1
  final val Term: Byte = 2
  final val Container: Byte =3

  final val SimpleKind: Byte = 0
  final val ArrowKind: Byte = 1

  final val VisibilityDefault: Byte = 0
  final val VisibilityPrivate: Byte = 1

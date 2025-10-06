package pickle

object Format:
  final val Literal     : Byte = 1
  final val RecordLit   : Byte = 2
  final val TaggedLit   : Byte = 3
  final val Ident       : Byte = 4
  final val Select      : Byte = 5
  final val Assign      : Byte = 6
  final val FieldAssign : Byte = 7
  final val If          : Byte = 8
  final val While       : Byte = 9
  final val Block       : Byte = 10
  final val With        : Byte = 11
  final val Allow       : Byte = 12
  final val TypeApply   : Byte = 13
  final val Apply       : Byte = 14
  final val New         : Byte = 15
  final val Object      : Byte = 16
  final val Encoded     : Byte = 17
  final val Match       : Byte = 18

  final val ParamDef    : Byte = 19
  final val ValDef      : Byte = 20
  final val FunDef      : Byte = 21
  final val PatDef      : Byte = 22
  final val ClassDef    : Byte = 23
  final val TypeDef     : Byte = 24
  final val Section     : Byte = 25
  final val AliasDef    : Byte = 26

  final val BoolConst   : Byte = 1
  final val IntConst    : Byte = 2
  final val StringConst : Byte = 3

  final val TypePattern     : Byte = 1
  final val WildcardPattern : Byte = 2
  final val AliasPattern    : Byte = 3
  final val OrPattern       : Byte = 4
  final val ApplyPattern    : Byte = 5
  final val TagPattern      : Byte = 6
  final val ValuePattern    : Byte = 7
  final val GuardPattern    : Byte = 8
  final val BindPattern     : Byte = 9
  final val SeqPattern      : Byte = 10

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
  final val TagType       : Byte = 10
  final val ObjectType    : Byte = 11
  final val ProcType      : Byte = 12
  final val TypeLambda    : Byte = 13
  final val AppliedType   : Byte = 14
  final val TypeBound     : Byte = 15
  final val ContainerInfo : Byte = 16
  final val ClassInfo     : Byte = 17
  final val TypeParamRef  : Byte = 18

  final val Type: Byte = 0
  final val Pattern: Byte = 1
  final val Term: Byte = 2

  final val SimpleKind: Byte = 0
  final val ArrowKind: Byte = 1

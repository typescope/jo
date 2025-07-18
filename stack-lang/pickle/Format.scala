package pickle

object Format:
  final val Literal     = 1
  final val RecordLit   = 2
  final val TaggedLit   = 3
  final val Ident       = 4
  final val Select      = 5
  final val Assign      = 6
  final val FieldAssign = 7
  final val If          = 8
  final val While       = 9
  final val Block       = 10
  final val With        = 11
  final val Allow       = 12
  final val TypeApply   = 13
  final val Apply       = 14
  final val New         = 15
  final val Object      = 16
  final val Encoded     = 17
  final val Match       = 18

  final val ParamDef    = 19
  final val ValDef      = 20
  final val FunDef      = 21
  final val PatDef      = 22
  final val ClassDef    = 23
  final val TypeDef     = 24
  final val Section     = 25

  final val TypePattern     = 1
  final val WildcardPattern = 2
  final val AliasPattern    = 3
  final val OrPattern       = 4
  final val ApplyPattern    = 5
  final val TagPattern      = 6
  final val ValuePattern    = 7
  final val GuardPattern    = 8
  final val BindPattern     = 9
  final val SeqPattern      = 10

  final val AtomPattern     = 1
  final val SkipToPattern   = 2
  final val RestPattern     = 3
  final val StarPattern     = 4

  final val VoidType      = 1
  final val AnyType       = 2
  final val BottomType    = 3
  final val ErrorType     = 4
  final val ConstantType  = 5
  final val StaticRef     = 6
  final val MemberRef     = 7
  final val RecordType    = 8
  final val UnionType     = 9
  final val TagType       = 10
  final val ObjectType    = 11
  final val ProcType      = 12
  final val TypeLambda    = 13
  final val AppliedType   = 13
  final val TypeBound     = 14
  final val ContainerInfo = 15
  final val ClassInfo     = 16

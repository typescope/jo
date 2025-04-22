package sast

import Types.*

object Patterns:
  /** Whether a type pattern is valid with respect to a scrutinee type
    *
    * In a type pattern, it is incorrect to simply test that the type in the
    * pattern is a subtype of the scrutinee.
    *
    * That would allow a tag type in patterns to have more params than the
    * scrutinee, which is unsound.
    *
    * In general, tag types in patterns should have fewer parameters than the
    * scrutinee type. The same goes for record and object types.
    *
    * But the above does not mean the scrutinee type is a subtype of pattern
    * type. For union type patterns, the scrutinee type is not a subtype of the
    * union type pattern. This is a consequence of structural pattern matching.
    *
    * To make type patterns easier to reason about, we restrict that the nested
    * types should be equal while the top-level algebraic types can be
    * different.
    */
  def isValidTypePattern(patternType: Type, scrutType: Type)(using StringBuilder): Boolean =
    if patternType.isTagType then isValidTagTypePattern(patternType.asTagType, scrutType)
    else if patternType.isUnionType then isValidUnionTypePattern(patternType.asUnionType, scrutType)
    else isEqualType(patternType, scrutType)

  def isValidUnionTypePattern(patternType: UnionType, scrutType: Type)(using explain: StringBuilder): Boolean =
    patternType.branches.forall: branchType =>
      isValidTypePattern(branchType, scrutType)

  def isValidTagTypePattern(patternType: TagType, scrutType: Type)(using explain: StringBuilder): Boolean =
    if scrutType.isTagType then
      val scrutTagType = scrutType.asTagType
      if scrutTagType.tag != patternType.tag then
        explain.append("The pattern type " + patternType.show + " does not match scrutinee type " + scrutTagType.show + ". ")
        false

      else if patternType.params.size > scrutTagType.params.size then
        explain.append("The pattern type " + patternType.show + " should not have more parameters than the scrutinee type " + scrutTagType.show + ". ")
        false

      else
        patternType.params.zip(scrutTagType.params).forall: (param1, param2) =>
          isEqualType(param1.info, param2.info)

    else if scrutType.isUnionType then
      val unionType = scrutType.asUnionType
      unionType.getTagType(patternType.tag) match
        case Some(scrutTagType) => isValidTagTypePattern(patternType, scrutTagType)

        case None =>
          explain.append("The tag type " + patternType.show + " is not a branch of the scrutinee type " + unionType.show + ". ")
          false

    else
      explain.append("The tag type " + patternType.show + " does not match the scrutinee type " + scrutType.show + ". ")
      false

  def isEqualType(patternType: Type, scrutType: Type)(using explain: StringBuilder): Boolean =
    // The non-algebraic types may contain algebraic types as
    // components. Therefore, we need to enforce that the types are equal
    // instead of just being subtypes.
    if Subtyping.conforms(patternType, scrutType) && Subtyping.conforms(scrutType, patternType) then
      true
    else
      explain.append("The pattern type " + patternType.show + " is not equal to the scrutinee type " + scrutType.show + ". ")
      explain.append("Non-algebraic or nested type need to be equal to the scrutinee type. ")
      false

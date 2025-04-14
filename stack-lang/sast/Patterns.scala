package sast

import Types.*

object Patterns:
  /** Whether a type pattern is valid
    *
    * In a type pattern, it is incorrect to just test that the type in the
    * pattern is a subtype of the scrutinee.
    *
    * That would allow a tag type in patterns to have more params than the
    * scrutinee, which is unsound.
    *
    * In general, tag types in patterns should have fewer parameters than the
    * scrutinee type.
    */
  def isValidTypePattern(patternType: Type, scrutType: Type)(using StringBuilder): Boolean =
    patternType.isTagType && isValidTagTypePattern(patternType.asTagType, scrutType)
    || patternType.isUnionType && isValidUnionTypePattern(patternType.asUnionType, scrutType)
    || isValidOtherTypePattern(patternType, scrutType)

  def isValidUnionTypePattern(patternType: UnionType, scrutType: Type)(using explain: StringBuilder): Boolean =
    patternType.branches.forall: branch =>
      isValidTagTypePattern(branch.info, scrutType)

  def isValidTagTypePattern(patternType: TagType, scrutType: Type)(using explain: StringBuilder): Boolean =
    if scrutType.isTagType then
      val scrutTagType = scrutType.asTagType
      if scrutTagType.tag != patternType.tag then
        explain += "The pattern type " + patternType.show + " does not match scrutinee type " + scrutType.show
        false

      else if patternType.params.size > scrutTagType.params.size then
        explain += "The pattern type " + patternType.show + " should not have more parameters than the scrutinee type " + scrutType.show
        false

      else
        patternType.params.zip(scrutType.params).forall: (param1, param2) =>
          isValidTypePattern(param1.info, param2.info)

    else if scrutType.isUnionType then
      val unionType = scrutType.asUnionType
      unionType.getTagType(patternType.tag) match
        case Some(scrutTagType) => isValidTagTypePattern(patternType, scrutTagType)

        case None =>
          explain += "The tag type " + patternType.show + " is not a branch of the scrutinee type " + unionType.show
          false

    else
      explain += "The tag type " + patternType.show + " does not match the scrutinee type " + scrutType.show
      false

  def isValidOtherTypePattern(patternType: Type, scrutType: Type)(using explain: StringBuilder): Boolean =
    // The non-algebraic types may contain algebraic types as
    // components. Therefore, we need to enforce that the types are equal
    // instead of just being subtypes.
    if Subtyping.conforms(patternType, scrutType) && Subtyping.conforms(scrutType, patternType) then
      true
    else
      explain += "The pattern type " + patternType + " is not equal to the scrutinee type " + scrutType.show + ". "
      explain += "Non-algebraic type patterns need to be equal to the scrutinee type."
      false

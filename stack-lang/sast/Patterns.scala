package sast

import Types.*

object Patterns:
  /** Whether a type pattern is valid with respect to a scrutinee type
    *
    * In a type pattern, we need to make sure that the type arguments can be
    * determined by class identity.
    */
  def isValidTypePattern(patternType: Type, scrutType: Type)(using StringBuilder, Definitions): Boolean =
    if patternType.isClassType then isValidClassTypePattern(patternType, scrutType)
    else if patternType.isUnionType then isValidUnionTypePattern(patternType.asUnionType, scrutType)
    else Subtyping.conforms(patternType, scrutType)

  def isValidUnionTypePattern(patternType: UnionType, scrutType: Type)(using explain: StringBuilder, defn: Definitions): Boolean =
    patternType.branches.forall: branchType =>
      isValidTypePattern(branchType, scrutType)

  def isValidClassTypePattern(classType: Type, scrutType: Type)(using explain: StringBuilder, defn: Definitions): Boolean =
    val cls1 = classType.asClassInfo.classSymbol
    if scrutType.isClassType then
      val cls2 = scrutType.asClassInfo.classSymbol
      if cls1 != cls2 then
        // If class does not match, the semantics can be implemented correctly
        // without problem. Exhaustivity check will produce warnings
        true

      else
        // If class match, the arguments must match
        Subtyping.conforms(classType, scrutType)

    else if scrutType.isUnionType then
      val unionType = scrutType.asUnionType
      unionType.getClassType(cls1) match
        case Some(scrutClassType) => isValidClassTypePattern(classType, scrutClassType)

        case None =>
          explain.append("The type " + classType.show + " is not a branch of the scrutinee type " + unionType.show + ". ")
          false

    else
      explain.append("The type " + classType.show + " does not match the scrutinee type " + scrutType.show + ". ")
      false

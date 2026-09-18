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
    else if patternType.isLambdaType then isValidLambdaTypePattern(patternType, scrutType)
    else Subtyping.conforms(patternType, scrutType)

  def isValidUnionTypePattern(patternType: UnionType, scrutType: Type)(using explain: StringBuilder, defn: Definitions): Boolean =
    patternType.branches.forall: branchType =>
      isValidTypePattern(branchType, scrutType)

  def isValidClassTypePattern(classType: Type, scrutType: Type)(using explain: StringBuilder, defn: Definitions): Boolean =
    val cls1 = classType.classSymbol
    if scrutType.isClassType then
      val cls2 = scrutType.classSymbol
      if cls1 != cls2 then
        // If class does not match, the semantics can be implemented correctly
        // without problem. Exhaustivity check will produce warnings
        true

      else
        // If class match, the arguments must match
        if Subtyping.conforms(classType, scrutType) then true
        else
          explain.append("The type " + classType.show + " does not conform to the scrutinee type " + scrutType.show)
          false

    else if scrutType.isUnionType then
      val unionType = scrutType.asUnionType
      unionType.getClassType(cls1) match
        case Some(scrutClassType) => isValidClassTypePattern(classType, scrutClassType)

        case None =>
          explain.append("The type " + classType.show + " is not a branch of the scrutinee type " + unionType.show)
          false

    else
      explain.append("The type " + classType.show + " does not match the scrutinee type " + scrutType.show)
      false

  /** A lambda type pattern
    *
    * At runtime, the type test only checks whether the value is a lambda.
    * Parameter and result types cannot be checked at runtime, thus the lambda
    * branch of the scrutinee type must conform to the pattern type.
    */
  def isValidLambdaTypePattern(lambdaType: Type, scrutType: Type)(using explain: StringBuilder, defn: Definitions): Boolean =
    if Subtyping.conforms(scrutType, lambdaType) then
      true

    else if scrutType.isUnionType then
      val unionType = scrutType.asUnionType
      unionType.lambdaTypes match
        case scrutLambdaType :: Nil =>
          if Subtyping.conforms(scrutLambdaType, lambdaType) then true
          else
            explain.append("The lambda branch " + scrutLambdaType.show + " of the scrutinee type does not conform to the type " + lambdaType.show)
            false

        case _ =>
          explain.append("The type " + lambdaType.show + " is not a branch of the scrutinee type " + unionType.show)
          false

    else
      explain.append("The type " + lambdaType.show + " does not match the scrutinee type " + scrutType.show)
      false

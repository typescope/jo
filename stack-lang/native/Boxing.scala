package native

import phases.Phase

import sast.*
import sast.Trees.*
import sast.Symbols.*
import sast.Types.*

/**
  * Insert boxing/unboxing for numeric types in union types.
  *
  * This phase is only needed for native backends (and Java backend in future).
  * JavaScript backend doesn't need boxing since all values are tagged.
  *
  * Boxing is needed when:
  * - A numeric value (Byte/Char/Int/Float) is used where a union type is expected
  * - A union type value is casted to a numeric type
  *
  * This phase runs after pattern translation, so patterns should not exist.
  */
class Boxing(using defn: Definitions) extends Phase[Symbol]:
  val contextObject = Phase.OwnerContext

  override def transformDefs(defs: List[Def])(using Context): List[Def] =
    defs.map:
      case fdef: FunDef =>
        val boxer = new Boxing.BoxingTyper(fdef.symbol)
        val body2 = boxer.apply(fdef.body, fdef.symbol.info.asProcType.resultType)
        fdef.copy(body = body2)(fdef.span)

      case cdef: ClassDef =>
        val funs2 = cdef.funs.map: fdef =>
          val boxer = new Boxing.BoxingTyper(fdef.symbol)
          val body2 = boxer.apply(fdef.body, fdef.symbol.info.asProcType.resultType)
          fdef.copy(body = body2)(fdef.span)
        cdef.copy(funs = funs2)(cdef.span)

      case defn => super.transformDef(defn)

object Boxing:
  /**
    * The ReTyper subclass that inserts boxing/unboxing operations.
    *
    * Boxing is inserted when:
    * - actual type is a primitive (Byte/Char/Int/Float)
    * - expected type is a union containing that primitive
    *
    * Unboxing is inserted when:
    * - actual type is a union containing a primitive
    * - expected type is that primitive
    * - (This happens after pattern matching extracts the primitive)
    */
  class BoxingTyper(owner: Symbol)(using defn: Definitions) extends ReTyper:
    def apply(word: Word, expectedType: Type): Word =
      val word2 = recur(word, expectedType)

      if needsBoxing(word2.tpe, expectedType) then
        boxValue(word2, expectedType)

      else if needsUnboxing(word2.tpe, expectedType) then
        unboxValue(word2, expectedType)

      else
        word2

    def apply(pattern: Pattern, scrutType: Type): Pattern =
      // Patterns should not exist after pattern translation
      assert(false, s"Boxing phase should run after pattern translation, but found pattern: $pattern")
      pattern

    /** Check if boxing is needed */
    private def needsBoxing(actualType: Type, expectedType: Type): Boolean =
      // Boxing needed when actual is primitive and expected is union containing that primitive
      if !expectedType.isUnionType then
        false

      else if !defn.isNumericType(actualType) then
        false

      else
        val unionType = expectedType.asUnionType
        unionType.branches.exists(_.isSubtype(actualType))

    /** Check if unboxing is needed */
    private def needsUnboxing(actualType: Type, expectedType: Type): Boolean =
      // Unboxing needed when actual is union containing primitive and expected is that primitive
      if !actualType.isUnionType then
        false

      else if !defn.isNumericType(expectedType) then
        false

      else
        val unionType = actualType.asUnionType
        unionType.branches.exists(_.isSubtype(expectedType))

    /** Box a primitive value */
    private def boxValue(word: Word, expectedType: Type): Word =
      // TODO: Create boxed wrapper instance
      // For now, just return the word unchanged
      // This will be implemented once we have boxing runtime support
      word

    /** Unbox a union value to extract primitive */
    private def unboxValue(word: Word, expectedType: Type): Word =
      // TODO: Extract primitive from boxed wrapper
      // For now, just return the word unchanged
      // This will be implemented once we have unboxing runtime support
      word
  end BoxingTyper
end Boxing

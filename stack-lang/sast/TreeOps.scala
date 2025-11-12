package sast

import Trees.*
import Symbols.Symbol
import Flags.*
import Types.*

import ast.Positions.{Span, Source}

import scala.collection.mutable

object TreeOps:
  /** Use exception because we do not want to refer Reporter in sast package */
  class AdaptionFailure(word: Word, targetType: Type) extends Exception:
    override def toString(): String =
      "Unable to adapt " + word + " of type " + word.tpe + " to " + targetType

  /** Adapt the word to the target type.
    *
    * It makes drop of values in if/match expressions explicit.
    * It also tries to apply adapters if direct conformance fails.
    */
  def adapt(word: Word, targetType: Type, adapters: List[Symbol])(using defn: Definitions): Word =
    val unitType = defn.UnitType

    val curType = word.tpe
    if Subtyping.conforms(curType, targetType) then
      word

    else if targetType.isVoidType && curType.isValueType then
      word.dropValue

    else

      val isNumeric = defn.isNumericType(word.tpe) && defn.isNumericType(targetType)

      if isNumeric && !Subtyping.conforms(word.tpe, targetType) then
        // Numeric coercion
        word match
          case Literal(Constant.Int(n)) =>
            val tp2 = coerceIntLiteral(n, word.tpe, targetType)
            val word2 = Literal(Constant.Int(n))(tp2, word.span)
            word2

          case _ =>
            // Only widening coercion is allowed for non-literals
            coerceNumeric(word, targetType)

      else if Subtyping.conforms(unitType, targetType) then
        val unit = unitValue(word.span.endPoint)
        Block(word.ensureDropValue :: unit :: Nil)(word.span)

      else
        // Try to apply adapters before failing
        tryAdapters(word, targetType, adapters) match
          case Some(adapted) => adapted
          case None => throw new AdaptionFailure(word, targetType)

  private def coerceIntLiteral(n: Int, origType: Type, targetType: Type)(using defn: Definitions): Type =
    if
      targetType.isSubtype(defn.ByteType) && n < 128 && n >= -128
      || targetType.isSubtype(defn.CharType) && n < 65536 && n >= 0
      || targetType.isSubtype(defn.IntType)
    then
      targetType

    else
      origType

  /** Adapt the word to the target type
    *
    *     Byte ==> Int
    *     Char ==> Int
    *
    * Assumption: The tye of the word does not conform to the target type.
    */
  private def coerceNumeric(word: Word, targetType: Type)(using defn: Definitions): Word =
    def fail() = throw new AdaptionFailure(word, targetType)

    val origType = word.tpe
    if origType.isSubtype(defn.ByteType) then
      if targetType.isSubtype(defn.IntType) then
        val byteToInt = Ident(defn.Predef_byteToInt)(word.span)
        byteToInt.appliedTo(word)

      else
        fail()

    else if origType.isSubtype(defn.CharType) then
      if targetType.isSubtype(defn.IntType) then
        val charToInt = Ident(defn.Predef_charToInt)(word.span)
        charToInt.appliedTo(word)

      else
        fail()

    else
      fail()

  def tryAdapters(word: Word, targetType: Type, adapters: List[Symbol])(using defn: Definitions): Option[Word] =
    adapters match
      case Nil => None

      case adapterSym :: rest =>
        val procType = adapterSym.info.asProcType
        val adapterParamType = procType.params.head.info

        // Check if the word's type conforms to the adapter's parameter type
        if Subtyping.conforms(word.tpe, adapterParamType) then
          val adapterIdent = Ident(adapterSym)(word.span)
          val adapted = adapterIdent.appliedTo(word)

          Some(adapted)

        else
          tryAdapters(word, targetType, rest)

  /** Eta-expand a function to an object with an apply method
    *
    * Converts: f
    * To: { def apply[T1, ...](arg1: T1, ...): U = f(arg1, ...) }
    */
  def etaExpand(fun: Symbol, policy: Effects.Policy, span: Span)(using Definitions, Source): Word =
    val procType = fun.info.asProcType
    val pos = span.toPos

    // Create a "this" symbol for the object
    val thisSym = Symbol.createSymbol("this", Synthetic, pos)

    // Create an "apply" method symbol
    val applySym = Symbol.createSymbol("apply", Fun | Method | Synthetic, pos)

    // Create parameter symbols for the apply method
    val paramSyms =
      for param <- procType.params yield
        Symbol.createSymbol(param.name, param.info, Param, applySym, pos)

    // Create auto parameter symbols for the apply method
    val autoSyms =
      for auto <- procType.autos yield
        Symbol.createSymbol(auto.name, auto.info, Context, applySym, pos)

    // No preParam for methods
    val applyProcType = procType.copy(preParamCount = 0)

    // Build the object type
    val objType = ObjectType(NamedInfo("apply", applyProcType) :: Nil, mutableFields = Nil)

    // Build the body: call the original function with the parameters
    val funIdent = Ident(fun)(span)

    // Apply type arguments if polymorphic
    val funWithTargs =
      if procType.isPolyType then
        funIdent.appliedToTypes(procType.tparams.map(StaticRef.apply)*)
      else
        funIdent

    // Apply regular arguments
    val paramIdents = paramSyms.map(sym => Ident(sym)(span))
    val autoIdents = autoSyms.map(sym => Ident(sym)(span))
    val body = Apply(funWithTargs, paramIdents, autoIdents)(span)

    // Create the apply method definition - no tparams for the FunDef, they're in the ProcType
    val resultTypeTree = TypeTree(procType.resultType)(span.point)
    val adaptersIdents = procType.adapters.map(_.map(s => Ident(s)(span)))
    val funDef = FunDef(
      applySym,
      procType.tparams,
      paramSyms,
      adaptersIdents,
      autoSyms,
      resultTypeTree,
      policy,
      body
    )(span)

    // Create and return the object
    Object(thisSym, funDef :: Nil)(objType, span)

  /** Returns (locals, free) */
  def variableCensus(fdef: FunDef)(using Definitions): (List[Symbol], List[Symbol]) =
    val census = new VariableCensus
    census(fdef.body)(using ())
    val locals = census.locals.distinct.toList
    val masked = fdef.allParams ++ locals
    val free = census.free.filter(sym => !masked.contains(sym)).distinct.toList
    (locals.filter(_.info.isValueType), free)

  class VariableCensus(using Definitions) extends TreeTraverser:
    val locals = new mutable.ArrayBuffer[Symbol]
    val free = new mutable.ArrayBuffer[Symbol]

    type Context = Unit

    override def apply(pat: Pattern)(using Context): Unit =
      pat match
        case AliasPattern(id, nested) =>
          locals += id.symbol
          this(nested)

        case SeqPattern(pats) =>
          pats.foreach:
            case AtomPattern(pattern) => this(pattern)

            case SkipToPattern(pattern) => this(pattern)

            case RestPattern(pattern) => this(pattern)

            case star @ StarPattern(pattern) =>
              locals ++= star.bindings.map(_._1)
              this(pattern)

        case _ =>
          recur(pat)

    def apply(word: Word)(using Context): Unit =
      word match
        case Ident(sym) =>
          // can be a global name
          free += sym

        case ValDef(sym, rhs) =>
          if !sym.isField then locals += sym
          this(rhs)

        case Assign(Ident(sym), rhs) =>
          locals += sym
          this(rhs)

        case obj: Object =>
          locals += obj.self
          recur(obj)

        case fdef: FunDef =>
          locals += fdef.symbol
          free ++= fdef.freeVariables

        case _ => recur(word)

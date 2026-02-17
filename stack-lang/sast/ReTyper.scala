package sast

import Trees.*
import Types.Type

import scala.annotation.unused

/** A tree transformer that threads expected types through the traversal.
  *
  * Subclasses override `apply` to transform nodes based on expected vs actual type.
  * The `recur` method handles tree traversal and avoids creating new trees when
  * nothing changes (using reference equality checks like TreeMap).
  */
abstract class ReTyper(using defn: Definitions):

  /** Transform a word based on its expected type. */
  def apply(word: Word, expectedType: Type): Word

  /** Transform a pattern based on scrutinee type. */
  def apply(pattern: Pattern, scrutType: Type): Pattern

  /** Recursively transform a word, threading expected types to children. */
  final def recur(word: Word, expectedType: Type): Word =
    word match
      case lit: Literal => recurLiteral(lit, expectedType)
      case ident: Ident => recurIdent(ident, expectedType)
      case select: Select => recurSelect(select, expectedType)
      case rc: RecordLit => recurRecord(rc, expectedType)
      case encoding: Encoded => recurEncoded(encoding, expectedType)
      case appl: Apply => recurApply(appl, expectedType)
      case newExpr: New => recurNew(newExpr, expectedType)
      case tapply: TypeApply => recurTypeApply(tapply, expectedType)
      case withExpr: With => recurWith(withExpr, expectedType)
      case allowExpr: Allow => recurAllow(allowExpr, expectedType)
      case assign: Assign => recurAssign(assign, expectedType)
      case fieldAssign: FieldAssign => recurFieldAssign(fieldAssign, expectedType)
      case vdef: ValDef => recurValDef(vdef, expectedType)
      case fdef: FunDef => recurFunDef(fdef, expectedType)
      case pdef: PatDef => recurPatDef(pdef, expectedType)
      case tdef: TypeDef => recurTypeDef(tdef, expectedType)
      case ifElse: If => recurIf(ifElse, expectedType)
      case whileDo: While => recurWhile(whileDo, expectedType)
      case isExpr: IsExpr => recurIsExpr(isExpr, expectedType)
      case classTest: ClassTest => recurClassTest(classTest, expectedType)
      case block: Block => recurBlock(block, expectedType)
      case patmat: Match => recurMatch(patmat, expectedType)
      case patValDef: PatValDef => recurPatValDef(patValDef, expectedType)
      case lambda: Lambda => recurLambda(lambda, expectedType)

  /** Recursively transform a pattern, threading scrutinee types to children. */
  final def recur(pattern: Pattern, scrutType: Type): Pattern =
    pattern match
      case pat: BindPattern => recurBindPattern(pat, scrutType)
      case pat: TypePattern => recurTypePattern(pat, scrutType)
      case pat: ApplyPattern => recurApplyPattern(pat, scrutType)
      case pat: OrPattern => recurOrPattern(pat, scrutType)
      case pat: AndPattern => recurAndPattern(pat, scrutType)
      case pat: NotPattern => recurNotPattern(pat, scrutType)
      case pat: ValuePattern => recurValuePattern(pat, scrutType)
      case pat: GuardPattern => recurGuardPattern(pat, scrutType)
      case pat: AssignPattern => recurAssignPattern(pat, scrutType)
      case pat: WildcardPattern => recurWildcardPattern(pat, scrutType)
      case pat: SeqPattern => recurSeqPattern(pat, scrutType)

  // Leaf nodes - no children to transform
  private def recurLiteral(lit: Literal, @unused expectedType: Type): Word = lit

  private def recurIdent(ident: Ident, @unused expectedType: Type): Word = ident

  private def recurNew(newExpr: New, @unused expectedType: Type): Word = newExpr

  // Nodes with children - recurse and return
  private def recurSelect(select: Select, @unused expectedType: Type): Word =
    val Select(qual, name) = select
    val qual2 = recur(qual, qual.tpe)

    if qual2 `eq` qual then select
    else Select(qual2, name)(select.span)

  private def recurRecord(rc: RecordLit, @unused expectedType: Type): Word =
    val RecordLit(fields) = rc
    var changed = false

    val fields2 = fields.map:
      case field @ (f, rhs) =>
        // Try to extract field type from expected RecordType
        val fieldExpectedType =
          if expectedType.isRecordType then
            expectedType.asRecordType.getFieldType(f).getOrElse(rhs.tpe)
          else
            rhs.tpe
        val rhs2 = recur(rhs, fieldExpectedType)
        if rhs `eq` rhs2 then
          field
        else
          changed = true
          f -> rhs2

    if changed then RecordLit(fields2)(rc.span)
    else rc

  private def recurEncoded(encoding: Encoded, @unused expectedType: Type): Word =
    val repr = encoding.repr
    val repr2 = recur(repr, encoding.tpe)

    if repr2 `eq` repr then encoding
    else Encoded(repr2)(encoding.tpe)

  private def recurApply(appl: Apply, @unused expectedType: Type): Word =
    val Apply(fun, args, autos) = appl

    val fun2 = recur(fun, fun.tpe)

    // Extract parameter types from function type
    val paramTypes = funParamTypes(fun.tpe, args.length)

    var changed = fun2 `ne` fun

    val args2 = args.zip(paramTypes).map: (arg, paramType) =>
      val arg2 = recur(arg, paramType)
      changed ||= arg2 `ne` arg
      arg2

    val autos2 = autos.map: auto =>
      val auto2 = recur(auto, auto.tpe)
      changed ||= auto2 `ne` auto
      auto2

    if changed then Apply(fun2, args2, autos2)(appl.span, appl.isPartialApply)
    else appl

  private def recurTypeApply(tapply: TypeApply, @unused expectedType: Type): Word =
    val TypeApply(fun, targs) = tapply
    val fun2 = recur(fun, fun.tpe)

    if fun2 `eq` fun then tapply
    else TypeApply(fun2, targs)(tapply.span)

  private def recurWith(withExpr: With, @unused expectedType: Type): Word =
    val With(expr, args) = withExpr
    val expr2 = recur(expr, expr.tpe)

    var changed = expr2 `ne` expr

    val args2 = args.map: arg =>
      val rhsExpectedType = arg.ident.tpe.widenTermRef
      val rhs2 = recur(arg.rhs, rhsExpectedType)
      if rhs2 `eq` arg.rhs then
        arg
      else
        changed = true
        arg.copy(rhs = rhs2)

    if changed then With(expr2, args2)
    else withExpr

  private def recurAllow(allowExpr: Allow, @unused expectedType: Type): Word =
    val Allow(expr, params) = allowExpr
    val expr2 = recur(expr, expr.tpe)

    if expr2 `eq` expr then allowExpr
    else Allow(expr2, params)

  private def recurAssign(assign: Assign, @unused expectedType: Type): Word =
    val Assign(ident, rhs) = assign
    val rhsExpectedType = ident.tpe.widenTermRef
    val rhs2 = recur(rhs, rhsExpectedType)

    if rhs2 `eq` rhs then assign
    else Assign(ident, rhs2)

  private def recurFieldAssign(fieldAssign: FieldAssign, @unused expectedType: Type): Word =
    val FieldAssign(lhs, rhs) = fieldAssign

    val lhs2 = recur(lhs, lhs.tpe).asInstanceOf[Select]
    val rhsExpectedType = lhs.tpe.widenTermRef
    val rhs2 = recur(rhs, rhsExpectedType)

    if lhs2.eq(lhs) && rhs2.eq(rhs) then fieldAssign
    else FieldAssign(lhs2, rhs2)

  private def recurValDef(vdef: ValDef, @unused expectedType: Type): Word =
    val rhsExpectedType = vdef.symbol.info
    val rhs2 = recur(vdef.rhs, rhsExpectedType)

    if rhs2 `eq` vdef.rhs then vdef
    else ValDef(vdef.symbol, rhs2)(vdef.span)

  private def recurFunDef(fdef: FunDef, @unused expectedType: Type): Word =
    val bodyExpectedType = fdef.symbol.info.asProcType.resultType
    val body2 = recur(fdef.body, bodyExpectedType)

    if body2 `eq` fdef.body then fdef
    else fdef.copy(body = body2)(fdef.span)

  private def recurPatDef(pdef: PatDef, @unused expectedType: Type): Word =
    val bodyExpectedType = pdef.symbol.info.asProcType.resultType
    val body2 = recur(pdef.body, bodyExpectedType)

    if body2 `eq` pdef.body then pdef
    else pdef.copy(body = body2)(pdef.span)

  private def recurTypeDef(tdef: TypeDef, @unused expectedType: Type): Word = tdef

  private def recurIf(ifElse: If, @unused expectedType: Type): Word =
    val If(cond, thenp, elsep) = ifElse

    val cond2 = recur(cond, defn.BoolType)
    val branchExpectedType = ifElse.tpe
    val thenp2 = recur(thenp, branchExpectedType)
    val elsep2 = recur(elsep, branchExpectedType)

    if cond2.eq(cond) && thenp2.eq(thenp) && elsep2.eq(elsep) then ifElse
    else If(cond2, thenp2, elsep2)(ifElse.tpe, ifElse.span)

  private def recurWhile(whileDo: While, @unused expectedType: Type): Word =
    val While(cond, body) = whileDo

    val cond2 = recur(cond, defn.BoolType)
    val body2 = recur(body, defn.UnitType)

    if cond2.eq(cond) && body2.eq(body) then whileDo
    else While(cond2, body2)(whileDo.span)

  private def recurIsExpr(isExpr: IsExpr, @unused expectedType: Type): Word =
    val IsExpr(scrutinee, pattern) = isExpr

    val scrutinee2 = recur(scrutinee, scrutinee.tpe)
    val scrutineeType = scrutinee.tpe.widenTermRef
    val pattern2 = recur(pattern, scrutineeType)

    if scrutinee2.eq(scrutinee) && pattern2.eq(pattern) then isExpr
    else IsExpr(scrutinee2, pattern2)

  private def recurClassTest(classTest: ClassTest, @unused expectedType: Type): Word =
    val ClassTest(value, cls) = classTest

    val value2 = recur(value, value.tpe)

    if value2.eq(value) then classTest
    else ClassTest(value2, cls)(classTest.span)

  private def recurMatch(patmat: Match, @unused expectedType: Type): Word =
    val Match(scrutinee, cases) = patmat

    val scrutinee2 = recur(scrutinee, scrutinee.tpe)
    val scrutineeType = scrutinee.tpe.widenTermRef
    val caseExpectedType = patmat.tpe

    var changed = scrutinee2 `ne` scrutinee

    val cases2 = cases.map: branch =>
      val pattern2 = recur(branch.pattern, scrutineeType)
      val body2 = recur(branch.body, caseExpectedType)
      if pattern2.eq(branch.pattern) && body2.eq(branch.body) then
        branch
      else
        changed = true
        branch.copy(pattern2, body2)(branch.span)

    if changed then Match(scrutinee2, cases2)(patmat.tpe, patmat.span)
    else patmat

  private def recurPatValDef(patValDef: PatValDef, @unused expectedType: Type): Word =
    val PatValDef(pattern, rhs) = patValDef

    val scrutineeType = pattern.scrutineeType.widenTermRef
    val pattern2 = recur(pattern, scrutineeType)
    // PatValDef is a statement (VoidType), but rhs may have meaningful type
    val rhsExpectedType = if expectedType.isVoidType then rhs.tpe else expectedType
    val rhs2 = recur(rhs, rhsExpectedType)

    if pattern2.eq(pattern) && rhs2.eq(rhs) then patValDef
    else PatValDef(pattern2, rhs2)(patValDef.span)

  private def recurBlock(block: Block, @unused expectedType: Type): Word =
    val Block(words) = block

    var changed = false
    val words2 = words.zipWithIndex.map: (word, idx) =>
      // Last word in block has the block's expected type
      val wordExpectedType =
        if idx == words.length - 1 then expectedType
        else word.tpe
      val word2 = recur(word, wordExpectedType)
      changed ||= word2 `ne` word
      word2

    if changed then Block(words2)(block.span)
    else block

  private def recurLambda(lambda: Lambda, @unused expectedType: Type): Word =
    val Lambda(symbol, params, receives, body) = lambda

    val bodyExpectedType = symbol.info.asProcType.resultType
    val body2 = recur(body, bodyExpectedType)

    if body2 `ne` body then Lambda(symbol, params, receives, body2)(lambda.span)
    else lambda

  // Pattern recursion methods
  private def recurBindPattern(pat: BindPattern, @unused scrutType: Type): Pattern =
    val BindPattern(id, nested) = pat
    val nested2 = recur(nested, scrutType)

    if nested2 `eq` nested then pat
    else BindPattern(id, nested2)(pat.isDefinition)

  private def recurTypePattern(pat: TypePattern, @unused scrutType: Type): Pattern = pat

  private def recurApplyPattern(pat: ApplyPattern, @unused scrutType: Type): Pattern =
    val ApplyPattern(fun, nested) = pat

    var changed = false
    val nested2 = nested.map: patNested =>
      val patNested2 = recur(patNested, patNested.scrutineeType)
      changed ||= patNested2 `ne` patNested
      patNested2

    if changed then ApplyPattern(fun, nested2)(pat.scrutineeType, pat.span)
    else pat

  private def recurOrPattern(pat: OrPattern, scrutType: Type): Pattern =
    val OrPattern(lhs, rhs) = pat

    val lhs2 = recur(lhs, scrutType)
    val rhs2 = recur(rhs, scrutType)

    if lhs2.eq(lhs) && rhs2.eq(rhs) then pat
    else OrPattern(lhs2, rhs2)(pat.valueType)

  private def recurAndPattern(pat: AndPattern, scrutType: Type): Pattern =
    val AndPattern(lhs, rhs) = pat

    val lhs2 = recur(lhs, scrutType)
    val rhs2 = recur(rhs, scrutType)

    if lhs2.eq(lhs) && rhs2.eq(rhs) then pat
    else AndPattern(lhs2, rhs2)(pat.valueType)

  private def recurNotPattern(pat: NotPattern, scrutType: Type): Pattern =
    val NotPattern(nested) = pat

    val nested2 = recur(nested, scrutType)

    if nested2.eq(nested) then pat
    else NotPattern(nested2)(pat.span)

  private def recurValuePattern(pat: ValuePattern, @unused scrutType: Type): Pattern =
    val value2 = recur(pat.value, pat.value.tpe)

    if value2 `eq` pat.value then pat
    else ValuePattern(value2)(pat.scrutineeType)

  private def recurGuardPattern(pat: GuardPattern, @unused scrutType: Type): Pattern =
    val GuardPattern(guard) = pat

    val guard2 = recur(guard, defn.BoolType)

    if guard2.eq(guard) then pat
    else GuardPattern(guard2)(pat.scrutineeType)

  private def recurAssignPattern(pat: AssignPattern, @unused scrutType: Type): Pattern =
    val AssignPattern(assignments) = pat

    var changed = false
    val assignments2 = assignments.map: assign =>
      val assign2 = recur(assign, assign.tpe).asInstanceOf[Assign]
      if assign2 ne assign then changed = true
      assign2

    if !changed then pat
    else AssignPattern(assignments2)(pat.scrutineeType)

  private def recurSeqPattern(pat: SeqPattern, @unused scrutType: Type): Pattern =
    val SeqPattern(patterns) = pat

    var changed = false
    val patterns2 = patterns.map: regPat =>
      regPat match
        case AtomPattern(pattern) =>
          val pattern2 = recur(pattern, pattern.scrutineeType)
          if pattern2 `eq` pattern then
            regPat
          else
            changed = true
            AtomPattern(pattern2)

        case RepeatPattern(bind, guard) =>
          val guard2 = guard.map(g => recur(g, g.scrutineeType))
          if guard2 == guard then
            regPat
          else
            changed = true
            RepeatPattern(bind, guard2)(regPat.span)

    if changed then SeqPattern(patterns2)(pat.scrutineeType, pat.span)
    else pat

  private def recurWildcardPattern(pat: WildcardPattern, @unused scrutType: Type): Pattern = pat

  /** Extract parameter types from a function type. */
  private def funParamTypes(funType: Type, numArgs: Int): List[Type] =
    if funType.isProcType then
      val procType = funType.asProcType
      procType.paramTypes.take(numArgs)
    else
      List.fill(numArgs)(Types.AnyType)

end ReTyper

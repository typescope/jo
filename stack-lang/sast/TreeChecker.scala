package sast

import Trees.*
import Symbols.*

import ast.Positions.Source
import reporting.Reporter

/** Check invariants of SAST */
object TreeChecker:
  case class CheckerContext(enclosingFun: Symbol)

  def check(units: List[FileUnit])(using Definitions, Reporter): List[FileUnit] =
    for unit <- units do
      given Source = unit.source
      checkDefs(unit.defs)
    end for
    units

  def checkDefs(defs: List[Def])(using Definitions, Reporter, Source): Unit =
    for
      defn <- defs
    do
      defn match
        case fdef: FunDef =>
          given CheckerContext = new CheckerContext(fdef.symbol)
          new TreeChecker().recur(fdef.body)

          val undefined = fdef.freeVariables.filter(_.isLocal)
          if undefined.nonEmpty then
            Reporter.error("Undefined local variable(s) = " + undefined, fdef.pos)

        case pdef: PatDef =>
          given CheckerContext = new CheckerContext(pdef.symbol)
          new TreeChecker().recur(pdef.body)

        case section: Section =>
          checkDefs(section.defs)

        case _ =>
    end for


class TreeChecker()(using defn: Definitions, rp: Reporter, so: Source) extends TreeTraverser:
  type Context = TreeChecker.CheckerContext

  override def recurLocalFunDef(fdef: FunDef)(using Context): Unit =
    given Context = new TreeChecker.CheckerContext(fdef.symbol)
    this(fdef.body)

  override def recurLocalPatDef(pdef: PatDef)(using Context): Unit =
    given Context = new TreeChecker.CheckerContext(pdef.symbol)
    this(pdef.body)

  override def apply(pattern: Pattern)(using ctx: Context): Unit =
    pattern match
      case ApplyPattern(fun, nested) =>
        if fun.refers(defn.orPattern) then
          Reporter.error("Unexpected use of `|` in S-AST, tree = " + pattern.show, fun.pos)

        if fun.refers(defn.andPattern) then
          Reporter.error("Unexpected use of `&` in S-AST, tree = " + pattern.show, fun.pos)

        if fun.refers(defn.notPattern) then
          Reporter.error("Unexpected use of `!` in S-AST, tree = " + pattern.show, fun.pos)

      case _ =>

    recur(pattern)

  def apply(word: Word)(using ctx: Context): Unit =

    word match
      case Ident(sym) =>
        // TODO: change flags of lifted methods
        if sym.isOneOf(Flags.NSpace | Flags.Method | Flags.Field) || sym.isType then
          Reporter.error("A term Ident tree should not be namespace, method, field or type, id = " + word, word.pos)

        if !sym.owner.isFunction && !sym.owner.isContainer && sym.name != "this" then
          Reporter.error("The owner of an ident should be either a function, a class or an container, found = " + sym.owner, word.pos)

        // TODO: enable after fixing owners of pattern translation & lifting
        // if sym.isLocal && sym.owner != ctx.enclosingFun && !ctx.enclosingFun.ownersIterator.exists(_ == sym.owner) then
        //   Reporter.error("The owner of a local ident should be in the nested owner chain", word.pos)

      case Select(qual, name) =>
        if !qual.tpe.isValueType then
          Reporter.error("Qualifier should be a value type, found = " + qual.tpe.show, word.pos)

        else if !qual.tpe.hasTermMember(name) then
          Reporter.error(s"Qualifier does not have member $name, found = " + qual.tpe.show, word.pos)

        else if !Subtyping.conforms(qual.tpe.termMember(name).widen, word.tpe.widen) then
          val memberType = qual.tpe.termMember(name)
          Reporter.error(s"Member type ${memberType.widen} is not a subtype of ${word.tpe.widen}", word.pos)

      case _: RecordLit =>
        if !word.tpe.isRecordType then
          Reporter.error("Expect record type, found = " + word.tpe.show, word.pos)

      case If(cond, thenp, elsep) =>
        if !Subtyping.conforms(thenp.tpe, word.tpe) then
          Reporter.error(s"Branch type ${thenp.tpe.show} is not a subtype of ${word.tpe.show}", thenp.pos)

        if !Subtyping.conforms(elsep.tpe, word.tpe) then
          Reporter.error(s"Branch type ${elsep.tpe.show} is not a subtype of ${word.tpe.show}", elsep.pos)

      case Labeled(label, resultType, body) =>
        if !Subtyping.conforms(body.tpe, resultType) && !body.tpe.isBottom(using defn) then
          Reporter.error(s"Labeled body type ${body.tpe.show} is not a subtype of ${resultType.show}", body.pos)

      case Return(label, value) =>
        // Return is typed as Bottom; checker only validates payload traversal here.

      case FieldAssign(lhs @ Select(qual, name), rhs) =>
        if !qual.tpe.isClassInfoType then
          Reporter.error("Object type expected, found = " + qual.tpe.show, word.pos)

        else
          // The constructor initializes immutable fields
          //
          // if qual.tpe.isClassType && !qual.tpe.asClassInfo.field(name).isMutable
          // then
          //   Reporter.error(s"Field $name is not mutable", word.pos)

          if !Subtyping.conforms(rhs.tpe, lhs.tpe.widenTermRef) then
            Reporter.error(s"Rhs has the type ${rhs.tpe.show}, which is not a subtype of ${lhs.show}", word.pos)

      case Assign(ident, rhs) =>
        // After type checking, a ValDef becomes Assign.
        // Pattern translation uses Assign directly for pattern bound variables.
        if !Subtyping.conforms(rhs.tpe, ident.symbol.info) then
          Reporter.error(s"Rhs has the type ${rhs.tpe.show}, which is not a subtype of ${ident.symbol.info.show}", word.pos)

      case Apply(fun, args, autos) =>
        fun.tpe.asInvokableType match
          case funType =>
            val expectArgSize = funType.paramTypes.size
            if expectArgSize != args.size then
              Reporter.error(s"args do not match, expect = $expectArgSize, found = " + args.size + ", tree = " + word.show, word.pos)

            for (paramType, arg) <- funType.paramTypes.zip(args) do
              if !Subtyping.conforms(arg.tpe, paramType) then
                Reporter.error("Found arg type = " + arg.tpe.show + ", paramType = " + paramType.show + ", tree = " + word.show, word.pos)

            for (autoType, auto) <- funType.autoTypes.zip(autos) do
              if !Subtyping.conforms(auto.tpe, autoType) then
                Reporter.error("Found auto type = " + auto.tpe.show + ", autoType = " + autoType.show + ", tree = " + word.show, word.pos)

            if !Subtyping.conforms(funType.resultType, word.tpe) then
              Reporter.error("word.tpe = " + word.tpe.show + ", result type = " + funType.resultType + " tree = " + word.show, word.pos)
        end match

        checkFunShape(fun)

      case TypeApply(fun, targs) =>
        if !fun.tpe.isPolyType then
          Reporter.error(s"TypeApply expects polymorphic function type, found = ${fun.tpe.show}", fun.pos)
        else
          val procType = fun.tpe.asProcType
          val n = targs.size
          val pre = procType.preTypeParamCount
          val full = procType.tparamCount

          if n != pre && n != full then
            Reporter.error(s"TypeApply expects $pre (prefix) or $full type args, found = $n", word.pos)

          // Strong invariant: partial TypeApply is temporary and must have been
          // eliminated by smartApply before TreeChecker.
          if n == pre && n != full then
            Reporter.error("Partial TypeApply should have been eliminated before TreeChecker", fun.pos)

      case _ =>
    end match

    recur(word)

  def checkFunShape(fun: Word)(using Reporter): Unit =
    fun.strip match
      case Ident(sym) =>
        if !sym.isFunction && !sym.info.isLambdaType then
          Reporter.error("Expect function, found = " + sym, fun.pos)

      case Select(qual, _) =>
        if !qual.tpe.isClassInfoType && !qual.tpe.isRecordType then
          Reporter.error("Expect object type, found = " + qual.tpe.show, qual.pos)

      case TypeApply(fun, _) => checkFunShape(fun)

      case Apply(Ident(sym), _, _)
          if sym.fullName == "native.readInt"
             || sym.fullName == "native.findInterfaceMethod"
          =>

      case _ =>
        fun match
          case funRaw if funRaw.tpe.isLambdaType =>

          case _ =>
            Reporter.error("Expect function to be select/ident/tapply, found = " + fun, fun.pos)

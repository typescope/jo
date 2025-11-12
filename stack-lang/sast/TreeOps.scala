package sast

import Trees.*
import Symbols.Symbol
import Flags.*
import Types.*

import ast.Positions.{Span, Source}

import scala.collection.mutable

object TreeOps:
  /** Eta-expand a function to an object with an apply method
    *
    * Converts: f
    * To: { def apply[T1, ...](arg1: T1, ...): U = f(arg1, ...) }
    */
  def etaExpand(fun: Symbol, owner: Symbol, policy: Effects.Policy, span: Span)(using defn: Definitions, source: Source): Word =
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

    defn.add(thisSym, owner, objType)
    defn.add(applySym, thisSym, applyProcType)

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

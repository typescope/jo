import Sast.*
import Types.*
import Symbols.*

import scala.collection.mutable

/** The compiler phase that makes initialization explicit.
  *
  * - Wrap all val defintions in a <init> function with the main phrase.
  * - Rewrite val definitions to assign statement.
  * - Augment function definitions with a list of local symbols.
  * - Remove type definitions.
  */
class ExplicitInit(using Reporter):
  val treeMap = new ExplicitInit.LocalsTreeMap

  def transform(prog: Prog): Prog =
    val initNamesInfo = new ExplicitInit.NamesInfo
    val defs = new mutable.ArrayBuffer[Def]
    val inits = new mutable.ArrayBuffer[Word]

    for defn <- prog.defs do
      defn match
        case funDef: FunDef  =>
          defs += treeMap.transform(funDef)

        case vdef: ValDef =>
          val rhs = treeMap.apply(vdef.rhs)(using initNamesInfo)
          inits += Assign(vdef.symbol, rhs)(vdef.span)

          val empty = Phrase(words = Nil)(rhs.tpe, rhs.span)
          defs += ValDef(vdef.symbol, empty)(vdef.span)

        case tdef: TypeDef =>
    end for

    inits += treeMap.apply(prog.main)(using initNamesInfo)

    // synthesize init function
    val initType = ProcType(params = Nil, resultType = VoidType, preParamCount = 0)
    val initSym = Symbol.createFunSymbol("_init", initType, prog.main.pos)
    val initSpan = prog.main.span
    val initBody = Phrase(inits.toList)(prog.main.tpe, initSpan)

    val initLocals = initNamesInfo.locals.distinct.toList
    val initCaptures =
      initNamesInfo.free.filter(!initLocals.contains(_)).distinct.toList

    val initFun = FunDef(
      initSym, tparams = Nil, params = Nil, initBody)(
      initLocals.filter(_.isValue).toList, initCaptures, initSpan
    )

    defs += initFun
    Prog(defs.toList, Apply(Ident(initSym)(initSpan), Nil)(VoidType, initSpan))

object ExplicitInit:
  class NamesInfo:
    val locals = new mutable.ArrayBuffer[Symbol]
    val free = new mutable.ArrayBuffer[Symbol]

  class LocalsTreeMap extends SastOps.TreeMap:
    type Context = NamesInfo

    def transform(fdef: FunDef): FunDef =
      given info: NamesInfo = new NamesInfo
      val body = this(fdef.body)
      val locals = info.locals.distinct.toList
      val masked = fdef.params ++ locals
      val free = info.free.filter(sym => !masked.contains(sym)).distinct.toList
      fdef.copy(body = body)(locals.filter(_.isValue), free, fdef.span)

    def apply(word: Word)(using info: Context): Word =
      word match
        case Ident(sym) =>
          info.free += sym
          word

        case ValDef(sym, rhs) =>
          info.locals += sym
          Assign(sym, this(rhs))(word.span)

        case fdef: FunDef =>
          info.locals += fdef.symbol
          transform(fdef)

        case Phrase(words) =>
          val words2 =
            for
              word <- words if !word.isInstanceOf[TypeDef]
            yield
              this(word)
          Phrase(words2)(word.tpe, word.span)

        case _ => recur(word)

import Sast.*
import Types.*
import Symbols.*

import scala.collection.mutable

/** The compiler phase that make initialization explicit.
  *
  * - Wrap all val defintions in a <init> function with the main phrase.
  * - Rewrite val definitions to assign statement.
  * - Augment function definitions with a list of local symbols.
  * - Remove type definitions.
  */
object ExplicitInit:
  def transform(prog: Prog): Prog =
    val initLocals = new mutable.ArrayBuffer[Symbol]
    val defs = new mutable.ArrayBuffer[Def]
    val inits = new mutable.ArrayBuffer[Word]

    for defn <- prog.defs do
      defn match
        case funDef: FunDef  =>
          defs += transform(funDef)

        case vdef: ValDef =>
          val rhs = transform(vdef.rhs)(using initLocals)
          inits += Assign(vdef.symbol, rhs)(vdef.pos)

          val empty = Phrase(words = Nil)(rhs.tpe, rhs.pos)
          defs += ValDef(vdef.symbol, empty)(vdef.pos)

        case tdef: TypeDef =>
    end for

    inits += transform(prog.main)(using initLocals)

    // synthesize init function
    val initType = ProcType(names = Nil, paramTypes = Nil, resultType = VoidType)
    val initSym = Symbol.createFunSymbol("<init>", initType)
    val initPos = prog.main.pos
    val initBody = Phrase(inits.toList)(prog.main.tpe, initPos)
    val initTypeParams = Nil
    val initParams = Nil
    val initFun = FunDef(initSym, initTypeParams, initParams, initLocals.toList, initBody)(initPos)

    defs += initFun
    Prog(defs.toList, Ident(initSym)(initPos))

  type LocalsInfo = mutable.ArrayBuffer[Symbol]

  def transform(fun: FunDef): FunDef =
    val locals = new mutable.ArrayBuffer[Symbol]
    val body = transform(fun.body)(using locals)
    fun.copy(locals = locals.toList, body = body)(fun.pos)

  def transform(word: Word)(using info: LocalsInfo): Word =
    word match
      case _: IntLit | _: BoolLit | _: Ident | _: FunRef =>
        word

      case Select(qual, name) =>
        Select(transform(qual), name)(word.tpe, word.pos)

      case RecordLit(fields) =>
        val fields2 = fields.map:
          case (f, rhs) => f -> transform(rhs)

        RecordLit(fields2)(word.tpe, word.pos)

      case Encoded(repr) =>
        Encoded(transform(repr))(word.tpe)

      case Assign(sym, rhs) =>
        Assign(sym, transform(rhs))(word.pos)

      case ValDef(sym, rhs) =>
        info += sym
        Assign(sym, transform(rhs))(word.pos)

      case fdef : FunDef =>
        transform(fdef)

      case If(cond, thenp, elsep) =>
        If(transform(cond), transform(thenp), transform(elsep))(word.tpe, word.pos)

      case While(cond, body) =>
        While(transform(cond), transform(body))(word.pos)

      case Phrase(words) =>
        Phrase(words.map(transform))(word.tpe, word.pos)

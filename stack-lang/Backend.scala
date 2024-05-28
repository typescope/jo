import Sast.*
import Symbols.*

/************************************************************************
 *                                                                      *
 * The compiler implementation of the stack-oriented language.          *
 *                                                                      *
 ************************************************************************/
abstract class Backend:
  type Context

  def compile(prog: Prog): Unit

  /** Push an integer literal to value stack */
  def push(v: Int)(using Context): Unit

  /** Push a Boolean literal to value stack */
  def push(v: Boolean)(using Context): Unit

  /** Push the value associated with the given symbol to value stack */
  def push(sym: Symbol)(using Context): Unit

  /** Compile a primitive */
  def primitive(sym: Symbol)(using Context): Unit

  /** Call the funtion */
  def call(fun: Symbol)(using Context): Unit

  /** Compile words */
  def compile(phrase: Phrase)(using Context): Unit =
    for word <- phrase.words do compile(word)

  /** Compile if/then/else */
  def compile(ifElse: Word.If)(using Context): Unit

  /** Compile while/do */
  def compile(whileDo: Word.While)(using Context): Unit

  /** Compile an assignment statement, i.e a = b + c */
  def compile(ifword: Word.Assign)(using Context): Unit

  /** Compile a word */
  def compile(word: Word)(using Context): Unit =
    word match
      case Word.IntLit(v)  => push(v)

      case Word.BoolLit(v) => push(v)

      case assign: Word.Assign =>
        compile(assign)

      case ifElse: Word.If =>
        compile(ifElse)

      case whileDo: Word.While =>
        compile(whileDo)

      case Word.Ident(sym) =>
        if sym.isPrimitive then primitive(sym)
        else if sym.isFunction then call(sym)
        else push(sym)

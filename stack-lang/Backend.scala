import Sast.*

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
  def compile(words: List[Word])(using Context): Unit =
    for word <- words do compile(word)

  /** Compile a conditional statement, i.e if/then/else */
  def compile(ifword: Word.If)(using Context): Unit

  /** Compile an initialization statement, i.e a = b + c */
  def compile(ifword: Word.Init)(using Context): Unit

  /** Compile a word */
  def compile(word: Word)(using Context): Unit =
    word match
      case Word.IntLit(v)  => push(v)

      case Word.BoolLit(v) => push(v)

      case init: Word.Init =>
        compile(init)

      case ifword: Word.If =>
        compile(ifword)

      case Word.Ident(sym) =>
        if sym.isPrimitive then primitive(sym)
        else if sym.isFunction then call(sym)
        else push(sym)

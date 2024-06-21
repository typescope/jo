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

  /** Compile [x = 3, y = 5] */
  def compile(record: RecordLit)(using Context): Unit

  /** Compile p.x */
  def compile(select: Select)(using Context): Unit

  /** Compile if/then/else */
  def compile(ifElse: If)(using Context): Unit

  /** Compile while/do */
  def compile(whileDo: While)(using Context): Unit

  /** Compile an assignment statement, i.e a = b + c */
  def compile(ifword: Assign)(using Context): Unit

  /** Compile an encoding --- backend needs to handle value drop */
  def compile(encoded: Encoded)(using Context): Unit

  /** Compile a word */
  def compile(word: Word)(using Context): Unit =
    word match
      case IntLit(v)  => push(v)

      case BoolLit(v) => push(v)

      case record: RecordLit => compile(record)

      case select: Select => compile(select)

      case phrase: Phrase => compile(phrase)

      case encoded: Encoded => compile(encoded)

      case assign: Assign =>
        compile(assign)

      case ifElse: If =>
        compile(ifElse)

      case whileDo: While =>
        compile(whileDo)

      case Ident(sym) =>
        if sym.isPrimitive then primitive(sym)
        else if sym.isFunction then call(sym)
        else push(sym)

      case _: ValDef =>
        throw new Exception("Unexpected " + word)

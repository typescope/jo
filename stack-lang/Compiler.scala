import Sast.*

/************************************************************************
 *                                                                      *
 * The compiler implementation of the stack-oriented language.          *
 *                                                                      *
 ************************************************************************/
object Compiler:
  def compile(prog: Prog)(using pf: Platform): Unit =
    pf.start()

    // Create labels for all definitions to support recursive definitions
    for defn <- prog.defs do pf.declare(defn.symbol)

    // Compile functions
    for case fdef: Def.FunDef <- prog.defs do
      pf.function(fdef, compile)

    // User code execution starts from here
    pf.entry:
      // Create initializer for value definitions
      for case vdef: Def.ValDef <- prog.defs do
        pf.initVal(vdef, compile)

      compile(prog.main)

    pf.finish()

  def compile(words: List[Word])(using Platform): Unit =
    for word <- words do compile(word)

  def compile(word: Word)(using pf: Platform): Unit =
    word match
      case Word.IntLit(v)  => pf.push(v)

      case Word.BoolLit(v) => pf.push(v)

      case ifword: Word.If =>
          pf.conditional(ifword, compile)

      case Word.Ident(sym) =>
        if sym.isPrimitive then pf.primitive(sym)
        else if sym.isFunction then pf.call(sym)
        else pf.push(sym)

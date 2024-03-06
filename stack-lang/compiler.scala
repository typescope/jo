/************************************************************************
 *                                                                      *
 * The compiler implementation of the stack-oriented language.          *
 *                                                                      *
 ************************************************************************/

//> using file ast.scala
//> using file sast.scala
//> using file namer.scala
//> using file parser.scala
//> using file assembly.scala
//> using file platform.scala
//> using file io.scala
//> using file linux.scala
//> using file x86.scala
//> using file elf32.scala
//> using file UniqueName.scala

/***********************************************************************
 *
 * Compiler
 *
 ***********************************************************************/

object Compiler:
  def compile(prog: Sast.Prog)(using pf: Platform): Unit =
    // Create labels for all definitions to support recursive definitions
    for defn <- prog.defs do pf.declare(defn.symbol)

    // Compile functions
    for case Sast.Def.FunDef(sym, words) <- prog.defs do
      pf.function(sym, () => compile(words))

    // User code execution starts from here
    pf.entry:
      // Create initializer for value definitions
      for case Sast.Def.ValDef(sym, words) <- prog.defs do
        pf.initVal(sym, () => compile(words))

      compile(prog.main)

  def compile(words: List[Sast.Word])(using Platform): Unit =
    for word <- words do compile(word)

  def compile(word: Sast.Word)(using pf: Platform): Unit =
    word match
      case Sast.Word.IntLit(v)  => pf.push(v)

      case Sast.Word.BoolLit(v) => pf.push(v)

      case Sast.Word.Proc(words) => pf.push(() => compile(words))

      case Sast.Word.Ident(sym) =>
        if sym.isVal then pf.push(sym)
        else if sym.isFun then pf.call(sym)
        else pf.primitive(sym)

/***********************************************************************
 *
 * Main entry point
 *
 ***********************************************************************/
@main
def run(sourceFile: String, others: String*) =
  val outFile =
    if others.size == 1 then
      others(0)
    else
      val tokens = sourceFile.split("\\.(?=[^\\.]+$)")
      tokens(0)


  val platform: Platform = Linux.createX86Platform()

  val ast = Parsing.parse(IO.fileContent(sourceFile))
  val sast = Namer.transform(ast)
  Compiler.compile(sast)(using platform)

  IO.withExeFile(outFile): bb =>
    platform.generate()(using bb)

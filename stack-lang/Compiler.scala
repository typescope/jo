//> using file Ast.scala
//> using file Sast.scala
//> using file Namer.scala
//> using file Parser.scala
//> using file Assembly.scala
//> using file Platform.scala
//> using file IO.scala
//> using file Assembler.scala
//> using file Linux.scala
//> using file X86.scala
//> using file ELF32.scala
//> using file UniqueName.scala
//> using file JSPlatform.scala

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
    for case Def.FunDef(sym, words) <- prog.defs do
      pf.function(sym, () => compile(words))

    // User code execution starts from here
    pf.entry:
      // Create initializer for value definitions
      for case Def.ValDef(sym, words) <- prog.defs do
        pf.initVal(sym, () => compile(words))

      compile(prog.main)

    pf.finish()

  def compile(words: List[Word])(using Platform): Unit =
    for word <- words do compile(word)

  def compile(word: Word)(using pf: Platform): Unit =
    word match
      case Word.IntLit(v)  => pf.push(v)

      case Word.BoolLit(v) => pf.push(v)

      case Word.Proc(words) => pf.push(() => compile(words))

      case Word.Ident(sym) =>
        if sym.isVal then pf.push(sym)
        else if sym.isFun then pf.call(sym)
        else pf.primitive(sym)

/***********************************************************************
 *
 * Main entry point
 *
 ***********************************************************************/

@main
def run(args: String*) =
  val optionSpec = Map(
    "-o" -> true,
    "-p" -> true,
    "-layout" -> true, // only valid for linux-x86 platform
  )

  val (options, rest) = IO.parseOptions(args, optionSpec)

  assert(rest.size == 1, "Expect a single source file as input, found = " + rest)
  val sourceFile = rest.head

  val outFile =
    options.get("-o") match
      case Some(file) => file
      case None =>
        val tokens = sourceFile.split("\\.(?=[^\\.]+$)")
        tokens(0)

  val layout = options.getOrElse("-layout", "c1")

  val platform: Platform =
    options.get("-p") match
      case Some(pf) =>
        if pf == "linux-x86" then
          Linux.createX86Platform(outFile, layout)
        else if pf == "javascript" then
          new JSPlatform(outFile)
        else
          throw new Exception("Unknow platform: " + pf)

      case None =>
        Linux.createX86Platform(outFile, layout)

  val ast = Parsing.parse(IO.fileContent(sourceFile))
  val sast = Namer.transform(ast)
  Compiler.compile(sast)(using platform)

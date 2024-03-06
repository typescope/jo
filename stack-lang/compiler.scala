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
//> using file JSPlatform.scala

import scala.collection.mutable

/************************************************************************
 *                                                                      *
 * The compiler implementation of the stack-oriented language.          *
 *                                                                      *
 ************************************************************************/
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

/**
  * Parse options and according to the option specs.
  *
  * @returns matched options and remaining non-optional arguments
  */
def parseOptions(args: Seq[String], options: Map[String, Boolean]): (Map[String, String], List[String]) =
  val rest = new mutable.ArrayBuffer[String]
  val res = mutable.Map.empty[String, String]
  val iter = args.iterator
  while iter.hasNext do
    val arg = iter.next()
    if arg(0) != '-' then
      rest += arg
    else
      options.get(arg) match
        case Some(flag) =>
          if flag then
            if iter.hasNext then
              val value = iter.next()
              if value(0) == '-' then
                throw new Exception("The flag " + arg + " requires an argument")
              else
                res(arg) = value
            else
              throw new Exception("The flag " + arg + " requires an argument")
          else
            res(arg) = ""

        case None => throw new Exception("Unknown flag " + arg)
  end while
  (res.toMap, rest.toList)

@main
def run(args: String*) =
  val optionSpec = Map(
    "-o" -> true,
    "-p" -> true
  )

  val (options, rest) = parseOptions(args, optionSpec)

  assert(rest.size == 1, "Expect a single source file as input, found = " + rest)
  val sourceFile = rest.head

  val outFile =
    options.get("-o") match
      case Some(file) => file
      case None =>
        val tokens = sourceFile.split("\\.(?=[^\\.]+$)")
        tokens(0)

  val platform: Platform =
    options.get("-p") match
      case Some(pf) =>
        if pf == "linux-x86" then Linux.createX86Platform()
        else if pf == "javascript" then new JSPlatform()
        else throw new Exception("Unknow platform: " + pf)
      case None =>
        Linux.createX86Platform()

  val ast = Parsing.parse(IO.fileContent(sourceFile))
  val sast = Namer.transform(ast)
  Compiler.compile(sast)(using platform)
  platform.generate(outFile)

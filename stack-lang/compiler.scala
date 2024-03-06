/************************************************************************
 *                                                                      *
 * The compiler implementation of the stack-oriented language.          *
 *                                                                      *
 ************************************************************************/

//> using file ast.scala
//> using file sast.scala
//> using file namer.scala
//> using file parser.scala
//> using file context.scala
//> using file assembly.scala
//> using file platform.scala
//> using file io.scala
//> using file linux.scala
//> using file x86.scala
//> using file elf32.scala

import scala.collection.mutable


/***********************************************************************
 *
 * Data Definitions for Compilation
 *
 ***********************************************************************/
import Assembly.*

enum Denotation:
  case Data(addr: Label)
  case Fun(addr: Label)
  case Prim

enum Scope:
  case RootScope()
  case NestedScope(outer: Scope)

  private val map: mutable.Map[Sast.Symbol, Denotation] = mutable.Map.empty

  def resolve(sym: Sast.Symbol): Option[Denotation] =
    map.get(sym) match
      case None =>
        this match
          case NestedScope(outer) => outer.resolve(sym)
          case _ => None

      case res  => res

  def bind(sym: Sast.Symbol, denot: Denotation): Unit =
    map.get(sym) match
      case None =>
        map(sym) = denot

      case Some(d) =>
        throw new Exception(sym.name + " is already bound to " + d)

object Scope:
  def createRootScope() =
    val rootScope = new Scope.RootScope()
    for sym <- Sast.predef.allSymbols do
      rootScope.bind(sym, Denotation.Prim)
    rootScope

/***********************************************************************
 *
 * Compiler
 *
 ***********************************************************************/

object Compiler:
  def compile(prog: Sast.Prog)(using ctx: Context): Prog =
    val startLabel = Label(ctx.freshName("_start"))

    // initialize runtime
    ctx.addCodeLabel(ctx.entry)
    ctx.initialize(startLabel)

    // Create labels for all definitions to support recursive definitions
    for defn <- prog.defs do
      val label = Label(ctx.freshName(defn.name))
      defn match
        case _: Sast.Def.ValDef =>
          ctx.bind(defn.symbol, Denotation.Data(label))
          ctx.addDataLabel(label)
          ctx.add(Data.Uninit(Type.Int32))

        case _: Sast.Def.FunDef =>
          ctx.bind(defn.symbol, Denotation.Fun(label))
    end for

    // Compile functions
    for case Sast.Def.FunDef(sym, words) <- prog.defs do
      val Some(Denotation.Fun(label)) = ctx.resolve(sym): @unchecked
      ctx.addCodeLabel(label)
      compile(words)
      summon[Context].ret()
    end for

    // User code execution starts from here

    // Create initializer for value definitions
    ctx.addCodeLabel(startLabel)
    for case Sast.Def.ValDef(sym, words) <- prog.defs do
      compile(words)
      val Some(Denotation.Data(label)) = ctx.resolve(sym): @unchecked
      ctx.useReg: r =>
        ctx.pop(r)
        ctx.add(Instr.Store(Reg(r), label))
    end for

    // Compile the main phrase
    val mainLabel = Label(ctx.freshName("_main"))
    ctx.addCodeLabel(mainLabel)
    compile(prog.main)

    // Finish gracefully
    ctx.finish()

    ctx.getResult()

  def compile(words: List[Sast.Word])(using Context): Unit =
    for word <- words do compile(word)

  def compile(word: Sast.Word)(using ctx: Context): Unit =
    word match
      case Sast.Word.IntLit(v)  => ctx.push(Int32(v))

      case Sast.Word.BoolLit(v) => ctx.push(Int32(if v then 1 else 0))

      case Sast.Word.Proc(words) =>
        val labelStart = Label(ctx.freshName("proc_start"))
        val labelEnd = Label(ctx.freshName("proc_end"))

        ctx.add(Instr.Jump(labelEnd))
        ctx.addCodeLabel(labelStart)
        compile(words)
        ctx.ret()
        ctx.addCodeLabel(labelEnd)

        ctx.push(labelStart)

      case Sast.Word.Ident(sym) =>
        ctx.resolve(sym) match
          case Some(d) =>
            d match
              case Denotation.Fun(addr)  => ctx.call(addr)

              case Denotation.Prim  => ctx.primitive(sym)

              case Denotation.Data(addr) =>
                ctx.useReg: r =>
                  ctx.add(Instr.Load(addr, r))
                  ctx.push(Reg(r))


          case None =>
            throw new Exception("Undefined identifier " + sym)


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
  val ctx: Context = Context.createContext(platform)

  val ast = Parsing.parse(IO.fileContent(sourceFile))
  val sast = Namer.transform(ast)
  val asm: Prog = Compiler.compile(sast)(using ctx)

  IO.withExeFile(outFile): bb =>
    platform.generate(asm)(using bb)

package llvm

import sast.*
import sast.Trees.*
import sast.Symbols.*

import scala.collection.mutable

/** The loops of a function that open a region per iteration, and the homes of its variables
  *
  * See "Iteration regions" and "Assignments to variables declared outside the
  * loop" in GC.md. A loop opens iteration regions if `opens` holds for it.
  * Every variable belongs to a home: the iteration region of such a loop, or
  * the region of the function. A variable belongs to the innermost such loop
  * whose condition or body contains all of its occurrences and writes it
  * before it reads it in every iteration. Parameters and all other variables
  * belong to the function.
  *
  * A variable that fails the check at one loop is checked again at the next
  * enclosing one. Falling back is sound and only keeps more.
  */
class IterationRegions(body: Word, params: Set[Symbol], opens: While => Boolean, runtime: LLVMRuntime)(using Definitions):
  import IterationRegions.*

  /** The loops that open iteration regions, with the enclosing such loop, by identity */
  private val parents = new java.util.IdentityHashMap[While, Option[While]]

  /** The loops that open iteration regions, inner loops first */
  private val postOrder = new mutable.ArrayBuffer[While]

  /** The loops enclosing all occurrences of each variable, outermost first */
  private val paths = mutable.LinkedHashMap.empty[Symbol, List[While]]

  /** The home loop of a variable, absent for the function */
  private val homes = mutable.Map.empty[Symbol, While]

  locally:
    collect(body, Nil)

    // The loop at which each variable is checked next, by its path
    val candidate = mutable.Map.empty[Symbol, List[While]]
    for (sym, path) <- paths if !params.contains(sym) && path.nonEmpty do
      candidate(sym) = path.reverse

    for loop <- postOrder do
      val syms = candidate.collect { case (sym, l :: _) if l `eq` loop => sym }.toSet
      if syms.nonEmpty then
        val failed = new Check(syms).run(loop)
        for sym <- syms do
          if failed.contains(sym) then candidate(sym) = candidate(sym).tail
          else
            homes(sym) = loop
            candidate.remove(sym)

  /** Whether a loop opens iteration regions */
  def opensRegions(loop: While): Boolean = parents.containsKey(loop)

  /** Whether a loop that opens iteration regions is inside another such loop */
  def isNested(loop: While): Boolean = parents.containsKey(loop) && parents.get(loop).isDefined

  /** The loop whose iteration region is the home of a variable, `None` for the function */
  def home(sym: Symbol): Option[While] = homes.get(sym)

  /** Record the loops around every occurrence of a variable. `loops` is innermost first. */
  private def collect(word: Word, loops: List[While]): Unit =
    def occurs(sym: Symbol): Unit =
      if !sym.is(Flags.Fun) then
        val path = loops.reverse
        paths(sym) = paths.get(sym) match
          case Some(old) => commonPrefix(old, path)
          case None => path

    word match
      case loop @ While(cond, body) =>
        if opens(loop) then
          parents.put(loop, loops.headOption)
          collect(cond, loop :: loops)
          collect(body, loop :: loops)
          postOrder += loop
        else
          collect(cond, loops)
          collect(body, loops)

      case Ident(sym) => occurs(sym)

      case Assign(ident, rhs, _) =>
        occurs(ident.symbol)
        collect(rhs, loops)

      case app: Apply if directCallee(app).contains(runtime.ParamSupport_paramKey) =>
        // reads its argument as a name

      case _ =>
        eachPart(word)(collect(_, loops))

  /** The definite assignment check of the candidate variables `syms` on a loop
    *
    * It walks the condition and the body in evaluation order, keeping the set
    * of variables written so far. A `Bool` word has a set when it is true and
    * one when it is false. An outcome that cannot happen, after a `Return` or
    * a call that never returns, has all variables. A read of a candidate
    * outside the set fails it.
    */
  private class Check(syms: Set[Symbol]):
    private val failed = mutable.Set.empty[Symbol]

    def run(loop: While): collection.Set[Symbol] =
      val (whenTrue, _) = walk(loop.cond, Written.none)
      walk(loop.body, whenTrue)
      failed

    private def out(sets: (Written, Written)): Written = sets._1.meet(sets._2)

    private def same(w: Written): (Written, Written) = (w, w)

    private def sequence(words: List[Word], in: Written): Written =
      words.foldLeft(in)((w, word) => out(walk(word, w)))

    private def walk(word: Word, in: Written): (Written, Written) =
      word match
        case Literal(Constant.Bool(true)) => (in, Written.all)

        case Literal(Constant.Bool(false)) => (Written.all, in)

        case Block(words) =>
          if words.isEmpty then same(in)
          else walk(words.last, sequence(words.init, in))

        case Encoded(repr) => walk(repr, in)

        case If(cond, thenp, elsep) =>
          val (condTrue, condFalse) = walk(cond, in)
          val (thenTrue, thenFalse) = walk(thenp, condTrue)
          val (elseTrue, elseFalse) = walk(elsep, condFalse)
          (thenTrue.meet(elseTrue), thenFalse.meet(elseFalse))

        case Ident(sym) =>
          if syms.contains(sym) && !in.contains(sym) then failed += sym
          same(in)

        case Assign(ident, rhs, _) => same(out(walk(rhs, in)).add(ident.symbol))

        case Return(_, value) =>
          walk(value, in)
          same(Written.all)

        case While(cond, body) =>
          val (condTrue, _) = walk(cond, in)
          walk(body, condTrue)
          same(in)

        case Labeled(_, _, body) =>
          walk(body, in)
          same(in)

        case app: Apply =>
          val sets = directCallee(app) match
            // `a && b` is `If(a, b, false)`, `a || b` is `If(a, true, b)`, as CodeGen compiles them
            case Some(sym) if sym == runtime.Bool_and =>
              val a :: b :: Nil = app.args: @unchecked
              val (aTrue, aFalse) = walk(a, in)
              val (bTrue, bFalse) = walk(b, aTrue)
              (bTrue, bFalse.meet(aFalse))

            case Some(sym) if sym == runtime.Bool_or =>
              val a :: b :: Nil = app.args: @unchecked
              val (aTrue, aFalse) = walk(a, in)
              val (bTrue, bFalse) = walk(b, aFalse)
              (aTrue.meet(bTrue), bFalse)

            case Some(sym) if sym == runtime.ParamSupport_paramKey => same(in)

            case Some(_) => same(sequence(app.allArgs, in))

            case None => same(sequence(app.fun :: app.allArgs, in))

          if app.tpe.dealias.isBottomType then same(Written.all) else sets

        case _ =>
          // the other words evaluate their parts in order
          var w = in
          eachPart(word)(part => w = out(walk(part, w)))
          same(w)
  end Check
end IterationRegions

object IterationRegions:
  /** The variables written so far, or all variables on a path that cannot be taken */
  case class Written(all: Boolean, syms: Set[Symbol]):
    def contains(sym: Symbol): Boolean = all || syms.contains(sym)

    def add(sym: Symbol): Written = if all then this else Written(false, syms + sym)

    /** The variables written on both paths */
    def meet(that: Written): Written =
      if all then that
      else if that.all then this
      else Written(false, syms.intersect(that.syms))

  object Written:
    val none = Written(false, Set.empty)
    val all = Written(true, Set.empty)

  /** Visit the direct parts of a word, in evaluation order */
  private class Parts(visit: Word => Unit) extends TreeTraverser:
    type Context = Unit

    def apply(word: Word)(using Unit): Unit = visit(word)

  private def eachPart(word: Word)(visit: Word => Unit): Unit =
    new Parts(visit).recur(word)(using ())

  private def commonPrefix(a: List[While], b: List[While]): List[While] =
    (a, b) match
      case (x :: xs, y :: ys) if x `eq` y => x :: commonPrefix(xs, ys)
      case _ => Nil
end IterationRegions

package fuzzing

import java.nio.file.Files

import ast.Trees.*
import ast.Printing
import parsing.Parser
import reporting.Reporter

/** Parse-AST reducer.
  *
  * Parses the crashing input, iteratively shrinks the parse tree by deleting
  * one element from a list-valued position at a time (definitions, imports,
  * block phrases, match cases, class members), prints the result, and checks
  * that the shrunken bytes still trigger the same crash fingerprint.
  *
  * Works only on inputs the parser accepts (see [[canReduce]]). For parser
  * crashes or non-UTF-8 bytes, [[Reducer.best]] routes to the coarser
  * [[LineDeltaReducer]].
  *
  * When `Printing.show(unit)` does not round-trip (printer emits something the
  * parser rejects or whose typer outcome differs), candidates are silently
  * dropped. Printer gaps surface as "reducer stalled" rather than "crashed" —
  * improve `ast.Printing` to fix.
  */
object AstReducer extends Reducer:

  /** True iff the dispatcher should prefer this reducer for `input`. */
  def canReduce(input: Array[Byte]): Boolean =
    tryParse(input).isDefined

  def reduce(
      input: Array[Byte],
      target: Target,
      expected: Fingerprint,
      maxIterations: Int = Reducer.DefaultMaxIterations,
      trialTimeoutSeconds: Int = Reducer.DefaultTrialTimeoutSeconds,
  ): Array[Byte] =

    tryParse(input) match
      case None =>
        LineDeltaReducer.reduce(input, target, expected, maxIterations, trialTimeoutSeconds)

      case Some(initial) =>
        val printed = printBytes(initial)

        // If the printer does not reproduce the crash, there is no point
        // shrinking the AST — every trial will discard. Fall back.
        if !Reducer.triggers(printed, target, expected, trialTimeoutSeconds) then
          LineDeltaReducer.reduce(input, target, expected, maxIterations, trialTimeoutSeconds)
        else
          val reduced = loop(initial, printed, target, expected, maxIterations, trialTimeoutSeconds)
          // If the printer re-emitted the tree larger than the original input
          // and AST shrinking couldn't overcome the gap, prefer the original.
          if reduced.length < input.length then reduced else input
  end reduce

  //--------------------------------------------------------------------------
  // Main loop

  private def loop(
      initial: FileUnit,
      initialBytes: Array[Byte],
      target: Target,
      expected: Fingerprint,
      maxIterations: Int,
      trialTimeoutSeconds: Int
  ): Array[Byte] =
    var current   = initial
    var currentBs = initialBytes
    var iter      = 0
    var progress  = true

    while progress && iter < maxIterations do
      progress = false
      val cands = candidates(current).iterator
      var restart = false

      while !restart && cands.hasNext && iter < maxIterations do
        iter += 1
        val cand  = cands.next()
        val bytes = printBytes(cand)

        if bytes.length < currentBs.length
            && Reducer.triggers(bytes, target, expected, trialTimeoutSeconds) then
          current   = cand
          currentBs = bytes
          progress  = true
          restart   = true
      end while
    end while

    currentBs
  end loop

  //--------------------------------------------------------------------------
  // Parse + print

  private def tryParse(input: Array[Byte]): Option[FileUnit] =
    val tmp = Files.createTempFile("fuzz-ast-reduce-", ".jo")
    try
      Files.write(tmp, input)
      given Reporter = Reporter.createReporter(buffer = true)
      Some(Parser.parse(tmp.toString))
    catch case _: Throwable => None
    finally Files.deleteIfExists(tmp)
  end tryParse

  private def printBytes(unit: FileUnit): Array[Byte] =
    Printing.show(unit).getBytes("UTF-8")

  //--------------------------------------------------------------------------
  // Candidate generation

  /** Yield shrink candidates for `u`, outer-first (top-level before nested):
    * dropping a definition is cheaper and more impactful than dropping a
    * phrase inside one of them.
    */
  private def candidates(u: FileUnit): LazyList[FileUnit] =
    topLevelShrinks(u) #::: nestedShrinks(u)

  private def topLevelShrinks(u: FileUnit): LazyList[FileUnit] =
    drops(u.defs).map(ds => u.copy(defs = ds)) #:::
      drops(u.imports).map(is => u.copy(imports = is))

  private def nestedShrinks(u: FileUnit): LazyList[FileUnit] =
    indexed(u.defs).flatMap: (d, i) =>
      shrinkDef(d).map(d2 => u.copy(defs = u.defs.updated(i, d2)))

  private def shrinkDef(d: Def): LazyList[Def] = d match
    case f: FunDef       => shrinkFunDef(f)
    case c: ClassDef     => shrinkClassDef(c)
    case o: ObjectDef    => shrinkObjectDef(o)
    case i: InterfaceDef => shrinkInterfaceDef(i)
    case x: ExtensionDef => shrinkExtensionDef(x)
    case u: UnionDef     => shrinkUnionDef(u)
    case s: Section      => shrinkSection(s)
    case p: PatDef       => shrinkPatDef(p)
    case _               => LazyList.empty

  private def shrinkFunDef(f: FunDef): LazyList[FunDef] =
    shrinkBody(f.body).map(b => f.copy(body = b)(f.span))

  private def shrinkClassDef(c: ClassDef): LazyList[ClassDef] =
    drops(c.funs).map(fs => c.copy(funs = fs)(c.span)) #:::
      drops(c.vals).map(vs => c.copy(vals = vs)(c.span)) #:::
      indexed(c.funs).flatMap: (f, i) =>
        shrinkFunDef(f).map(f2 => c.copy(funs = c.funs.updated(i, f2))(c.span))

  private def shrinkObjectDef(o: ObjectDef): LazyList[ObjectDef] =
    drops(o.funs).map(fs => o.copy(funs = fs)(o.span)) #:::
      indexed(o.funs).flatMap: (f, i) =>
        shrinkFunDef(f).map(f2 => o.copy(funs = o.funs.updated(i, f2))(o.span))

  private def shrinkInterfaceDef(i: InterfaceDef): LazyList[InterfaceDef] =
    drops(i.members).map(ms => i.copy(members = ms)(i.span))

  private def shrinkExtensionDef(x: ExtensionDef): LazyList[ExtensionDef] =
    drops(x.funs).map(fs => x.copy(funs = fs)(x.span)) #:::
      indexed(x.funs).flatMap: (f, i) =>
        shrinkFunDef(f).map(f2 => x.copy(funs = x.funs.updated(i, f2))(x.span))

  private def shrinkUnionDef(u: UnionDef): LazyList[UnionDef] =
    val branchShrinks: LazyList[UnionDef] =
      if u.branches.size > 1 then drops(u.branches).map(bs => u.copy(branches = bs)(u.span))
      else LazyList.empty
    branchShrinks #::: drops(u.funs).map(fs => u.copy(funs = fs)(u.span))

  private def shrinkSection(s: Section): LazyList[Section] =
    drops(s.defs).map(ds => s.copy(defs = ds)(s.span)) #:::
      indexed(s.defs).flatMap: (d, i) =>
        shrinkDef(d).map(d2 => s.copy(defs = s.defs.updated(i, d2))(s.span))

  private def shrinkPatDef(p: PatDef): LazyList[PatDef] =
    if p.cases.size > 1 then drops(p.cases).map(cs => p.copy(cases = cs)(p.span))
    else LazyList.empty

  private def shrinkBody(w: Word): LazyList[Word] = w match
    case blk: Block =>
      val phraseShrinks: LazyList[Word] =
        if blk.phrases.size > 1 then drops(blk.phrases).map(ps => Block(ps)(blk.span))
        else LazyList.empty
      phraseShrinks #::: indexed(blk.phrases).flatMap: (p, i) =>
        shrinkBody(p).map(p2 => Block(blk.phrases.updated(i, p2))(blk.span))

    case m: Match =>
      if m.cases.size > 1 then drops(m.cases).map(cs => Match(m.scrutinee, cs)(m.span))
      else LazyList.empty

    case _ => LazyList.empty

  //--------------------------------------------------------------------------
  // Helpers

  /** For each index i in 0..xs.size-1, yield the list with element i removed. */
  private def drops[A](xs: List[A]): LazyList[List[A]] =
    LazyList.range(0, xs.size).map(i => xs.take(i) ++ xs.drop(i + 1))

  private def indexed[A](xs: List[A]): LazyList[(A, Int)] =
    xs.zipWithIndex.to(LazyList)

end AstReducer

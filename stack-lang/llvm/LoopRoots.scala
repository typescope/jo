package llvm

import sast.*
import sast.Trees.*
import sast.Symbols.*

import scala.collection.mutable

/** The direct callee of a call, as CodeGen tells it */
def directCallee(app: Apply): Option[Symbol] =
  app.funSymbol.orElse:
    app.fun match
      case Encoded(Ident(sym)) if sym.is(Flags.Fun) => Some(sym)
      case _ => None

/** Whether a word contains a loop */
class LoopFinder:
  private val cache = new java.util.IdentityHashMap[Word, java.lang.Boolean]

  def apply(word: Word): Boolean =
    val known = cache.get(word)
    if known != null then known.booleanValue
    else
      var found = false
      val finder = new TreeTraverser:
        type Context = Unit

        def apply(word: Word)(using Unit): Unit =
          word match
            case _: While => found = true
            case _ => if !found then recur(word)

      finder(word)(using ())
      cache.put(word, found)
      found

/** Operands computed before a loop, see "Collecting a region in a loop" in GC.md
  *
  * CodeGen holds the earlier operands of a call, a record literal and a field
  * assignment in LLVM registers while it compiles the later ones. A loop
  * collection in a later operand would not see them. This rewrite assigns
  * every operand up to the last one that contains a loop to a fresh local,
  * so the word reads the locals after the loop, where they are roots. A
  * record is allocated after its fields then.
  *
  * Constants are not hoisted, since they hold no heap reference. An operand
  * of type `void` or `Bottom` is evaluated as a statement in its place.
  * `&&` and `||` evaluate their second operand only after they consumed the
  * first, so they are left alone.
  *
  * The rewrite rebuilds only the nodes it changes and the nodes above them,
  * so every other node keeps its identity, which placing results relies on.
  */
class LoopOperands(fdef: FunDef, runtime: LLVMRuntime, hasLoop: LoopFinder)(using defn: Definitions) extends TreeMap:
  type Context = Unit

  private var count = 0

  def run(): FunDef =
    val body2 = this(fdef.body)(using ())
    if body2 `eq` fdef.body then fdef
    else fdef.copy(body = body2)(fdef.annots, fdef.span)

  override def transformApply(apply: Apply)(using Unit): Word =
    super.transformApply(apply) match
      case app: Apply =>
        val callee = directCallee(app)
        val shortCircuit = callee.exists(sym => sym == runtime.Bool_and || sym == runtime.Bool_or)
        val operands = if callee.isDefined then app.allArgs else app.fun :: app.allArgs
        val last = operands.lastIndexWhere(hasLoop(_))
        if shortCircuit || last <= 0 then app
        else
          val stats = new mutable.ArrayBuffer[Word]
          val operands2 = hoist(operands, last, stats)
          val (fun2, all2) = if callee.isDefined then (app.fun, operands2) else (operands2.head, operands2.tail)
          val (args2, autos2) = all2.splitAt(app.args.size)
          val app2 = Apply(fun2, args2, autos2)(app.span, app.isPartialApply)
          Block((stats :+ app2).toList)(app.span)

      case word => word

  override def transformRecord(rc: RecordLit)(using Unit): Word =
    super.transformRecord(rc) match
      case rec: RecordLit =>
        val values = rec.args.map(_._2)
        val last = values.lastIndexWhere(hasLoop(_))
        if last < 0 then rec
        else
          val stats = new mutable.ArrayBuffer[Word]
          val values2 = hoist(values, last, stats)
          val rec2 = RecordLit(rec.args.map(_._1).zip(values2))(rec.span)
          Block((stats :+ rec2).toList)(rec.span)

      case word => word

  override def transformFieldAssign(fieldAssign: FieldAssign)(using Unit): Word =
    super.transformFieldAssign(fieldAssign) match
      case assign @ FieldAssign(select @ Select(qual, name), rhs) if hasLoop(rhs) =>
        val stats = new mutable.ArrayBuffer[Word]
        val qual2 :: rhs2 :: Nil = hoist(qual :: rhs :: Nil, 1, stats): @unchecked
        val assign2 = FieldAssign(Select(qual2, name)(select.span), rhs2)
        Block((stats :+ assign2).toList)(assign.span)

      case word => word

  /** Assign the operands up to `last` to fresh locals, adding the assignments to `stats` */
  private def hoist(operands: List[Word], last: Int, stats: mutable.ArrayBuffer[Word]): List[Word] =
    for (operand, i) <- operands.zipWithIndex yield
      if i > last || isConstant(operand) then operand
      else if operand.tpe.isVoidType || operand.tpe.isBottomType then
        stats += operand
        Block(Nil)(operand.span)
      else
        count += 1
        val sym = TermSymbol.create("loop$operand" + count, operand.tpe.widen, Flags.Synthetic, Visibility.Default, fdef.symbol, fdef.symbol.sourcePos)
        stats += Assign(Ident(sym)(operand.span), operand, isDefine = true)
        Ident(sym)(operand.span)

  private def isConstant(word: Word): Boolean =
    word match
      case _: Literal => true
      case Ident(sym) => sym.is(Flags.Fun)
      case Encoded(repr) => isConstant(repr)
      case _ => false

/** The roots of the loops of a function, see "The roots of the frame" in GC.md
  *
  * A root of a loop is a local slot holding a heap reference that is read at
  * or after the start of the outermost loop around the loop, in the order
  * CodeGen compiles the function. Control from the end of an iteration only
  * goes back into the loop, forward to later code, or back into an enclosing
  * loop, so no read before the outermost loop can run again.
  *
  * `qualifies` tells the loops that collect, and `isRoot` the slots that hold
  * a heap reference.
  */
class LoopRoots(body: Word, runtime: LLVMRuntime, qualifies: While => Boolean, isRoot: Symbol => Boolean):
  /** The roots of each collecting loop, by identity */
  val roots = new java.util.IdentityHashMap[While, List[Symbol]]

  private var position = 0

  /** The position of the last read of each local */
  private val lastRead = mutable.LinkedHashMap.empty[Symbol, Int]

  /** The collecting loops and the start of their outermost loop */
  private val loops = new mutable.ArrayBuffer[(While, Int)]

  /** The start of the outermost loop around the current word, if any */
  private var outermost: Option[Int] = None

  visit(body)

  for (loop, start) <- loops do
    val syms = for (sym, pos) <- lastRead if pos >= start && isRoot(sym) yield sym
    roots.put(loop, syms.toList)

  /** The largest number of roots of a loop */
  def maxRoots: Int =
    var max = 0
    roots.values.forEach(syms => max = math.max(max, syms.size))
    max

  private def visit(word: Word): Unit =
    word match
      case Block(words) => words.foreach(visit)

      case Encoded(repr) => visit(repr)

      case app: Apply =>
        directCallee(app) match
          // reads its argument as a tree
          case Some(sym) if sym == runtime.ParamSupport_paramKey =>

          case Some(_) => app.allArgs.foreach(visit)

          case None =>
            visit(app.fun)
            app.allArgs.foreach(visit)

      case Assign(_, rhs, _) => visit(rhs)

      case If(cond, thenp, elsep) =>
        visit(cond)
        visit(thenp)
        visit(elsep)

      case loop @ While(cond, body) =>
        position += 1
        val outer = outermost
        val start = outer.getOrElse(position)
        if qualifies(loop) then loops += ((loop, start))

        outermost = Some(start)
        visit(cond)
        visit(body)
        outermost = outer

      case Labeled(_, _, body) => visit(body)

      case Return(_, value) => visit(value)

      case Ident(sym) =>
        if !sym.is(Flags.Fun) then
          position += 1
          lastRead(sym) = position

      case TypeApply(fun, _) => visit(fun)

      case RecordLit(args) => args.foreach((_, value) => visit(value))

      case Select(qual, _) => visit(qual)

      case FieldAssign(Select(qual, _), rhs) =>
        visit(qual)
        visit(rhs)

      case _ =>

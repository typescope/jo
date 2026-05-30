package sast

import Trees.*
import Symbols.*
import Types.*

import scala.collection.mutable

/** Reachability analysis starting from `root`.
 *
 *  @param root          The program entry point.
 *  @param rewire        Symbol redirects used for linking (e.g. jo.main → user main).
 *  @param intrinsicDeps Additional symbols made reachable when a given symbol is
 *                       reached.  Used by backends to declare codegen-injected
 *                       dependencies that have no corresponding SAST node (e.g.
 *                       runtime helpers substituted for @intrinsic calls, or
 *                       classes instantiated inside try/rescue wrappers).
 *                       Defaults to empty (no extra deps).
 */
class Universe(root: Symbol, rewire: Map[Symbol, Symbol], intrinsicDeps: Map[Symbol, List[Symbol]] = Map.empty)(using defn: Definitions):

  // ---- worklist state -------------------------------------------------------

  private val _live    = mutable.Set.empty[Symbol]
  private val worklist = mutable.Queue.empty[Symbol]

  private def enqueue(sym: Symbol): Unit =
    val resolved = if sym.isFunction then rewire.getOrElse(sym, sym) else sym
    intrinsicDeps.getOrElse(resolved, Nil).foreach(enqueue)
    if !_live.contains(resolved) && !resolved.hasAnnotation(defn.intrinsic) then
      worklist.enqueue(resolved)

  private val traverser = new Universe.Traverser(enqueue)

  private def collectRefs(body: Word): Unit = traverser(body)(using ())

  // ---- fixed-point computation ----------------------------------------------

  def run(): Set[Symbol] =
    enqueue(root)

    val _liveClasses  = mutable.Set.empty[Symbol]
    val _deferMethods = mutable.Set.empty[Symbol]

    while worklist.nonEmpty do
      val sym = worklist.dequeue()
      if !_live.contains(sym) then
        _live += sym

        if sym.isFunction then
          val fdef = defn.index.getCode(sym)
          collectRefs(fdef.body)

        // CHA: abstract method live → find concrete impls in live classes.
        if sym.is(Flags.Defer) then
          _deferMethods += sym
          val ifaceSym = sym.owner
          for classSym <- _liveClasses do
            val classInfo = classSym.classInfo
            if classInfo.directViews.exists(_.typeSymbol == ifaceSym) then
              enqueue(classInfo.memberSymbol(sym.name))

        // CHA mirror: class live → find already-live abstract methods it implements.
        if sym.isClass then
          _liveClasses += sym
          val classInfo = sym.classInfo
          classInfo.directViews.foreach: itype =>
            val ifaceSym = itype.typeSymbol
            val reachable = ifaceSym.classInfo.allMethods.filter(_deferMethods.contains)
            for abstractMeth <- reachable do
              enqueue(classInfo.memberSymbol(abstractMeth.name))
        end if
      end if
    end while
    _live.toSet

end Universe

object Universe:

  private[sast] class Traverser(enqueue: Symbol => Unit)(using Definitions) extends TreeTraverser:
    type Context = Unit

    def apply(word: Word)(using Unit): Unit =
      word match
        case Ident(sym) if sym.isFunction =>
          enqueue(sym)
          if sym.is(Flags.Object) then
            // Singleton object accessor: backends derive the class from resultType at
            // emit time (not from the FunDef body), so enqueue the class explicitly.
            enqueue(sym.tpe.asProcType.resultType.classSymbol)

        case sel @ Select(_, _) =>
          sel.tpe match
            case MemberRef(_, sym) if sym.isFunction => enqueue(sym)
            case _ =>

          recur(word)

        case Encoded(repr) =>
          this(repr)

          if word.tpe.isLambdaType && repr.tpe.isClassType then
            // Lambda encoding from ElimCapture: Encoded(Apply(Select(New(cls), "<init>"), captures))
            // Add the lambda class and its `apply` method (lambda assumption).

            val cls = repr.tpe.classSymbol
            val apply = cls.termMember(Names.apply)
            enqueue(apply)

        case New(classType) =>
          enqueue(classType.tpe.classSymbol)

        case ClassTest(_, cls) =>
          enqueue(cls)
          recur(word)

        case _ =>
          recur(word)

  def filter(units: List[FileUnit], root: Symbol, rewire: Map[Symbol, Symbol], intrinsicDeps: Map[Symbol, List[Symbol]] = Map.empty)(using Definitions): List[FileUnit] =
    filter(units, new Universe(root, rewire, intrinsicDeps).run())

  /** Return a copy of `units` with all unreachable definitions removed.
   *
   *  - A top-level FunDef is kept iff its symbol is in `live`.
   *  - A ClassDef is kept iff its class symbol is in `live`;
   *    its `funs` list is trimmed to only those methods in `live`.
   *  - An InterfaceDef is kept iff at least one of its methods is in `live`;
   *    its `methods` list is trimmed accordingly.
   *  - All other def kinds are dropped (backends do not emit them).
   */
  def filter(units: List[FileUnit], live: Set[Symbol]): List[FileUnit] =
    units.map: unit =>
      val kept = mutable.ArrayBuffer.empty[Def]
      unit.foreach:
        case fdef: FunDef =>
          if live.contains(fdef.symbol) then kept += fdef

        case cdef: ClassDef =>
          if live.contains(cdef.symbol) then
            kept += cdef.withFuns(cdef.funs.filter(f => live.contains(f.symbol)))

        case idef: InterfaceDef =>
          val reachableMethods = idef.methods.filter(m => live.contains(m.symbol))
          if reachableMethods.nonEmpty then
            kept += idef.withMethods(reachableMethods)

        case _ =>

      unit.copy(defs = kept.toList)

package sast

import Trees.*
import Symbols.*

import scala.collection.mutable

/** Reachability worklist driven by references emitted during backend lowering.
  *
  * Linking resolves deferred function identifiers. Abstract interface method
  * selections remain for dynamic dispatch, so retain their implementations
  * in reachable classes regardless of which is discovered first.
  */
class Universe(rewire: Map[Symbol, Symbol])(using defn: Definitions):
  private val live = mutable.Set.empty[Symbol]
  private val worklist = mutable.Queue.empty[Symbol]
  private val liveClasses = mutable.Set.empty[Symbol]
  private val abstractMethods = mutable.Set.empty[Symbol]

  /** Enqueue a reference after resolving links and deduplicating it.
    *
    * Intrinsics have no emitted definition; their lowering marks any helpers.
    */
  private def enqueue(sym: Symbol): Unit =
    val resolved = rewire.getOrElse(sym, sym)

    if !resolved.hasAnnotation(defn.intrinsic) && live.add(resolved) then
      worklist.enqueue(resolved)

  /** Mark a reference to a function emitted outside a class. */
  def useFunction(sym: Symbol): Unit =
    assert(sym.isFunction && !sym.isMethod, s"Expected a function: $sym")
    enqueue(sym)

  /** Mark an instance method reference, including abstract interface methods. */
  def useMethod(sym: Symbol): Unit =
    assert(sym.isMethod && !sym.is(Flags.Constructor), s"Expected a method: $sym")
    enqueue(sym)

  /** Mark a class reference without requiring its constructor. */
  def useClass(cls: Symbol): Unit =
    assert(cls.isClass, s"Expected a class: $cls")
    enqueue(cls)

  /** Mark construction of a class.
    *
    * Construction references both the class and its constructor, unlike a
    * class test, which only calls `useClass`.
    */
  def useConstructor(cls: Symbol): Unit =
    useClass(cls)
    val ctor = cls.termMember(Names.Constructor)
    assert(ctor.is(Flags.Constructor), s"Expected a constructor: $ctor")
    enqueue(ctor)

  /** Lower each reachable function once.
    *
    * Assemble definitions separately to preserve source order and keep methods
    * grouped within their classes.
    */
  def lower[A](units: List[FileUnit], root: Symbol)(compile: FunDef => A): Map[Symbol, A] =
    val functions = mutable.Map.empty[Symbol, FunDef]

    for unit <- units; tree <- unit do
      tree match
        case fdef: FunDef => functions(fdef.symbol) = fdef

        case cdef: ClassDef => cdef.funs.foreach(f => functions(f.symbol) = f)

        case _ =>

    val lowered = mutable.Map.empty[Symbol, A]
    useFunction(root)

    while worklist.nonEmpty do
      val sym = worklist.dequeue()

      if sym.isAllOf(Flags.Method | Flags.Defer) then
        abstractMethods += sym

        for cls <- liveClasses do
          if cls.classInfo.views.exists(_.typeSymbol == sym.owner) then
            useMethod(cls.classInfo.memberSymbol(sym.name))

      else if sym.isFunction && !sym.is(Flags.Object) then
        // Concrete methods require their enclosing class shell as well.
        if sym.is(Flags.Method) then useClass(sym.owner)

        lowered(sym) = compile(functions(sym))

      if sym.isClass then
        liveClasses += sym
        val info = sym.classInfo

        for view <- info.views; method <- view.typeSymbol.classInfo.allMethods do
          if abstractMethods.contains(method) then useMethod(info.memberSymbol(method.name))

        if sym.is(Flags.Object) then useConstructor(sym)

      end if
    end while

    lowered.toMap

  def contains(sym: Symbol): Boolean = live.contains(sym)

end Universe

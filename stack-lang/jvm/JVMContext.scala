package jvm

import sast.*
import sast.Trees.*
import sast.Symbols.*

import scala.collection.mutable

/** Program-wide JVM backend services.
  *
  * This is the single owner of definition indexing, symbol rewiring,
  * reachability worklists, and generated-name allocation. Compilation code
  * requests definitions and names through this API without sharing its
  * mutable collections.
  */
final class JVMContext(rewire: Map[Symbol, Symbol]):
  import JVMContext.*

  private val funDefs = mutable.Map.empty[Symbol, FunDef]
  private val classDefs = mutable.Map.empty[Symbol, ClassDef]
  private val interfaceDefs = mutable.Map.empty[Symbol, InterfaceDef]

  private val topLevelWork = new mutable.ArrayDeque[Symbol]()
  private val topLevelSeen = mutable.Set.empty[Symbol]
  private val topLevelNames = mutable.Map.empty[Symbol, String]

  private val classWork = new mutable.ArrayDeque[Symbol]()
  private val classSeen = mutable.Set.empty[Symbol]
  private val classNames = mutable.Map.empty[Symbol, String]

  // Reserve compiler/runtime-owned class names before allocating any user
  // name. This set is deliberately shared by methods and classes because
  // top-level functions are emitted as methods on Main but their generated
  // names have historically participated in the same collision policy.
  private val usedNames = mutable.Set[String]("Main", "Node", "Lambda")

  def index(units: List[FileUnit]): Unit =
    units.foreach(unit => indexDefs(unit.defs))

  def resolve(sym: Symbol): Symbol = rewire.getOrElse(sym, sym)

  def requireTopLevel(sym: Symbol): String =
    val resolved = resolve(sym)
    topLevelNames.getOrElseUpdate(resolved, {
      val name = allocateName(resolved.fullName.replace('.', '$'))
      topLevelWork.append(resolved)
      name
    })

  def requireClass(sym: Symbol): String =
    if !classSeen(sym) then
      classSeen += sym
      classWork.append(sym)
    className(sym)

  def className(sym: Symbol): String =
    classNames.getOrElseUpdate(sym, allocateName(sym.name.replace('.', '$')))

  def nextPending(): Option[Pending] =
    while topLevelWork.nonEmpty do
      val sym = topLevelWork.removeHead()
      if !topLevelSeen(sym) then
        topLevelSeen += sym
        return Some(Pending.TopLevel(funDefs(sym)))

    if classWork.nonEmpty then
      val sym = classWork.removeHead()
      classDefs.get(sym) match
        case Some(cdef) => Some(Pending.Class(cdef))
        case None => Some(Pending.Interface(interfaceDefs(sym)))
    else None

  def topLevelName(sym: Symbol): String = topLevelNames(resolve(sym))
  def classDef(sym: Symbol): ClassDef = classDefs(sym)
  def interfaceDef(sym: Symbol): InterfaceDef = interfaceDefs(sym)

  private def indexDefs(defs: List[Def]): Unit =
    defs.foreach {
      case fdef: FunDef => funDefs(fdef.symbol) = fdef
      case cdef: ClassDef =>
        classDefs(cdef.symbol) = cdef
        indexDefs(cdef.funs)
      case idef: InterfaceDef =>
        interfaceDefs(idef.symbol) = idef
        indexDefs(idef.methods)
      case section: Section => indexDefs(section.defs)
      case _ =>
    }

  private def allocateName(raw: String): String =
    val base = sanitize(raw)
    var name = base
    var suffix = 0
    while usedNames.contains(name) do
      suffix += 1
      name = base + "_" + suffix
    usedNames += name
    name

  private def sanitize(s: String): String =
    val result = new StringBuilder
    s.foreach(c => if c.isLetterOrDigit || c == '_' || c == '$' then result += c else result += '_')
    if result.isEmpty || result.head.isDigit then result.insert(0, '_')
    result.toString

object JVMContext:
  enum Pending:
    case TopLevel(definition: FunDef)
    case Class(definition: ClassDef)
    case Interface(definition: InterfaceDef)

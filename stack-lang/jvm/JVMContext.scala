package jvm

import ast.Positions.Source

import sast.*
import sast.Trees.*
import sast.Symbols.*
import sast.Types.*

import java.nio.file.Paths
import scala.collection.mutable

/** Program-wide JVM naming, definition indexing, and source-file buckets.
  *
  * Indexing creates one empty bucket for every SAST file unit. Reachability
  * fills those buckets with definitions, without compiling a method body.
  * Bytecode generation only starts after that fixed point is complete.
  */
final class JVMContext(rewire: Map[Symbol, Symbol], javaInternalName: Symbol => Option[String])(using defn: Definitions):
  import JVMContext.*

  private val funDefs = mutable.Map.empty[Symbol, FunDef]
  private val classDefs = mutable.Map.empty[Symbol, ClassDef]
  private val interfaceDefs = mutable.Map.empty[Symbol, InterfaceDef]
  private val definitionBuckets = mutable.Map.empty[Symbol, Bucket]
  private val bucketsByClass = mutable.LinkedHashMap.empty[String, Bucket]

  private val topLevelLocations = mutable.Map.empty[Symbol, MethodLocation]
  private val classNames = mutable.Map.empty[Symbol, String]
  private val usedClassNames = mutable.Set[String]("Main", "Node", "Lambda")

  def index(units: List[FileUnit]): Unit =
    units.foreach: unit =>
      val bucket = createBucket(unit)
      indexDefs(unit.defs, bucket, unit.source)

  /** Populate the already-created source buckets from completed liveness.
    * No expression or method lowering occurs here.
    */
  def populate(live: Set[Symbol]): Unit =
    live.toList.sortBy(raw => (resolve(raw).source.file, resolve(raw).span.start, resolve(raw).fullName)).foreach: raw =>
      val sym = resolve(raw)
      if sym.isFunction then
        funDefs.get(sym).foreach: fdef =>
          if sym.owner.isInterface then
            addInterface(sym.owner)
            // Deferred members live in the interface class file. A default
            // interface method uses the JVM backend's static-helper ABI and
            // therefore also belongs in its source bucket.
            if !sym.is(Flags.Defer) then
              val bucket = definitionBuckets(sym)
              if !bucket.methods.exists(_.symbol == sym) then bucket.methods += fdef
              allocateTopLevel(sym, bucket)
          else if sym.owner.isClass then addClass(sym.owner)
          else
            val bucket = definitionBuckets(sym)
            if !bucket.methods.exists(_.symbol == sym) then bucket.methods += fdef
            allocateTopLevel(sym, bucket)
            addSignatureTypes(fdef.symbol.tpe.asProcType)
      else if sym.isClass then addClass(sym)
      else if sym.isInterface then addInterface(sym)

  def resolve(sym: Symbol): Symbol = rewire.getOrElse(sym, sym)

  /** The Jo source a definition's bytecode is attributed to.
    *
    * This is the bucket's source, not the symbol's own: it is the file the
    * enclosing class file names in its `SourceFile` attribute, and a
    * `LineNumberTable` entry is only meaningful relative to that file. A
    * symbol reached through a `@link` rewiring, or one with no bucket at
    * all, falls back to its own source.
    */
  def sourceOf(sym: Symbol): Source =
    val resolved = resolve(sym)
    definitionBuckets.get(resolved).map(_.source).getOrElse(resolved.source)

  def topLevelLocation(sym: Symbol): MethodLocation =
    val resolved = resolve(sym)
    topLevelLocations.getOrElse(resolved, allocateTopLevel(resolved, definitionBuckets(resolved)))

  def requireClass(sym: Symbol): String = className(sym)

  def className(sym: Symbol): String =
    // A reflected Java class already has a name, and no bucket to allocate one
    // from. This is reached for the `interfaces` table of a Jo class that
    // implements a Java interface; everywhere else an external class is erased
    // to `Object` before it gets here (see `JVMTypes.representationOf`).
    javaInternalName(sym).getOrElse(classNames.getOrElseUpdate(sym, {
      val bucket = definitionBuckets(sym)
      allocateClassName(bucket.className + "$" + sanitize(sym.name))
    }))

  def buckets: List[Bucket] = bucketsByClass.values.toList
  def classDef(sym: Symbol): ClassDef = classDefs(sym)
  def interfaceDef(sym: Symbol): InterfaceDef = interfaceDefs(sym)

  private def addClass(sym: Symbol): Unit =
    classDefs.get(sym).foreach: original =>
      val bucket = definitionBuckets(sym)
      if !bucket.classes.exists(_.symbol == sym) then
        // A JVM class is an atomic output unit. Keep constructors and
        // synthesized erasure/interface bridges even when no SAST call names
        // them directly; backend-only allocation and JVM dispatch need them.
        bucket.classes += original
        className(sym)
        original.vals.foreach(field => addTypeDependency(field.tpt.tpe))
        original.funs.foreach(fdef => addSignatureTypes(fdef.symbol.tpe.asProcType))
        // Every name in the class-file `interfaces` table must itself be
        // emitted, even when no call was made through that interface type.
        original.views
          .flatMap(view => JVMTypes.classOrInterfaceSymbol(view.tpe))
          .foreach(addInterface)

  private def addInterface(sym: Symbol): Unit =
    interfaceDefs.get(sym).foreach: original =>
      val bucket = definitionBuckets(sym)
      if !bucket.interfaces.exists(_.symbol == sym) then
        // As with classes, the interface declaration is emitted atomically.
        // Default bodies are still separate static helpers and enter the
        // bucket individually through the function branch in `populate`.
        bucket.interfaces += original
        original.methods.foreach(fdef => addSignatureTypes(fdef.symbol.tpe.asProcType))
        className(sym)

  private def addSignatureTypes(procType: ProcType): Unit =
    (procType.paramTypes ++ procType.autoTypes :+ procType.resultType).foreach(addTypeDependency)

  private def addTypeDependency(tpe: Type): Unit =
    JVMTypes.representationOf(tpe) match
      case JVMTypes.Representation.Class(sym) =>
        if sym.isInterface then addInterface(sym)
        else if sym.isClass then addClass(sym)
      case _ =>

  private def indexDefs(defs: List[Def], bucket: Bucket, source: Source): Unit =
    defs.foreach {
      case fdef: FunDef =>
        funDefs(fdef.symbol) = fdef
        definitionBuckets(fdef.symbol) = bucket
      case cdef: ClassDef =>
        classDefs(cdef.symbol) = cdef
        definitionBuckets(cdef.symbol) = bucket
        indexDefs(cdef.funs, bucket, source)
      case idef: InterfaceDef =>
        interfaceDefs(idef.symbol) = idef
        definitionBuckets(idef.symbol) = bucket
        indexDefs(idef.methods, bucket, source)
      case section: Section =>
        val sectionBucket = createSectionBucket(section, source)
        indexDefs(section.defs, sectionBucket, source)
      case _ =>
    }

  private def createBucket(unit: FileUnit): Bucket =
    val namespace = namespacePath(unit.owner)
    val sourceName = Paths.get(unit.source.file).getFileName.toString
    val dot = sourceName.lastIndexOf('.')
    val sourceStem = if dot > 0 then sourceName.substring(0, dot) else sourceName
    val requested = (namespace.split('/').filter(_.nonEmpty) :+ sanitize(sourceStem)).mkString("/")
    val className = allocateClassName(requested)
    val bucket = Bucket(unit.owner, unit.source, sourceName, className)
    bucketsByClass(className) = bucket
    bucket

  /** A section is a JVM static-owner boundary rather than a namespace-only
    * SAST wrapper. Its fully qualified Jo owner becomes the class name.
    * `allocateClassName` disambiguates repeated extension sections originating
    * in different files while keeping every bucket tied to one SourceFile.
    */
  private def createSectionBucket(section: Section, source: Source): Bucket =
    val sourceName = Paths.get(source.file).getFileName.toString
    val requested = section.symbol.fullName.split('.').iterator
      .filter(_.nonEmpty).map(sanitize).mkString("/")
    val className = allocateClassName(requested)
    val bucket = Bucket(section.symbol, source, sourceName, className)
    bucketsByClass(className) = bucket
    bucket

  private def namespacePath(owner: Symbol): String =
    owner.fullName.split('.').iterator.filter(_.nonEmpty).map(sanitize).mkString("/")

  private def allocateTopLevel(sym: Symbol, bucket: Bucket): MethodLocation =
    topLevelLocations.getOrElseUpdate(sym, {
      val base = sanitize(sym.name)
      var name = base
      var suffix = 0
      val used = topLevelLocations.valuesIterator.filter(_.owner == bucket.className).map(_.name).toSet
      while used.contains(name) do
        suffix += 1
        name = base + "_" + suffix
      MethodLocation(bucket.className, name)
    })

  private def allocateClassName(raw: String): String =
    var name = raw
    var suffix = 0
    while usedClassNames.contains(name) do
      suffix += 1
      name = raw + "_" + suffix
    usedClassNames += name
    name

  private def sanitize(s: String): String =
    val result = new StringBuilder
    s.foreach(c => if c.isLetterOrDigit || c == '_' || c == '$' then result += c else result += '_')
    if result.isEmpty || result.head.isDigit then result.insert(0, '_')
    result.toString

object JVMContext:
  final case class MethodLocation(owner: String, name: String)

  final case class Bucket(
    namespace: Symbol,
    source: Source,
    sourceName: String,
    className: String,
    methods: mutable.ArrayBuffer[FunDef] = mutable.ArrayBuffer.empty,
    classes: mutable.ArrayBuffer[ClassDef] = mutable.ArrayBuffer.empty,
    interfaces: mutable.ArrayBuffer[InterfaceDef] = mutable.ArrayBuffer.empty
  )

package jvm

import ast.Positions.{ Source, SourcePosition }

import sast.*
import sast.Denotations.ClassInfo
import sast.Symbols.*
import sast.Types.*

import reporting.Reporter

import java.lang.reflect.{ Executable, Field, Method, Modifier }

import scala.collection.mutable

/** The `jvm` namespace root: JDK (and `--java-lib`) classes reflected into Jo
  * symbols on demand.
  *
  * This is a third kind of lazy symbol source, alongside source files (`Namer`)
  * and pickled libraries (`pickle.Decoder`), and it needs no new resolution
  * mechanism: `jvm` is an ordinary root container whose `NameTable` happens to
  * materialize its members the first time they are looked up.
  *
  * `import jvm.java.io.File` walks `jvm` → `java` → `io` → `File`. Each segment
  * is resolved by asking the classpath for a class of that binary name: a hit
  * becomes a class, a miss becomes a package. `java.util.Map.Entry` works the
  * same way, because a materialized class leaves behind a container that is
  * both its statics namespace and the prefix for its nested classes (`$`).
  *
  * A class materializes into two symbols sharing one name, in the two universes
  * Jo keeps separate anyway (`NameTable`), so a single import brings both:
  *
  *   - a **type**, whose `ClassInfo` carries the instance methods, the
  *     constructor, instance-field getters, and — as `views` — Java's own
  *     direct supertypes, which is what makes Java subtyping hold in Jo;
  *   - a **container**, holding the `static` methods and fields.
  *
  * Every member records a [[JVMRuntime.NativeSpec]] here rather than compiling
  * to a Jo body: these are exactly the specs `@extern` bindings are hand-written
  * for in `runtime/jvm/Runtime.jo`, so `NativeCalls` lowers them with no new
  * bytecode machinery.
  *
  * Costs are kept lazy in the one place that matters: a member's *symbol* is
  * created eagerly (cheap — names and descriptors come straight from
  * reflection), but its Jo *type* is not. Resolving a Jo type reaches for other
  * Java classes, so forcing types eagerly would pull the transitive closure of
  * the JDK in behind the first import.
  */
final class JavaSymbols(classpath: JavaClasspath, enabled: Boolean)(using lazyDefn: Definitions.Lazy, rp: Reporter):
  import JavaSymbols.*

  private val specs = mutable.HashMap.empty[Symbol, JVMRuntime.NativeSpec]

  /** How to reach `sym`'s Java definition, if it has one. */
  def nativeSpec(sym: Symbol): Option[JVMRuntime.NativeSpec] = specs.get(sym)

  /** The `jvm` root container, to be defined into the compilation's root table. */
  val root: Symbol =
    val table = new PackageTable("", isClass = false, this)
    val sym = ContainerSymbol.create("jvm", table, Flags.NSpace | Flags.External, Visibility.Default, null, javaPos)
    table.owner = sym
    sym

  private var reportedDisabled = false

  /** Materialize `name` under `prefix` into `table`, if there is anything there.
    *
    * Called by [[PackageTable]] the first time a name is looked up.
    */
  private[jvm] def materialize(prefix: String, prefixIsClass: Boolean, name: String, owner: Symbol, table: NameTable): Unit =
    if !enabled then
      if !reportedDisabled then
        reportedDisabled = true
        Reporter.error(
          "Java interop is not enabled here, so the `jvm` namespace has no members. "
          + "Pass --enable-java-ffi (or set `enable-ffi = true` on the module) to allow it."
        )

    else if isJoIdentifier(name) then
      val binary =
        if prefix.isEmpty then name
        else if prefixIsClass then prefix + "$" + name
        else prefix + "." + name

      classpath.load(binary) match
        case Some(cls) => defineClass(cls, name, owner, table)
        // A miss under a package prefix is taken to be a deeper package, since
        // reflection offers no way to ask whether one exists — `jvm.java.io`
        // has to work before `jvm.java.io.File` can be looked up. Under a class
        // prefix there is no such doubt: its members are all known already, so
        // a miss is simply a name that is not there.
        case None if !prefixIsClass => definePackage(binary, name, owner, table)
        case None => ()

  /** Reflection cannot list what is in a package or on a classpath, so there is
    * nothing for `import jvm.java.io.*` to expand to. Saying so beats importing
    * nothing and letting every later use fail as an unknown name.
    */
  private[jvm] def reportNoEnumeration(prefix: String): Unit =
    val what = if prefix.isEmpty then "the `jvm` root" else "`" + prefix + "`"
    Reporter.error(s"A wildcard import cannot be used for $what: Java names have to be imported one by one.")

  private def definePackage(binary: String, name: String, owner: Symbol, table: NameTable): Unit =
    val sub = new PackageTable(binary, isClass = false, this)
    val sym = ContainerSymbol.create(name, sub, Flags.NSpace | Flags.External, Visibility.Default, owner, javaPos)
    sub.owner = sym
    table.define(sym)

  //----------------------------------------------------------------------------
  // Classes
  //----------------------------------------------------------------------------

  private def defineClass(cls: Class[?], name: String, owner: Symbol, table: NameTable): Unit =
    // The statics container doubles as the namespace nested classes live in,
    // which is why it is created even for a class with no static members.
    val staticTable = new PackageTable(cls.getName, isClass = true, this)
    val container = ContainerSymbol.create(name, staticTable, Flags.Section | Flags.External, Visibility.Default, owner, javaPos)
    staticTable.owner = container
    table.define(container)

    val classFlags = Flags.External | (if cls.isInterface then Flags.Interface else Flags.Class)
    val classSym = TypeSymbol.create(Kind.Simple, name, classFlags, Visibility.Default, owner, javaPos)
    table.define(classSym)
    index.addLazy(classSym, () => classInfoOf(cls, classSym))

    defineStatics(cls, container, staticTable)

  /** Java's own direct supertypes, minus `Object`.
    *
    * `Object` is left out on purpose: it is mapped to Jo's `Any` in signatures
    * (so any Jo value can cross an `Object`-typed boundary), and `Any` is
    * already every type's supertype, so naming it as a view would add nothing.
    */
  private def viewsOf(cls: Class[?]): List[Type] =
    val supers = Option(cls.getSuperclass).toList ++ cls.getInterfaces.toList
    supers.filter(sup => sup != classOf[Object] && Modifier.isPublic(sup.getModifiers))
      .flatMap(sup => typeSymbolFor(sup).map(StaticRef(_)))

  private def classInfoOf(cls: Class[?], classSym: Symbol): ClassInfo =
    val self = TermSymbol.create("this", Flags.Synthetic, Visibility.Default, classSym, javaPos)
    ClassInfo(classSym, tparams = Nil, self = self, fields = Nil, methods = instanceMembers(cls, classSym), views = viewsOf(cls))

  //----------------------------------------------------------------------------
  // Members
  //----------------------------------------------------------------------------

  /** Instance methods, instance-field getters, and constructors.
    *
    * Only members *declared* by `cls` are listed. Inherited ones are reached
    * through `views` by `ClassInfo.getMemberSymbol`, which keeps a class's
    * `ClassInfo` the size of its own API rather than of its whole hierarchy.
    */
  private def instanceMembers(cls: Class[?], classSym: Symbol): List[Symbol] =
    val owner = internalName(cls)
    val kind = if cls.isInterface then "interface" else "virtual"

    val methods = declaredMethods(cls).filterNot(m => Modifier.isStatic(m.getModifiers))
    val fields = declaredFields(cls).filterNot(f => Modifier.isStatic(f.getModifiers))
    val constructors = cls.getDeclaredConstructors.toList.filter(c => Modifier.isPublic(c.getModifiers))

    val taken = mutable.Set.empty[String]
    val members = mutable.ArrayBuffer.empty[Symbol]

    for (method, joName) <- withJoNames(methods, taken) do
      val sym = TermSymbol.create(joName, Flags.Fun | Flags.Method | Flags.External, Visibility.Default, classSym, javaPos)
      index.addLazy(sym, () => procTypeOf(method, method.getParameterTypes, method.getReturnType))
      specs(sym) = JVMRuntime.NativeSpec(owner, method.getName, methodDescriptor(method.getParameterTypes, method.getReturnType), kind)
      members += sym

    for field <- fields if taken.add(field.getName) do
      val sym = TermSymbol.create(field.getName, Flags.Fun | Flags.Method | Flags.External, Visibility.Default, classSym, javaPos)
      index.addLazy(sym, () => procTypeOf(field, Array.empty[Class[?]], field.getType))
      specs(sym) = JVMRuntime.NativeSpec(owner, field.getName, field.getType.descriptorString, "getfield")
      members += sym

    // Jo models a class as having exactly one constructor, so the primary one
    // takes `<init>` (what `Namer` looks up for `File(path)`) and the rest are
    // reached as factory functions on the companion container.
    constructors.sortBy(sortKey).headOption.foreach: ctor =>
      val sym = TermSymbol.create(Names.Constructor, Flags.Fun | Flags.Method | Flags.Constructor | Flags.External, Visibility.Default, classSym, javaPos)
      index.addLazy(sym, () => procTypeOf(ctor, ctor.getParameterTypes, cls))
      specs(sym) = JVMRuntime.NativeSpec(owner, Names.Constructor, methodDescriptor(ctor.getParameterTypes, Void.TYPE), "special")
      members += sym

    members.toList

  private def defineStatics(cls: Class[?], container: Symbol, table: PackageTable): Unit =
    val owner = internalName(cls)
    val methods = declaredMethods(cls).filter(m => Modifier.isStatic(m.getModifiers))
    val fields = declaredFields(cls).filter(f => Modifier.isStatic(f.getModifiers))
    val taken = mutable.Set.empty[String]

    def define(sym: Symbol): Unit =
      // A name defined here must not later be mistaken for a nested class.
      table.claim(sym.name)
      table.define(sym)

    for (method, joName) <- withJoNames(methods, taken) do
      val sym = TermSymbol.create(joName, Flags.Fun | Flags.External, Visibility.Default, container, javaPos)
      index.addLazy(sym, () => procTypeOf(method, method.getParameterTypes, method.getReturnType))
      specs(sym) = JVMRuntime.NativeSpec(owner, method.getName, methodDescriptor(method.getParameterTypes, method.getReturnType), "static")
      define(sym)

    for field <- fields if taken.add(field.getName) do
      val sym = TermSymbol.create(field.getName, Flags.Fun | Flags.External, Visibility.Default, container, javaPos)
      index.addLazy(sym, () => procTypeOf(field, Array.empty[Class[?]], field.getType))
      specs(sym) = JVMRuntime.NativeSpec(owner, field.getName, field.getType.descriptorString, "getstatic")
      define(sym)

    // Secondary constructors, which `ClassInfo` has no room for.
    val constructors = cls.getDeclaredConstructors.toList.filter(c => Modifier.isPublic(c.getModifiers)).sortBy(sortKey)
    for ctor <- constructors.drop(1) do
      val joName = uniqueName("make_" + overloadSuffix(ctor.getParameterTypes), taken)
      val sym = TermSymbol.create(joName, Flags.Fun | Flags.External, Visibility.Default, container, javaPos)
      index.addLazy(sym, () => procTypeOf(ctor, ctor.getParameterTypes, cls))
      specs(sym) = JVMRuntime.NativeSpec(owner, Names.Constructor, methodDescriptor(ctor.getParameterTypes, Void.TYPE), "special")
      define(sym)

  private def declaredMethods(cls: Class[?]): List[Method] =
    cls.getDeclaredMethods.toList
      .filter(m => Modifier.isPublic(m.getModifiers) && !m.isSynthetic && !m.isBridge)
      .filter(m => isJoIdentifier(m.getName))

  private def declaredFields(cls: Class[?]): List[Field] =
    cls.getDeclaredFields.toList
      .filter(f => Modifier.isPublic(f.getModifiers) && !f.isSynthetic)
      .filter(f => isJoIdentifier(f.getName))

  /** Pair each method with the Jo name it gets, mangling all but one overload.
    *
    * Jo has no overloading and the JDK overloads constantly, so the alternative
    * to mangling is silently dropping every overload but one — lossy in a way a
    * general-purpose FFI should not be.
    */
  private def withJoNames(methods: List[Method], taken: mutable.Set[String]): List[(Method, String)] =
    val byName = methods.groupBy(_.getName)
    for
      name <- byName.keys.toList.sorted
      (method, i) <- byName(name).sortBy(sortKey).zipWithIndex
    yield
      val base = if i == 0 then name else name + "_" + overloadSuffix(method.getParameterTypes)
      (method, uniqueName(base, taken))

  private def uniqueName(base: String, taken: mutable.Set[String]): String =
    if taken.add(base) then base
    else
      var i = 2
      while !taken.add(base + "_" + i) do i += 1
      base + "_" + i

  //----------------------------------------------------------------------------
  // Types
  //----------------------------------------------------------------------------

  private def index: SymbolIndex = lazyDefn.index

  private def procTypeOf(member: Executable | Field, params: Array[Class[?]], result: Class[?]): Denotations.Denotation =
    given defn: Definitions = lazyDefn.value
    val paramInfos = params.toList.zipWithIndex.map: (cls, i) =>
      NamedInfo("p" + i, joType(cls, member, "parameter"))
    ProcType(
      tparams = Nil,
      params = paramInfos,
      autos = Nil,
      candidates = Nil,
      resultType = joType(result, member, "result"),
      receivesInfo = Nil,
      preParamCount = 0,
      preTypeParamCount = 0
    )()

  /** The Jo type a Java type crosses the FFI boundary as.
    *
    * `double` and `short` have no representation in this backend (see
    * `JVMTypes`), so a member using one is left with an error type: the member
    * still exists and still shows up in error messages, rather than silently
    * disappearing from the class or, worse, being widened to something the
    * verifier will reject.
    */
  private def joType(cls: Class[?], member: Executable | Field, role: String)(using defn: Definitions): Type =
    val name = member match
      case e: Executable => e.getDeclaringClass.getName + "." + e.getName
      case f: Field      => f.getDeclaringClass.getName + "." + f.getName

    knownType(cls) match
      case Some(tp) => tp
      case None =>
        if cls.isPrimitive then
          Reporter.error(s"Java $role type `${cls.getName}` of $name has no Jo representation")
          ErrorType
        else if cls.isArray then
          Reporter.error(s"Java $role type `${cls.getName}` of $name is an array, which the Jo FFI does not support yet")
          ErrorType
        else
          typeSymbolFor(cls) match
            case Some(sym) => StaticRef(sym)
            case None =>
              Reporter.error(s"Java $role type `${cls.getName}` of $name is not accessible")
              ErrorType

  private def knownType(cls: Class[?])(using defn: Definitions): Option[Type] =
    if cls == classOf[Object] then Some(AnyType)
    else if cls == classOf[String] then Some(defn.StringType)
    else if !cls.isPrimitive then None
    else cls.getName match
      case "int"     => Some(defn.IntType)
      case "boolean" => Some(defn.BoolType)
      case "byte"    => Some(defn.ByteType)
      case "char"    => Some(defn.CharType)
      case "long"    => Some(defn.LongType)
      case "float"   => Some(defn.FloatType)
      case "void"    => Some(defn.UnitType)
      case _         => None

  /** The Jo type symbol for `cls`, resolved through the `jvm` root so that every
    * mention of a class shares one symbol (and one materialization).
    */
  private def typeSymbolFor(cls: Class[?]): Option[Symbol] =
    val pkg = cls.getPackageName
    val binary = cls.getName
    val nested = (if pkg.isEmpty then binary else binary.substring(pkg.length + 1)).split('$').toList

    if nested.exists(part => !isJoIdentifier(part)) then None
    else
      val segments = (if pkg.isEmpty then Nil else pkg.split('.').toList) ++ nested.init
      var table: Option[NameTable] = Some(root.nameTable)

      for segment <- segments do
        table = table.flatMap(_.resolveContainer(segment)).map(_.nameTable)

      table.flatMap(_.resolveType(nested.last))

end JavaSymbols

object JavaSymbols:
  /** A name table that fills itself in from the classpath on first lookup.
    *
    * `prefix` is the binary name of the package or class this table belongs to;
    * `isClass` selects the `$` separator Java uses for nested classes.
    */
  final class PackageTable(prefix: String, isClass: Boolean, java: JavaSymbols) extends NameTable():
    private[jvm] var owner: Symbol = null
    private val tried = mutable.Set.empty[String]

    /** Record that `name` is already accounted for, so lookup skips reflection. */
    private[jvm] def claim(name: String): Unit = tried += name

    private def materialize(name: String): Unit =
      if tried.add(name) then java.materialize(prefix, isClass, name, owner, this)

    override def resolveTerm(name: String): Option[Symbol] =
      materialize(name); super.resolveTerm(name)

    override def resolveAnnotation(name: String): Option[Symbol] =
      materialize(name); super.resolveAnnotation(name)

    override def resolveType(name: String): Option[Symbol] =
      materialize(name); super.resolveType(name)

    override def resolvePattern(name: String): Option[Symbol] =
      materialize(name); super.resolvePattern(name)

    override def resolveContainer(name: String): Option[Symbol] =
      materialize(name); super.resolveContainer(name)

    override def resolve(name: String): List[Symbol] =
      materialize(name); super.resolve(name)

    // Enumeration is what a wildcard import asks for, and the one thing this
    // table cannot answer.
    override def terms: List[Symbol]      = enumerated(super.terms)
    override def types: List[Symbol]      = enumerated(super.types)
    override def patterns: List[Symbol]   = enumerated(super.patterns)
    override def containers: List[Symbol] = enumerated(super.containers)

    private var reportedEnumeration = false

    private def enumerated(known: List[Symbol]): List[Symbol] =
      if !reportedEnumeration then
        reportedEnumeration = true
        java.reportNoEnumeration(prefix)
      known
  end PackageTable

  private val javaSource = new Source("<java>", mutable.ArrayBuffer(0), mutable.Map(0 -> ""))

  /** The position reported for a symbol that came from a class file, not source. */
  val javaPos: SourcePosition = SourcePosition(javaSource, 0, 0)

  def internalName(cls: Class[?]): String = cls.getName.replace('.', '/')

  def methodDescriptor(params: Array[Class[?]], result: Class[?]): String =
    params.map(_.descriptorString).mkString("(", "", ")")
      + result.descriptorString

  /** Deterministic ordering within an overload set, best candidate first.
    *
    * "Best" is what a Jo caller most likely means, because that overload is the
    * one that keeps the plain Java name; the rest get mangled. Ranking by
    * descriptor alone would be deterministic but perverse — `(DD)D` sorts ahead
    * of `(II)I`, so plain `Math.max` would be the `double` overload, which this
    * backend cannot even represent. So types are scored first: exact Jo
    * counterparts before widenings, and anything unrepresentable last.
    *
    * The descriptor still breaks ties, so names never move between compilations
    * of the same classpath.
    */
  def sortKey(member: Executable): (Int, Int, String) =
    val types = member.getParameterTypes
    val score = types.map(typeScore).sum
    (score, types.length, types.map(_.descriptorString).mkString)

  private def typeScore(cls: Class[?]): Int =
    if !cls.isPrimitive then (if cls == classOf[String] then 0 else 1)
    else cls.getName match
      case "int" | "boolean" | "byte" | "char" => 0
      case "long"  => 2
      case "float" => 4
      // `double` and `short` have no representation in this backend at all.
      case _       => 100

  def overloadSuffix(params: Array[Class[?]]): String =
    if params.isEmpty then "0" else params.map(simpleName).mkString("_")

  private def simpleName(cls: Class[?]): String =
    if cls.isArray then simpleName(cls.getComponentType) + "Array"
    else
      val name = cls.getSimpleName
      val sanitized = name.map(c => if ast.Naming.isNameRest(c) then c else '_')
      if sanitized.isEmpty || !ast.Naming.isNameStart(sanitized.head) then "_" + sanitized else sanitized

  def isJoIdentifier(name: String): Boolean =
    name.nonEmpty && ast.Naming.isNameStart(name.head) && name.forall(ast.Naming.isNameRest)
end JavaSymbols

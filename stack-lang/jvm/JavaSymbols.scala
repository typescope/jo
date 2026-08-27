package jvm

import ast.Positions.{ Source, SourcePosition }

import sast.*
import sast.Denotations.ClassInfo
import sast.Symbols.*
import sast.Types.*

import reporting.Reporter

import java.lang.reflect.{
  Constructor, Executable, Field, GenericArrayType, Method, Modifier,
  ParameterizedType, TypeVariable, WildcardType, Type => JavaType
}

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
  *
  * Generic signatures are read rather than erased, and arrays become
  * `jvm.Array[T]` — a distinct type from `jo.Array`, see `defineArraySupport`.
  * A Java interface's abstract methods are marked `Flags.Defer`, which is all
  * it takes for Jo's own lambda-interface conversion to apply to it.
  */
final class JavaSymbols(classpath: JavaClasspath, enabled: Boolean)(using lazyDefn: Definitions.Lazy, rp: Reporter):
  import JavaSymbols.*

  private val specs = mutable.HashMap.empty[Symbol, JVMRuntime.NativeSpec]

  /** A reflected class's Jo type parameters, in declaration order.
    *
    * Held here rather than read back out of `ClassInfo` because every member's
    * signature needs them while that very `ClassInfo` is still being computed.
    */
  private val classTparams = mutable.HashMap.empty[Symbol, List[Symbol]]

  // Declared before `val root`, whose initializer fills them in: a field
  // declaration further down the class body would run afterwards and reset
  // them to their defaults.
  private var arrayClassSym: Symbol = null
  private var arrayOpSyms: Map[String, Symbol] = Map.empty

  /** The JVM internal name of each reflected class, for the one place the
    * backend has to name a Java class rather than erase it to `Object`: the
    * `interfaces` table of a Jo class that implements a Java interface.
    */
  private val classInternalNames = mutable.HashMap.empty[Symbol, String]

  /** How to reach `sym`'s Java definition, if it has one. */
  def nativeSpec(sym: Symbol): Option[JVMRuntime.NativeSpec] = specs.get(sym)

  /** `sym`'s JVM internal name, if it is a reflected Java class or interface. */
  def internalNameOf(sym: Symbol): Option[String] = classInternalNames.get(sym)

  /** The `jvm` root container, to be defined into the compilation's root table. */
  val root: Symbol =
    val table = new PackageTable("", isClass = false, this)
    val sym = ContainerSymbol.create("jvm", table, Flags.NSpace | Flags.External, Visibility.Default, null, javaPos)
    table.owner = sym
    defineArraySupport(table, sym)
    sym

  //----------------------------------------------------------------------------
  // jvm.Array[T]
  //----------------------------------------------------------------------------

  /** `jvm.Array[T]` — a real JVM array, deliberately *not* `jo.Array[T]`.
    *
    * JVM arrays are reified and covariant: a `String[]` carries its element type
    * at runtime, and the `Object[]` a `jo.Array[String]` compiles to is not one.
    * Conflating them would read fine and then fail the verifier's cast the first
    * time an array went back to Java, so they stay separate types even though
    * both are read with the same opcodes.
    *
    * Element access uses the `Object[]` instructions, which is sound in the
    * direction that matters: every reference array *is* an `Object[]` by Java's
    * own covariance, and storing the wrong element type raises Java's own
    * `ArrayStoreException` rather than corrupting anything.
    */
  /** The `jvm.Array[T]` element operations, by name (`size`, `get`, `set`). */
  def arrayOp(name: String): Symbol = arrayOpSyms(name)

  private def defineArraySupport(table: PackageTable, owner: Symbol): Unit =
    val classSym = TypeSymbol.create(Kind.simpleKinded(1), "Array", Flags.Class | Flags.External, Visibility.Default, owner, javaPos)
    val elem = defineTparam("T", classSym)
    classTparams(classSym) = elem :: Nil
    index.addLazy(classSym, () => ClassInfo(
      classSym,
      tparams = elem :: Nil,
      self = TermSymbol.create("this", Flags.Synthetic, Visibility.Default, classSym, javaPos),
      fields = Nil,
      methods = Nil,
      views = Nil
    ))
    arrayClassSym = classSym

    val opsTable = new NameTable
    val container = ContainerSymbol.create("Array", opsTable, Flags.Section | Flags.External, Visibility.Default, owner, javaPos)

    // `Array` is a name this table answers itself; without claiming it, the
    // lookup would go to the classpath and invent a package called `Array`.
    table.claim("Array")
    table.define(classSym)
    table.define(container)

    def op(name: String, paramsOf: (Definitions, Type) => List[Type], resultOf: (Definitions, Type) => Type): Symbol =
      val sym = TermSymbol.create(name, Flags.Fun | Flags.External, Visibility.Default, container, javaPos)
      val tparam = defineTparam("T", sym)
      index.addLazy(sym, () =>
        given defn: Definitions = lazyDefn.value
        val elemType = StaticRef(tparam)
        ProcType(
          tparams = tparam :: Nil,
          params = paramsOf(defn, elemType).zipWithIndex.map((tp, i) => NamedInfo("p" + i, tp)),
          autos = Nil,
          candidates = Nil,
          resultType = resultOf(defn, elemType),
          receivesInfo = Nil,
          preParamCount = 0,
          preTypeParamCount = 0
        )()
      )
      opsTable.define(sym)
      sym

    def arrayOf(elemType: Type): Type = AppliedType(classSym, elemType :: Nil)

    arrayOpSyms = Map(
      "size" -> op("size", (_, t) => arrayOf(t) :: Nil, (defn, _) => defn.IntType),
      "get"  -> op("get",  (defn, t) => arrayOf(t) :: defn.IntType :: Nil, (_, t) => t),
      "set"  -> op("set",  (defn, t) => arrayOf(t) :: defn.IntType :: t :: Nil, (defn, _) => defn.UnitType)
    )

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
    val tparams = cls.getTypeParameters
    val classSym = TypeSymbol.create(Kind.simpleKinded(tparams.length), name, classFlags, Visibility.Default, owner, javaPos)
    table.define(classSym)
    // Eagerly, and before the `ClassInfo` that reports them: `ArrayList`'s `E`
    // has to be one symbol shared by the class, its supertypes' `views`, and
    // every member mentioning it, or substitution at a use site would miss.
    classTparams(classSym) = tparams.toList.map(tparam => defineTparam(tparam.getName, classSym))
    classInternalNames(classSym) = internalName(cls)
    index.addLazy(classSym, () => classInfoOf(cls, classSym))

    defineStatics(cls, classSym, container, staticTable)

  /** Java's own direct supertypes, minus `Object`.
    *
    * `Object` is left out on purpose: it is mapped to Jo's `Any` in signatures
    * (so any Jo value can cross an `Object`-typed boundary), and `Any` is
    * already every type's supertype, so naming it as a view would add nothing.
    */
  private def viewsOf(cls: Class[?], classSym: Symbol)(using Definitions): List[Type] =
    val supers = Option(cls.getGenericSuperclass).toList ++ cls.getGenericInterfaces.toList
    val env = tparamEnv(classTparams.getOrElse(classSym, Nil))
    supers.filter(sup => rawClassOf(sup).exists(c => c != classOf[Object] && Modifier.isPublic(c.getModifiers)))
      .map(sup => joType(sup, env, () => "supertype of " + cls.getName))
      .filterNot(_ == ErrorType)

  private def classInfoOf(cls: Class[?], classSym: Symbol): ClassInfo =
    given defn: Definitions = lazyDefn.value
    val self = TermSymbol.create("this", Flags.Synthetic, Visibility.Default, classSym, javaPos)
    ClassInfo(
      classSym,
      tparams = classTparams.getOrElse(classSym, Nil),
      self = self,
      fields = Nil,
      methods = instanceMembers(cls, classSym),
      views = viewsOf(cls, classSym)
    )

  private def defineTparam(name: String, owner: Symbol): Symbol =
    val sym = TypeSymbol.create(Kind.Simple, name, Flags.Param | Flags.External, Visibility.Default, owner, javaPos)
    // Java bounds are dropped: they exist to constrain call sites the Jo side
    // is not checking anyway, and an F-bounded one (`E extends Enum<E>`) would
    // make the symbol's own info cyclic.
    index.add(sym, AnyType)
    sym

  private def tparamEnv(tparams: List[Symbol]): Map[String, Type] =
    tparams.map(tparam => tparam.name -> StaticRef(tparam)).toMap

  private def rawClassOf(t: JavaType): Option[Class[?]] = t match
    case cls: Class[?] => Some(cls)
    case pt: ParameterizedType =>
      pt.getRawType match
        case raw: Class[?] => Some(raw)
        case _ => None
    case _ => None

  /** Does `m` merely redeclare something `java.lang.Object` already provides?
    *
    * Such a method must not count as deferred. `Comparator` declares an
    * abstract `equals(Object)` and is still a single-abstract-method interface
    * — which is exactly why Java lets you write a lambda for it — so counting
    * it would cost every such interface its lambda conversion.
    */
  private def overridesObjectMethod(m: Method): Boolean =
    try Modifier.isPublic(classOf[Object].getMethod(m.getName, m.getParameterTypes*).getModifiers)
    catch case _: NoSuchMethodException => false

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
    val tparams = classTparams.getOrElse(classSym, Nil)

    for (method, joName) <- withJoNames(methods, taken) do
      // An abstract Java method is a deferred Jo method, which is what makes a
      // Java single-abstract-method interface a Jo *lambda interface*: the
      // frontend then converts a lambda written in argument position into a
      // class that really implements it (`Adaptation`, `ElimCapture`,
      // `Types.getLambdaInterfaceMethod`), with no bridging of this backend's
      // own devising.
      val deferred =
        if Modifier.isAbstract(method.getModifiers) && !overridesObjectMethod(method) then Flags.Defer
        else Flags.empty
      val sym = TermSymbol.create(joName, Flags.Fun | Flags.Method | Flags.External | deferred, Visibility.Default, classSym, javaPos)
      index.addLazy(sym, () => procTypeOf(method, sym, tparams))
      specs(sym) = JVMRuntime.NativeSpec(owner, method.getName, methodDescriptor(method.getParameterTypes, method.getReturnType), kind)
      members += sym

    for field <- fields if taken.add(field.getName) do
      val sym = TermSymbol.create(field.getName, Flags.Fun | Flags.Method | Flags.External, Visibility.Default, classSym, javaPos)
      index.addLazy(sym, () => procTypeOf(field, sym, tparams))
      specs(sym) = JVMRuntime.NativeSpec(owner, field.getName, field.getType.descriptorString, "getfield")
      members += sym

    // Jo models a class as having exactly one constructor, so the primary one
    // takes `<init>` (what `Namer` looks up for `File(path)`) and the rest are
    // reached as factory functions on the companion container.
    constructors.sortBy(sortKey).headOption.foreach: ctor =>
      val sym = TermSymbol.create(Names.Constructor, Flags.Fun | Flags.Method | Flags.Constructor | Flags.External, Visibility.Default, classSym, javaPos)
      index.addLazy(sym, () => procTypeOf(ctor, sym, tparams))
      specs(sym) = JVMRuntime.NativeSpec(owner, Names.Constructor, methodDescriptor(ctor.getParameterTypes, Void.TYPE), "special")
      members += sym

    members.toList

  private def defineStatics(cls: Class[?], classSym: Symbol, container: Symbol, table: PackageTable): Unit =
    val owner = internalName(cls)
    // A `static` member cannot mention the class's own type parameters, but a
    // secondary constructor surfaced here still returns the applied class.
    val tparams = classTparams.getOrElse(classSym, Nil)
    val methods = declaredMethods(cls).filter(m => Modifier.isStatic(m.getModifiers))
    val fields = declaredFields(cls).filter(f => Modifier.isStatic(f.getModifiers))
    val taken = mutable.Set.empty[String]

    def define(sym: Symbol): Unit =
      // A name defined here must not later be mistaken for a nested class.
      table.claim(sym.name)
      table.define(sym)

    for (method, joName) <- withJoNames(methods, taken) do
      val sym = TermSymbol.create(joName, Flags.Fun | Flags.External, Visibility.Default, container, javaPos)
      index.addLazy(sym, () => procTypeOf(method, sym, Nil))
      specs(sym) = JVMRuntime.NativeSpec(owner, method.getName, methodDescriptor(method.getParameterTypes, method.getReturnType), "static")
      define(sym)

    for field <- fields if taken.add(field.getName) do
      val sym = TermSymbol.create(field.getName, Flags.Fun | Flags.External, Visibility.Default, container, javaPos)
      index.addLazy(sym, () => procTypeOf(field, sym, Nil))
      specs(sym) = JVMRuntime.NativeSpec(owner, field.getName, field.getType.descriptorString, "getstatic")
      define(sym)

    // Secondary constructors, which `ClassInfo` has no room for.
    val constructors = cls.getDeclaredConstructors.toList.filter(c => Modifier.isPublic(c.getModifiers)).sortBy(sortKey)
    for ctor <- constructors.drop(1) do
      val joName = uniqueName("make_" + overloadSuffix(ctor.getParameterTypes), taken)
      val sym = TermSymbol.create(joName, Flags.Fun | Flags.External, Visibility.Default, container, javaPos)
      index.addLazy(sym, () => procTypeOf(ctor, sym, tparams))
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

  /** The Jo signature of a reflected member.
    *
    * Generic signatures are read, not erased: `ArrayList[String].add` takes a
    * Jo `String` even though its descriptor says `(Ljava/lang/Object;)Z`. The
    * two never disagree at the bytecode level, because a JVM descriptor is
    * erased to begin with and `NativeCalls` casts at the boundary — which is
    * the same reason `compileStaticCall` builds descriptors from a declaring
    * symbol rather than from a call site's instantiation.
    */
  private def procTypeOf(member: Executable | Field, sym: Symbol, ownerTparams: List[Symbol]): Denotations.Denotation =
    given defn: Definitions = lazyDefn.value

    def what() = member match
      case e: Executable => e.getDeclaringClass.getName + "." + e.getName
      case f: Field      => f.getDeclaringClass.getName + "." + f.getName

    // A constructor's own type parameters are dropped rather than surfaced:
    // `Namer` requires a constructor's `ProcType` to have none, and a generic
    // constructor is vanishingly rare next to a generic class.
    val methodTparams = member match
      case m: Method => m.getTypeParameters.toList.map(tparam => defineTparam(tparam.getName, sym))
      case _         => Nil

    val env = tparamEnv(ownerTparams) ++ tparamEnv(methodTparams)

    val paramTypes = member match
      case e: Executable => e.getGenericParameterTypes.toList
      case _: Field      => Nil

    val resultType = member match
      case m: Method         => joType(m.getGenericReturnType, env, what)
      // A constructor returns the class applied to its own parameters, so
      // `new ArrayList[String]` carries `String` through to every member.
      case c: Constructor[?] => selfType(c.getDeclaringClass)
      case f: Field          => joType(f.getGenericType, env, what)
      case other             => throw new Exception("Unexpected Java member: " + other)

    ProcType(
      tparams = methodTparams,
      params = paramTypes.zipWithIndex.map((t, i) => NamedInfo("p" + i, joType(t, env, what))),
      autos = Nil,
      candidates = Nil,
      resultType = resultType,
      receivesInfo = Nil,
      preParamCount = 0,
      preTypeParamCount = 0
    )()

  /** What a constructor returns.
    *
    * The class applied to *its own* type parameters, not to `Any` the way a raw
    * mention is: that is what lets `rebaseMemberType` substitute at the use
    * site, so `new ArrayList[String]` is an `ArrayList[String]` and its `add`
    * takes a `String`.
    */
  private def selfType(cls: Class[?]): Type =
    typeSymbolFor(cls) match
      case None => ErrorType
      case Some(sym) =>
        classTparams.getOrElse(sym, Nil) match
          case Nil     => StaticRef(sym)
          case tparams => AppliedType(sym, tparams.map(StaticRef(_)))

  /** A mention of `sym` with no type arguments written.
    *
    * A generic class still has to be applied to something to be well-kinded, so
    * a raw Java use becomes `C[Any, ...]` — which is what raw Java means.
    */
  private def rawRef(sym: Symbol): Type =
    classTparams.getOrElse(sym, Nil) match
      case Nil     => StaticRef(sym)
      case tparams => AppliedType(sym, tparams.map(_ => AnyType))

  /** The Jo type a Java type crosses the FFI boundary as.
    *
    * `double` and `short` have no representation in this backend (see
    * `JVMTypes`), so a member using one is left with an error type: the member
    * still exists and still shows up in error messages, rather than silently
    * disappearing from the class or, worse, being widened to something the
    * verifier will reject.
    */
  private def joType(t: JavaType, env: Map[String, Type], what: () => String)(using defn: Definitions): Type =
    t match
      case cls: Class[?] =>
        knownType(cls) match
          case Some(tp) => tp
          case None =>
            if cls.isPrimitive then
              Reporter.error(s"Java type `${cls.getName}` in ${what()} has no Jo representation")
              ErrorType
            else if cls.isArray then
              arrayTypeOf(cls.getComponentType, env, what)
            else
              typeSymbolFor(cls) match
                case Some(sym) => rawRef(sym)
                case None =>
                  Reporter.error(s"Java type `${cls.getName}` in ${what()} is not accessible")
                  ErrorType

      case tv: TypeVariable[?] =>
        // A type variable from a scope Jo does not model (a generic
        // constructor's own) degrades to `Any` rather than failing the member.
        env.getOrElse(tv.getName, AnyType)

      case pt: ParameterizedType =>
        rawClassOf(pt) match
          case None => AnyType
          case Some(raw) =>
            knownType(raw) match
              case Some(tp) => tp
              case None =>
                typeSymbolFor(raw) match
                  case Some(sym) =>
                    pt.getActualTypeArguments.toList.map(joType(_, env, what)) match
                      case Nil   => StaticRef(sym)
                      case targs => AppliedType(sym, targs)
                  case None =>
                    Reporter.error(s"Java type `${raw.getName}` in ${what()} is not accessible")
                    ErrorType

      case wt: WildcardType =>
        // `? extends X` is X. A bare `?` or a `? super X` has no useful upper
        // bound, and `Any` is the honest reading of both.
        wt.getUpperBounds.toList.headOption match
          case Some(bound) if bound != classOf[Object] => joType(bound, env, what)
          case _ => AnyType

      case ga: GenericArrayType =>
        arrayTypeOf(ga.getGenericComponentType, env, what)

      case _ => AnyType

  /** `T[]` as `jvm.Array[T]`.
    *
    * Only reference elements: an `int[]` needs `iaload` where an `Object[]`
    * needs `aaload`, and the element type that would pick between them is gone
    * by the time the backend sees the call. `byte[]`, `int[]` and friends are
    * therefore still unrepresentable rather than quietly wrong.
    */
  private def arrayTypeOf(component: JavaType, env: Map[String, Type], what: () => String)(using Definitions): Type =
    component match
      case cls: Class[?] if cls.isPrimitive =>
        Reporter.error(s"Java type `${cls.getName}[]` in ${what()} is a primitive array, which the Jo FFI does not support yet")
        ErrorType

      case _ =>
        joType(component, env, what) match
          case ErrorType => ErrorType
          case elemType  => AppliedType(arrayClassSym, elemType :: Nil)

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
      // The SAST's own `VoidType`, not Jo's `Unit`. They are different things
      // (see jvm.md): `Unit` is a value that erases to `Object`, while
      // `VoidType` leaves nothing — which is what Java `void` is. Getting this
      // right is what lets a Jo lambda implementing `Runnable` compile to a
      // real `run()V`, since the typer already drops a value adapted to
      // `VoidType` (`Adaptation.adapt`).
      case "void"    => Some(VoidType)
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

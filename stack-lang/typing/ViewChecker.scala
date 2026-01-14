package typing

import sast.*
import sast.Trees.*
import sast.Types.*
import sast.Symbols.*

import ast.Positions.*
import reporting.Reporter

import scala.collection.mutable

/** Check that direct views are correctly implemented.
  *
  * For each direct view declaration `view I` in a class, check:
  *
  * 1. I is an interface type
  * 2. The class implements all methods required by the interface
  * 3. The methods have compatible types
  * 4. View Consistency: No duplicate member names in the unified virtual namespace
  *    (class members, concrete methods from direct views, non-private members from delegate views)
  */
object ViewChecker:
  def check(nss: List[Namespace])(using Definitions, Reporter): List[Namespace] =
    for ns <- nss do
      given Source = ns.symbol.sourcePos.source
      checkDefs(ns.defs)
    end for
    nss

  def checkDefs(defs: List[Def])(using Definitions, Reporter, Source): Unit =
    for defn <- defs do
      defn match
        case cdef: ClassDef =>
          checkClassDef(cdef)

        case section: Section =>
          checkDefs(section.defs)

        case _ =>
    end for

  def checkClassDef(cdef: ClassDef)(using defn: Definitions, rp: Reporter, src: Source): Unit =
    val views = new mutable.ArrayBuffer[Symbol]

    def checkDuplicate(sym: Symbol, span: Span): Unit =
      if views.contains(sym) then
        Reporter.error("Two views of the type " + sym + " defined", span.toPos)
      else
        views += sym

    // Check direct views (stored in ClassDef.directViews)
    for viewTree <- cdef.directViews do
      val viewType = viewTree.tpe

      def errorDirectView(): Unit = Reporter.error(s"Direct view must be an interface type, found: ${viewType.show}", viewTree.pos)

      // The view type must be an interface type
      def checkDirectViewType(sym: Symbol): Unit =
        checkDuplicate(sym, viewTree.span)

        if sym.isOneOf(Flags.Interface) then
          checkDirectView(cdef, viewTree)

        else
          errorDirectView()

      viewType match
        case StaticRef(sym) => checkDirectViewType(sym)

        case AppliedType(sym, _) => checkDirectViewType(sym)

        case _ => errorDirectView()

    // Check delegate views
    for viewSym <- cdef.vals if viewSym.is(Flags.View) do
      val viewType = viewSym.info

      def errorView(): Unit = rp.error(s"Delegate view must be an interface or class type, found: ${viewType.show}", viewSym.sourcePos)

      // The view type must be an interface or class type
      viewType match
        case StaticRef(sym) =>
          if !sym.isOneOf(Flags.Interface | Flags.Class) then
            errorView()
          else
            checkDuplicate(sym, viewSym.sourcePos.span)

        case AppliedType(sym, _) =>
          if !sym.isOneOf(Flags.Interface | Flags.Class) then
            errorView()
          else
            checkDuplicate(sym, viewSym.sourcePos.span)

        case _ =>
          errorView()

      // Check that the delegate view is not shadowed by the class type
      // If the class is a subtype of the delegate view, the delegate will never be used
      val classType = cdef.symbol.info
      if Subtyping.conforms(classType, viewType) then
        Reporter.error(
          s"Delegate view ${viewType.show} is shadowed: class ${cdef.symbol.name} is already a subtype of ${viewType.show}",
          viewSym.sourcePos
        )

    // Check View Consistency: no duplicate method names across class methods,
    // concrete methods from direct views, and non-private methods from delegate views
    checkViewConsistency(cdef)


  def checkDirectView
      (cdef: ClassDef, viewTree: TypeTree)
      (using defn: Definitions, rp: Reporter, src: Source)
  : Unit =
    val viewType = viewTree.tpe
    val classInfo = viewType.asClassInfo
    val interfaceSym = classInfo.classSymbol

    // Get all methods from the interface
    val requiredMethods = classInfo.methods

    // Check each required method is implemented
    for requiredMethod <- requiredMethods do
      val methodName = requiredMethod.name
      val requiredType = viewType.termMember(methodName).widenTermRef

      // Interface methods are either:
      // - Abstract (Flags.Defer, no body): must be implemented
      // - Concrete (no Flags.Defer, has body): cannot be overridden
      val isAbstract = requiredMethod.is(Flags.Defer)

      // Find matching method in the class
      cdef.funs.find(_.symbol.name == methodName) match
        case Some(implMethod) =>
          // Check if this method can be overridden
          if isAbstract then
            val implType = implMethod.symbol.info

            // Check type compatibility using subtyping
            if !Subtyping.conforms(implType, requiredType) then
              rp.error(
                s"Method $methodName has incompatible type from interface $interfaceSym.\n" +
                s"  Required: ${requiredType.show}\n" +
                s"  Found:    ${implType.show}",
                implMethod.pos
              )

        case None =>
          // Only abstract methods must be implemented
          if isAbstract then
            rp.error(
              s"Class ${cdef.symbol.name} does not implement required method $methodName from interface ${interfaceSym.name}",
              viewTree.pos
            )

  /** Check View Consistency: ensure no duplicate member names in the unified virtual namespace.
    *
    * The unified namespace includes:
    * 1. Direct members (methods and fields) in the class
    * 2. Concrete methods from direct views (interface methods with implementations)
    * 3. Non-private members (methods and fields) from delegate views
    *
    * This ensures a consistent API where each member name has a single, unambiguous meaning.
    */
  def checkViewConsistency
      (cdef: ClassDef)
      (using defn: Definitions, rp: Reporter, src: Source)
  : Unit =
    // Track members with their source for error reporting
    enum MemberSource:
      case DirectMethod(sym: Symbol)
      case DirectField(sym: Symbol)
      case DirectViewMethod(interfaceInfo: ClassInfo, sym: Symbol)
      case DelegateViewMethod(viewInfo: ClassInfo, methodSym: Symbol)
      case DelegateViewField(viewInfo: ClassInfo, fieldSym: Symbol)

    val memberRegistry = mutable.Map.empty[String, MemberSource]

    def describeSource(source: MemberSource): String =
      source match
        case MemberSource.DirectMethod(sym) =>
          s"as class method '${sym.name}' in ${cdef.symbol.name}"
        case MemberSource.DirectField(sym) =>
          s"as class field '${sym.name}' in ${cdef.symbol.name}"
        case MemberSource.DirectViewMethod(interfaceInfo, sym) =>
          s"as concrete method '${sym.name}' from direct view ${interfaceInfo.classSymbol.name}"
        case MemberSource.DelegateViewMethod(viewInfo, methodSym) =>
          s"from delegate view ${viewInfo.classSymbol.name} (method '${methodSym.name}')"
        case MemberSource.DelegateViewField(viewInfo, fieldSym) =>
          s"from delegate view ${viewInfo.classSymbol.name} (field '${fieldSym.name}')"

    def registerMember(name: String, source: MemberSource, pos: SourcePosition): Unit =
      memberRegistry.get(name) match
        case Some(existing) =>
          rp.error(
            s"Member '$name' conflicts in unified namespace.\n" +
            s"  First defined: ${describeSource(existing)}\n" +
            s"  Also defined: ${describeSource(source)}",
            pos
          )
        case None =>
          memberRegistry(name) = source

    // 1. Register direct methods from the class (excluding constructors)
    for method <- cdef.funs if !method.symbol.is(Flags.Constructor) do
      registerMember(
        method.symbol.name,
        MemberSource.DirectMethod(method.symbol),
        method.pos
      )

    // 2. Register direct fields from the class (excluding view fields)
    for field <- cdef.vals if !field.is(Flags.View) do
      registerMember(
        field.name,
        MemberSource.DirectField(field),
        field.sourcePos
      )

    // 3. Register concrete methods from direct views (excluding constructors)
    for viewTree <- cdef.directViews do
      val viewType = viewTree.tpe
      if viewType.isClassInfoType then
        val viewClassInfo = viewType.asClassInfo
        for method <- viewClassInfo.methods if !method.is(Flags.Defer) do
          registerMember(
            method.name,
            MemberSource.DirectViewMethod(viewClassInfo, method),
            viewTree.pos
          )

    // 4. Register non-private methods from delegate views (excluding constructors)
    for viewSym <- cdef.vals if viewSym.is(Flags.View) do
      val viewType = viewSym.info
      if viewType.isClassInfoType then
        val viewClassInfo = viewType.asClassInfo
        for method <- viewClassInfo.methods if !method.is(Flags.Constructor) do
          // Only include non-private methods
          method.visibility match
            case Visibility.Private(sym) if sym == method.owner =>
              // Skip private methods

            case _ =>
              registerMember(
                method.name,
                MemberSource.DelegateViewMethod(viewClassInfo, method),
                viewSym.sourcePos
              )

        // 5. Register non-private fields from delegate views
        for field <- viewClassInfo.fields do
          // Only include non-private fields
          field.visibility match
            case Visibility.Private(sym) if sym == field.owner =>
              // Skip private fields

            case _ =>
              registerMember(
                field.name,
                MemberSource.DelegateViewField(viewClassInfo, field),
                viewSym.sourcePos
              )

package typing

import sast.*
import sast.Trees.*
import sast.Types.*
import sast.Symbols.*

import ast.Positions.*
import reporting.Reporter

/** Check that direct views are correctly implemented.
  *
  * For each direct view declaration `view I` in a class, check:
  * 1. I is an interface type
  * 2. The class implements all methods required by the interface
  * 3. The methods have compatible types
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
    // Check direct views (stored in ClassDef.directViews)
    for viewTree <- cdef.directViews do
      val viewType = viewTree.tpe

      def errorDirectView(): Unit = rp.error(s"Direct view must be an interface type, found: ${viewType.show}", viewTree.pos)

      // The view type must be an interface type
      def checkDirectViewType(sym: Symbol): Unit =
        if sym.isOneOf(Flags.Interface) then
          checkDirectView(cdef, viewTree)
        else
          errorDirectView()

      viewType match
        case StaticRef(sym) => checkDirectViewType(sym)
        case AppliedType(sym, _) => checkDirectViewType(sym)
        case _ => errorDirectView()

    // Check delegate views (stored as fields with View flag but not Defer)
    for viewSym <- cdef.vals if viewSym.is(Flags.View) do
      val viewType = viewSym.info

      def errorView(): Unit = rp.error(s"Delegate view must be an interface or class type, found: ${viewType.show}", viewSym.sourcePos)

      // The view type must be an interface or class type
      viewType match
        case StaticRef(sym) =>
          if !sym.isOneOf(Flags.Interface | Flags.Class) then
            errorView()

        case AppliedType(sym, _) =>
          if !sym.isOneOf(Flags.Interface | Flags.Class) then
            errorView()

        case _ =>
          errorView()

      // Check that the delegate view is not shadowed by the class type
      // If the class is a subtype of the delegate view, the delegate will never be used
      val classType = cdef.symbol.info
      if Subtyping.conforms(classType, viewType) then
        rp.error(
          s"Delegate view ${viewType.show} is shadowed: class ${cdef.symbol.name} is already a subtype of ${viewType.show}",
          viewSym.sourcePos
        )


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
          if !isAbstract then
            rp.error(
              s"Method $methodName in interface ${interfaceSym.name} is not abstract and cannot be overridden",
              implMethod.pos
            )
          else
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

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
    // Find all direct view fields (vals with View and Defer flags)
    val directViews = cdef.vals.filter: viewSym =>
      viewSym.isAllOf(Flags.View | Flags.Defer)

    for viewSym <- directViews do
      val viewType = viewSym.info

      // Check 1: The view type must be an interface
      val interfaceSym = viewType.dealias match
        case StaticRef(sym) if sym.is(Flags.Interface) =>
          sym

        case AppliedType(StaticRef(sym), _) if sym.is(Flags.Interface) =>
          sym

        case _ =>
          rp.error(s"Direct view must be an interface type, found: ${viewType.show}", viewSym.sourcePos)
          null

      if interfaceSym != null then
        checkView(cdef, viewType, viewSym)

  def checkView
      (cdef: ClassDef, viewType: Type, viewSym: Symbol)
      (using defn: Definitions, rp: Reporter, src: Source)
  : Unit =
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
              viewSym.sourcePos
            )

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
    for viewSym <- cdef.vals if viewSym.isAllOf(Flags.View) do
      val viewType = viewSym.info

      def errorDirectView(): Unit = rp.error(s"Direct view must be an interface type, found: ${viewType.show}", viewSym.sourcePos)

      def errorView(): Unit = rp.error(s"View must be an interface or class type, found: ${viewType.show}", viewSym.sourcePos)

      // The view type must be an interface or class type
      def checkView(sym: Symbol): Unit =
        if viewSym.is(Flags.Defer) then
          if sym.isOneOf(Flags.Interface) then
            checkDirectView(cdef, viewType, viewSym)
          else
            errorDirectView()
        else
          if !sym.isOneOf(Flags.Interface | Flags.Class) then
            errorView()

      viewType match
        case StaticRef(sym) => checkView(sym)

        case AppliedType(sym, _) => checkView(sym)

        case _ =>
          errorView()

  /** Check a list of view specifications for a view type
    *
    * Validates each view spec and checks coherence (no duplicate view types).
    * Returns a list of valid view specs.
    *
    * @param viewSpecs List of view specs to validate
    * @param baseType The base type being extended
    * @param astViewSpecs Corresponding AST view specs for error reporting
    * @return List of valid view specs
    */
  def checkViewSpecs(
      viewSpecs: List[ViewSpec],
      baseType: Type,
      astViewSpecs: List[ast.Trees.ViewSpec])
      (using defn: Definitions, rp: Reporter, src: Source)
  : List[ViewSpec] =

    val validSpecs = new mutable.ArrayBuffer[ViewSpec]
    val seenViewTypes = mutable.Set.empty[Type]

    // Get intrinsic views from the base type for coherence checking
    val intrinsicViews = baseType.intrinsicViews.map(_.info).toSet

    def checkAdapter(viewSpec: ViewSpec, adapterSym: Symbol, pos: SourcePosition): Unit =
      val viewType = viewSpec.viewType

      if !adapterSym.is(Flags.Fun) then
        rp.error("View adapter must be a function, found = " + adapterSym, pos)

      else
        val procType = adapterSym.info.asProcType

        // Check: must have exactly one regular parameter
        if procType.params.size != 1 then
          rp.error(s"View adapter must take exactly one parameter, found ${procType.params.size} parameters", pos)

        // Check: must have no auto parameters
        else if procType.autos.nonEmpty then
          rp.error("View adapter cannot have auto parameters", pos)

        // Check: must have no type parameters
        else if procType.tparams.nonEmpty then
          rp.error("View adapter cannot have type parameters", pos)

        // Check: parameter type must conform to base type
        else
          val adapterParamType = procType.params.head.info
          if !Subtyping.conforms(baseType, adapterParamType) then
            rp.error(s"Base type ${baseType.show} does not conform to adapter parameter type ${adapterParamType.show}", pos)

          // Check: return type must conform to view type
          else if !Subtyping.conforms(procType.resultType, viewType) then
            rp.error(s"Adapter return type ${procType.resultType.show} does not conform to view type ${viewType.show}", pos)

          else
            validSpecs += viewSpec
    end checkAdapter

    for (viewSpec, astViewSpec) <- viewSpecs.zip(astViewSpecs) do
      // Check: view type must be a class or interface (not a type alias)
      val isValid = viewSpec.viewType match
        case StaticRef(sym) if sym.isType && sym.isAlias =>
          rp.error(s"View type cannot be a type alias, found = ${viewSpec.viewType.show}", astViewSpec.tpe.pos)
          false

        case _ =>
          if !viewSpec.viewType.approx.isClassInfoType then
            rp.error(s"View type must be a class or interface, found = ${viewSpec.viewType.show}", astViewSpec.tpe.pos)
            false
          else
            true

      if isValid then
        // Check coherence: no duplicate view types
        val viewTypeDealiased = viewSpec.viewType.dealias
        if seenViewTypes.contains(viewTypeDealiased) then
          rp.error(s"Duplicate view type ${viewSpec.viewType.show} in view type declaration", astViewSpec.tpe.pos)

        // Check coherence: extension views must not overlap with intrinsic views
        else if intrinsicViews.contains(viewTypeDealiased) then
          rp.error(s"Extension view ${viewSpec.viewType.show} conflicts with intrinsic view of base type ${baseType.show}", astViewSpec.tpe.pos)

        else
          seenViewTypes += viewTypeDealiased

          // Validate adapter if present
          viewSpec.adapter match
            case None =>
              // No adapter specified - must use constructor, so view type must be a class
              if viewSpec.viewType.isClassType then
                // View type is a class - can use constructor
                validSpecs += viewSpec

              else if astViewSpec.adapter.isEmpty then
                // Avoid dupicate error message if adapter is invalid

                // View type is not a class (e.g., interface) - cannot use constructor
                rp.error(
                  s"View type ${viewSpec.viewType.show} must be a class when no adapter is specified, or provide an adapter function",
                  astViewSpec.tpe.pos
                )

            case Some(adapterSym) =>
              val adapterPos = astViewSpec.adapter.get.pos
              checkAdapter(viewSpec, adapterSym, adapterPos)

    end for

    validSpecs.toList

  def checkDirectView
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

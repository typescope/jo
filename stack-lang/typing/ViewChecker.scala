package typing

import sast.*
import sast.Trees.*
import sast.Types.ProcType

import ast.Positions.*
import reporting.Reporter

  /** Check that direct views are correctly implemented.
  *
  * For each direct view declaration `view I` in a class, check:
  *
  * 1. The class implements all abstract methods required by the interface
  * 2. The implementations have compatible types
  *
  * Duplicate and non-interface view types are caught earlier in Namer.checkViews
  * and filtered out before this pass runs, so those checks are not repeated here.
  * Conflicts involving abstract-method forwarders (delegate views, or two delegates
  * overlapping) are also caught in Namer.checkViews.
  */
object ViewChecker:
  def check(units: List[FileUnit])(using Definitions, Reporter): List[FileUnit] =
    for unit <- units do
      given Source = unit.source
      checkDefs(unit.defs)
    end for
    units

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
    for viewTree <- cdef.views do
      checkView(cdef, viewTree)

    def receivesCompatible(proc1: ProcType, proc2: ProcType): Boolean =
      proc1.receives.isEmpty && proc2.receives.isEmpty
      || proc1.receives.nonEmpty && proc2.receives.nonEmpty

    def summarize(procType: ProcType): String =
       if procType.receives.isEmpty then "empty" else "non-empty"

    for implMethod <- cdef.funs do
      val methodName = implMethod.name
      val implMethodSym = implMethod.symbol

      val defers = implMethodSym.implementedDefers

      defers match
      case defer1 :: rest =>
        val proc1 = defer1.tpe.asProcType

        rest.find: defer2 =>
          val proc2 = defer2.tpe.asProcType
          val found = !receivesCompatible(proc1, proc2)
          if found then
            rp.error(
              s"Deferred methods implemented by $methodName should all have non-empty receives or all empty receives.\n" +
              s"  interface ${defer1.owner}: ${summarize(proc1)}\n" +
              s"  interface ${defer2.owner}: ${summarize(proc2)}",
              implMethod.pos
            )
          found

      case _ =>
    end for

  def checkView
      (cdef: ClassDef, viewTree: TypeTree)
      (using defn: Definitions, rp: Reporter, src: Source)
  : Unit =
    val viewType = viewTree.tpe
    val classInfo = viewType.classInfo
    val interfaceSym = classInfo.classSymbol

    // Get all methods from the interface
    val requiredMethods = classInfo.methods

    // Check each required method is implemented
    for requiredMethod <- requiredMethods do
      val methodName = requiredMethod.name
      val requiredType = viewType.termMember(methodName).asProcType

      val isAbstract = requiredMethod.is(Flags.Defer)

      // Find matching method in the class
      cdef.funs.find(_.symbol.name == methodName) match
        case Some(implMethod) =>
          val implMethodSym = implMethod.symbol
          if isAbstract then
            val implType = implMethodSym.tpe

            if !Subtyping.conforms(implType, requiredType) then
              rp.error(
                s"Method $methodName has incompatible type from interface $interfaceSym.\n" +
                s"  Required: ${requiredType.show}\n" +
                s"  Found:    ${implType.show}",
                implMethod.pos
              )

        case None =>
          if isAbstract then
            rp.error(
              s"Class ${cdef.symbol.name} does not implement required method $methodName from interface ${interfaceSym.name}",
              viewTree.pos
            )

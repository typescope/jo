package python

import typing.PostCheck

import sast.*
import sast.Trees.*
import sast.Symbols.Symbol

import reporting.Config
import reporting.Reporter

final class PythonPostCheck extends PostCheck:
  def check(units: List[FileUnit])(using defn: Definitions, rp: Reporter, cf: Config): Unit =
    val runtime = new PythonRuntime

    def reportInvalidTargetSym(sym: Symbol): Unit =
      if sym.hasAnnotation(runtime.annot_targetName) then
        Reporter.error("@py.targetName is only valid on abstract methods of a @py.interop interface", sym.sourcePos)
      if sym.hasAnnotation(runtime.annot_property) then
        Reporter.error("@py.property is only valid on abstract methods of a @py.interop interface", sym.sourcePos)

    def reportInvalidInteropSym(sym: Symbol): Unit =
      if sym.hasAnnotation(runtime.annot_interop) then
        Reporter.error("@py.interop is only valid on interfaces", sym.sourcePos)

    def reportInvalidTarget(defn: Def): Unit = reportInvalidTargetSym(defn.symbol)
    def reportInvalidInterop(defn: Def): Unit = reportInvalidInteropSym(defn.symbol)

    def checkFun(fdef: FunDef): Unit =
      val sym = fdef.symbol

      reportInvalidInteropSym(sym)

      val isPythonTargetName = sym.hasAnnotation(runtime.annot_targetName)
      val isPythonProperty = sym.hasAnnotation(runtime.annot_property)

      if isPythonTargetName || isPythonProperty then
        val isAbstractInterfaceMethod =
          sym.owner.is(Flags.Interface) && sym.is(Flags.Method) && sym.is(Flags.Defer)
        val isInterop = sym.owner.hasAnnotation(runtime.annot_interop)
        val isValidTarget = isAbstractInterfaceMethod && isInterop

        if !isValidTarget then
          if isPythonTargetName then
            Reporter.error("@py.targetName is only valid on abstract methods of a @py.interop interface", sym.sourcePos)
          if isPythonProperty then
            Reporter.error("@py.property is only valid on abstract methods of a @py.interop interface", sym.sourcePos)

        if isPythonProperty then
          val procType = sym.tpe.asProcType
          if procType.params.nonEmpty || procType.autos.nonEmpty then
            Reporter.error("@py.property is only valid on parameterless methods", sym.sourcePos)

        runtime.pyTargetName(sym).foreach: name =>
          if !PythonRuntime.isValidMemberName(name) then
            Reporter.error(s"@py.targetName value \"$name\" is not a valid Python identifier", sym.sourcePos)

    def traverse(defn: Def): Unit =
      defn match
        case pdef: ParamDef =>
          reportInvalidTarget(pdef)
          reportInvalidInterop(pdef)

        case tdef: TypeDef =>
          reportInvalidTarget(tdef)
          reportInvalidInterop(tdef)

        case fdef: FunDef =>
          checkFun(fdef)

        case cdef: ClassDef =>
          reportInvalidTarget(cdef)
          reportInvalidInterop(cdef)
          cdef.vals.foreach(field => reportInvalidTargetSym(field.symbol))
          cdef.vals.foreach(field => reportInvalidInteropSym(field.symbol))
          cdef.funs.foreach(checkFun)

        case idef: InterfaceDef =>
          reportInvalidTarget(idef)
          // @py.interop is valid on an interface — no interop check here
          idef.methods.foreach(checkFun)

        case pdef: PatDef =>
          reportInvalidTarget(pdef)
          reportInvalidInterop(pdef)

        case sec: Section =>
          reportInvalidTarget(sec)
          reportInvalidInterop(sec)
          sec.defs.foreach(traverse)

    units.foreach: unit =>
      unit.defs.foreach(traverse)

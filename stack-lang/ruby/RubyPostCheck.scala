package ruby

import typing.PostCheck

import sast.*
import sast.Trees.*
import sast.Symbols.Symbol

import reporting.Config
import reporting.Reporter

final class RubyPostCheck extends PostCheck:
  def check(units: List[FileUnit])(using defn: Definitions, rp: Reporter, cf: Config): Unit =
    val runtime = new RubyRuntime

    def reportInvalidTargetSym(sym: Symbol): Unit =
      if sym.hasAnnotation(runtime.annot_targetName) then
        Reporter.error("@rb.targetName is only valid on abstract methods of a @rb.interop interface", sym.sourcePos)

    def reportInvalidInteropSym(sym: Symbol): Unit =
      if sym.hasAnnotation(runtime.annot_interop) then
        Reporter.error("@rb.interop is only valid on interfaces", sym.sourcePos)

    def reportInvalidTarget(defn: Def): Unit = reportInvalidTargetSym(defn.symbol)
    def reportInvalidInterop(defn: Def): Unit = reportInvalidInteropSym(defn.symbol)

    def checkFun(fdef: FunDef): Unit =
      val sym = fdef.symbol

      reportInvalidInteropSym(sym)

      if sym.hasAnnotation(runtime.annot_targetName) then
        val isAbstractInterfaceMethod =
          sym.owner.is(Flags.Interface) && sym.is(Flags.Method) && sym.is(Flags.Defer)
        val isInterop = sym.owner.hasAnnotation(runtime.annot_interop)
        val isValidTarget = isAbstractInterfaceMethod && isInterop

        if !isValidTarget then
          Reporter.error("@rb.targetName is only valid on abstract methods of a @rb.interop interface", sym.sourcePos)

        runtime.rbTargetName(sym).foreach: name =>
          if !RubyRuntime.isValidMethodName(name) then
            Reporter.error(s"@rb.targetName value \"$name\" is not a valid Ruby method name", sym.sourcePos)

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
          // @rb.interop is valid on an interface — no interop check here
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

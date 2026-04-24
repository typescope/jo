package ruby

import typing.PostCheck

import sast.*
import sast.Trees.*

import reporting.Config
import reporting.Reporter

final class RubyPostCheck extends PostCheck:
  def check(units: List[FileUnit])(using defn: Definitions, rp: Reporter, cf: Config): Unit =
    val runtime = new RubyRuntime

    def reportInvalidTarget(defn: Def): Unit =
      if defn.symbol.hasAnnotation(runtime.annot_targetName) then
        Reporter.error("@rb.targetName is only valid on abstract interface methods", defn.symbol.sourcePos)

    def checkFun(fdef: FunDef): Unit =
      val sym = fdef.symbol
      if sym.hasAnnotation(runtime.annot_targetName) then
        val isAbstractInterfaceMethod =
          sym.owner.is(Flags.Interface) && sym.is(Flags.Method) && sym.is(Flags.Defer)

        if !isAbstractInterfaceMethod then
          Reporter.error("@rb.targetName is only valid on abstract interface methods", sym.sourcePos)

        runtime.rbTargetName(sym).foreach: name =>
          if !RubyRuntime.isValidMethodName(name) then
            Reporter.error(s"@rb.targetName value \"$name\" is not a valid Ruby method name", sym.sourcePos)

    def traverse(defn: Def): Unit =
      defn match
        case pdef: ParamDef => reportInvalidTarget(pdef)
        case tdef: TypeDef => reportInvalidTarget(tdef)
        case fdef: FunDef => checkFun(fdef)
        case cdef: ClassDef =>
          reportInvalidTarget(cdef)
          cdef.vals.foreach(field => reportInvalidTargetSym(field.symbol))
          cdef.funs.foreach(checkFun)
        case idef: InterfaceDef =>
          reportInvalidTarget(idef)
          idef.methods.foreach(checkFun)
        case pdef: PatDef => reportInvalidTarget(pdef)
        case sec: Section =>
          reportInvalidTarget(sec)
          sec.defs.foreach(traverse)

    units.foreach: unit =>
      unit.defs.foreach(traverse)

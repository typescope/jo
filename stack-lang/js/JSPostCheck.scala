package js

import typing.PostCheck

import sast.*
import sast.Trees.*

import reporting.Config
import reporting.Reporter

final class JSPostCheck extends PostCheck:
  def check(units: List[FileUnit])(using defn: Definitions, rp: Reporter, cf: Config): Unit =
    val runtime = new JSRuntime

    def reportInvalidTarget(defn: Def): Unit =
      if defn.symbol.hasAnnotation(runtime.annot_targetName) then
        Reporter.error("@js.targetName is only valid on abstract interface methods", defn.symbol.sourcePos)

      if defn.symbol.hasAnnotation(runtime.annot_property) then
        Reporter.error("@js.property is only valid on abstract interface methods", defn.symbol.sourcePos)

    def checkFun(fdef: FunDef): Unit =
      val sym = fdef.symbol
      val isJsTargetName = sym.hasAnnotation(runtime.annot_targetName)
      val isJsProperty = sym.hasAnnotation(runtime.annot_property)

      if isJsTargetName || isJsProperty then
        val isAbstractInterfaceMethod =
          sym.owner.is(Flags.Interface) && sym.is(Flags.Method) && sym.is(Flags.Defer)

        if !isAbstractInterfaceMethod then
          if isJsTargetName then
            Reporter.error("@js.targetName is only valid on abstract interface methods", sym.sourcePos)
          if isJsProperty then
            Reporter.error("@js.property is only valid on abstract interface methods", sym.sourcePos)

        if isJsProperty then
          val procType = sym.info.asProcType
          if procType.params.nonEmpty || procType.autos.nonEmpty then
            Reporter.error("@js.property is only valid on parameterless methods", sym.sourcePos)

        runtime.jsTargetName(sym).foreach: name =>
          if !JSRuntime.isValidIdentifier(name) then
            Reporter.error(s"@js.targetName value \"$name\" is not a valid JavaScript identifier", sym.sourcePos)

    def traverse(defn: Def): Unit =
      defn match
        case pdef: ParamDef =>
          reportInvalidTarget(pdef)
        case tdef: TypeDef =>
          reportInvalidTarget(tdef)
        case fdef: FunDef =>
          checkFun(fdef)
        case cdef: ClassDef =>
          reportInvalidTarget(cdef)
          cdef.funs.foreach(checkFun)
        case idef: InterfaceDef =>
          reportInvalidTarget(idef)
          idef.methods.foreach(checkFun)
        case pdef: PatDef =>
          reportInvalidTarget(pdef)
        case sec: Section =>
          reportInvalidTarget(sec)
          sec.defs.foreach(traverse)

    units.foreach: unit =>
      unit.defs.foreach(traverse)

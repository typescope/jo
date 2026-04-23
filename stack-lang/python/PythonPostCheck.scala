package python

import phases.PostCheck

import sast.*
import sast.Trees.*

import reporting.Config
import reporting.Reporter

final class PythonPostCheck extends PostCheck:
  def check(units: List[FileUnit])(using defn: Definitions, rp: Reporter, cf: Config): Unit =
    val runtime = new PythonRuntime

    def checkFun(fdef: FunDef): Unit =
      val sym = fdef.symbol
      val isPythonTargetName = fdef.annots.exists:
        case Apply(Ident(annotSym), _, _) => annotSym == runtime.annot_targetName
        case _ => false
      val isPythonProperty = fdef.annots.exists:
        case Apply(Ident(annotSym), _, _) => annotSym == runtime.annot_property
        case _ => false

      if isPythonTargetName || isPythonProperty then
        val isAbstractInterfaceMethod =
          sym.owner.is(Flags.Interface) && sym.is(Flags.Method) && sym.is(Flags.Defer)

        if !isAbstractInterfaceMethod then
          if isPythonTargetName then
            Reporter.error("@py.targetName is only valid on abstract interface methods", sym.sourcePos)
          if isPythonProperty then
            Reporter.error("@py.property is only valid on abstract interface methods", sym.sourcePos)

        if isPythonProperty then
          val procType = sym.info.asProcType
          if procType.params.nonEmpty || procType.autos.nonEmpty then
            Reporter.error("@py.property is only valid on parameterless methods", sym.sourcePos)

        runtime.pyTargetName(sym).foreach: name =>
          if !PythonRuntime.isValidIdentifier(name) then
            Reporter.error(s"@py.targetName value \"$name\" is not a valid non-keyword Python identifier", sym.sourcePos)

    def traverse(defn: Def): Unit =
      defn match
        case fdef: FunDef =>
          checkFun(fdef)
        case cdef: ClassDef =>
          cdef.funs.foreach(checkFun)
        case idef: InterfaceDef =>
          idef.methods.foreach(checkFun)
        case sec: Section =>
          sec.defs.foreach(traverse)
        case _ =>

    units.foreach: unit =>
      unit.defs.foreach(traverse)

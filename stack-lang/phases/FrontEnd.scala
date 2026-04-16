package phases

import sast.*
import sast.Trees.*
import sast.Symbols.Symbol

import typing.Typer

import reporting.Config
import reporting.Config.InternalSetting
import reporting.Reporter
import reporting.Reporter.Step

import scala.language.implicitConversions

object FrontEnd:
  type ProcessStep = Step[List[FileUnit], List[FileUnit]]

  val rewireMap: InternalSetting[Map[Symbol, Symbol]] = InternalSetting(Map.empty, "mapping for rewiring functions")

  def run
      (defaultRuntimePackages: List[String], sources: List[String], defaultMappings: Map[String, String])
      (using defnLazy: Definitions.Lazy, rp: Reporter, cf: Config)
  : List[FileUnit] =
    val (nss, nssDelayed) = sources |> Typer.parseStep |> Typer.typeStep

    locally:
      given Definitions = defnLazy.value

      Config.sastDir.value.foreach: dir =>
        common.IO.ensureExists(dir)
        for unit <- nss do pickle.Encoder.store(unit, dir, testPickling = false, verbose = false)

      nss |> linkStep(nssDelayed, defaultRuntimePackages, defaultMappings) |> translateStep

  def linkStep
      (lazyLibs: pickle.LazyFileUnits, defaultRuntimePackages: List[String], defaultMappings: Map[String, String])
      (using defn: Definitions, rp: Reporter, cf: Config)
  : ProcessStep =
    Step("Link", (units: List[FileUnit]) => {
      for pkg <- defaultRuntimePackages do
        pickle.Decoder.loadPackage(pkg, lazyLibs) <| "link " + pkg

      for pkg <- Config.linkLibPaths.value do
        pickle.Decoder.loadPackage(pkg, lazyLibs) <| "link " + pkg

      val linkData = new LinkRewriter.LinkData(defaultMappings)
      val symbolMap = detectMain(units, linkData.addUserMappings(Config.linkMap.value))
      cf.setInternal(FrontEnd.rewireMap, symbolMap)

      val runtimeRoots = List("jo.runtime", "jo.py").flatMap(defn.resolveContainerOpt)
      lazyLibs.forceIf: unit =>
        runtimeRoots.exists(unit.owner.containedIn)

      val libUnits = lazyLibs.force()
      val allUnits = units ++ libUnits

      val rewriter = new LinkRewriter(symbolMap)
      rewriter.transform(allUnits)
    })

  private def detectMain
     (units: List[FileUnit], linkData: LinkRewriter.LinkData)
     (using rp: Reporter, defn: Definitions)
  : Map[Symbol, Symbol]  =

    if linkData.contains(defn.main.fullName) then
      linkData.resolve()

    else
      val mainInfo = defn.main.info

      val cands = units.flatMap: unit =>
         unit.defs.filter:
           case defn: FunDef => !defn.symbol.is(Flags.Defer) && defn.name == "main"

           case _ => false

      val mains = cands.map(_.symbol).filter: sym =>
          Subtyping.conforms(sym.info, mainInfo)

      mains match
        case main :: Nil =>
          linkData.resolve(defn.main, main)

        case _ =>
          if mains.isEmpty then
            val explain =
              if cands.isEmpty then
                ""
              else
                val nameLines = cands.map(defn => "- " + defn.symbol.fullName + ": " + defn.symbol.info.show).mkString(System.lineSeparator)
                s" None of the following candidates conform to the contract ${defn.main.fullName} (${mainInfo.show})" + System.lineSeparator + nameLines

            Reporter.abortInternal("No qualified main function found." + explain)

          else
            val nameLines = mains.map("- " + _.fullName).mkString(System.lineSeparator)
            Reporter.abortInternal("Multiple main function detected:" + System.lineSeparator + nameLines)

          linkData.resolve()


  def translateStep(using defn: Definitions, rp: Reporter, cf: Config): ProcessStep =
    Step("Normalize", (units: List[FileUnit]) => {
      val patmat = new phases.PatternMatcher
      val tailcallopt = new phases.TailCallOpt
      units        |>
      patmat       |>
      tailcallopt
    })

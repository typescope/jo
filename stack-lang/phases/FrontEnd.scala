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
      (runtimes: List[String], sources: List[String], defaultMappings: Map[String, String])
      (using defnLazy: Definitions.Lazy, rp: Reporter, cf: Config)
  : List[FileUnit] =
    val (nss, nssDelayed) = sources |> Typer.parseStep |> Typer.typeStep

    locally:
      given Definitions = defnLazy.value
      nss |> linkStep(nssDelayed, runtimes, defaultMappings) |> translateStep

  def linkStep
      (libsDelayed: List[DelayedDef[FileUnit]], linkPackages: List[String], defaultMappings: Map[String, String])
      (using defn: Definitions, rp: Reporter, cf: Config)
  : ProcessStep =
    Step("Link", (units: List[FileUnit]) => {
      // TODO: optimization possible based on reachability analysis of modules
      val libUnits = libsDelayed.map(_.force())

      val linkUnits: List[FileUnit] = linkPackages.flatMap: pkg =>
         pickle.Decoder.loadPackage(pkg).map(_.force()) <| "link " + pkg

      val allUnits = units ++ libUnits ++ linkUnits

      // Apply link rewriting and check that all deferred functions are provided
      val linkData = new LinkRewriter.LinkData(defaultMappings)
      val symbolMap = detectMain(units, linkData.addUserMappings(Config.linkMap.value))
      cf.setInternal(FrontEnd.rewireMap, symbolMap)

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
      val normalizer = new phases.NormalizeParams
      val tailcallopt = new phases.TailCallOpt

      units        |>
      normalizer   |>
      patmat       |>
      tailcallopt
    })

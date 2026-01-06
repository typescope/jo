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
  type ProcessStep = Step[List[Namespace], List[Namespace]]

  val rewireMap: InternalSetting[Map[Symbol, Symbol]] = InternalSetting(Map.empty, "mapping for rewiring functions")

  def run
      (runtimes: List[String], sources: List[String], defaultMappings: Map[String, String])
      (using defnLazy: Definitions.Lazy, rp: Reporter, cf: Config)
  : List[Namespace] =
    val (nss, nssDelayed) = sources |> Typer.parseStep |> Typer.typeStep

    locally:
      given Definitions = defnLazy.value
      nss |> linkStep(nssDelayed, runtimes, defaultMappings) |> translateStep

  def linkStep
      (libsDelayed: List[DelayedDef[Namespace]], linkPackages: List[String], defaultMappings: Map[String, String])
      (using defn: Definitions, rp: Reporter, cf: Config)
  : ProcessStep =
    Step("Link", (nss: List[Namespace]) => {
      // TODO: optimization possible based on reachability analysis of modules
      val libNss = libsDelayed.map(_.force())

      val linkNss = linkPackages.flatMap: pkg =>
         pickle.Decoder.loadPackage(pkg) <| "link " + pkg

      val allNss = nss ++ libNss ++ linkNss

      // Apply link rewriting and check that all deferred functions are provided
      val linkData = new LinkRewriter.LinkData(defaultMappings)
      val symbolMap = detectMain(nss, linkData.addUserMappings(Config.linkMap.value))
      cf.setInternal(FrontEnd.rewireMap, symbolMap)

      val rewriter = new LinkRewriter(symbolMap)
      rewriter.transform(allNss)
    })

  private def detectMain
     (nss: List[Namespace], linkData: LinkRewriter.LinkData)
     (using cf: Config, rp: Reporter, defn: Definitions)
  : Map[Symbol, Symbol]  =

    if Config.autoMainOff.value then
      linkData.resolve()

    else
      val mainInfo = defn.Main_main.info

      val cands = nss.flatMap: ns =>
         ns.defs.filter:
           case defn: FunDef => !defn.symbol.is(Flags.Defer) && defn.name == "main"

           case _ => false

      val mains = cands.map(_.symbol).filter: sym =>
          Subtyping.conforms(sym.info, mainInfo)

      mains match
        case main :: Nil =>
          linkData.resolve(defn.Main_main, main)

        case _ =>
          if mains.isEmpty then
            val explain =
              if cands.isEmpty then
                ""
              else
                val nameLines = cands.map(defn => "- " + defn.symbol.fullName + ": " + defn.symbol.info.show).mkString(System.lineSeparator)
                s" None of the following candidates conform to the contract ${defn.Main_main.fullName} (${mainInfo.show})" + System.lineSeparator + nameLines

            Reporter.abortInternal("No qualified main function found." + explain)

          else
            val nameLines = mains.map("- " + _.fullName).mkString(System.lineSeparator)
            Reporter.abortInternal("Multiple main function detected:" + System.lineSeparator + nameLines)

          linkData.resolve()


  def translateStep(using defn: Definitions, rp: Reporter, cf: Config): ProcessStep =
    Step("Normalize", (nss: List[Namespace]) => {
      val patmat = new phases.PatternMatcher
      val normalizer = new phases.NormalizeParams

      nss        |>
      normalizer |>
      patmat
    })

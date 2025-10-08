package phases

import sast.*
import sast.Trees.*

import typing.Typer

import reporting.Config
import reporting.Reporter
import reporting.Reporter.Step

object FrontEnd:
  type ProcessStep = Step[List[Namespace], List[Namespace]]

  def run
      (runtimes: List[String], sources: List[String], linkMappings: Map[String, String] = Map.empty)
      (using defnLazy: Definitions.Lazy, rp: Reporter, cf: Config)
  : List[Namespace] =
    val sast = sources |> Typer.parseStep |> Typer.typeStep

    locally:
      given Definitions = defnLazy.value
      sast |> linkStep(runtimes, linkMappings) |> translateStep

  def linkStep(packages: List[String], linkMappings: Map[String, String] = Map.empty)
      (using defn: Definitions, rp: Reporter, cf: Config)
  : ProcessStep =
    Step("Link", (nss: List[Namespace]) => {
      val linkNss = packages.flatMap: pkg =>
         pickle.Decoder.loadPackage(pkg) <| "link " + pkg

      val allNss = nss ++ linkNss

      // Apply link rewriting and check that all deferred functions are provided
      val symbolMap = LinkRewriter.parseLinkMappings(linkMappings)
      val rewriter = new LinkRewriter(symbolMap)
      rewriter.transform(allNss)
    })

  def translateStep(using defn: Definitions, rp: Reporter, cf: Config): ProcessStep =
    Step("Normalize", (nss: List[Namespace]) => {
      val encoder = new phases.EncodeTagged
      val patmat = new phases.PatternMatcher
      val normalizer = new phases.NormalizeParams

      nss        |>
      normalizer |>
      patmat     |>
      encoder
    })

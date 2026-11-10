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

  /** Runtime definitions of a backend required by the frontend phases
    *
    * @param isLambdaValue   the full name of the intrinsic `isLambdaValue(v: Any): Bool`
    */
  case class RuntimeConfig(isLambdaValue: String)

  def run
      (defaultRuntimePackages: List[String], sources: List[String], defaultMappings: Map[String, String], runtimeConfig: RuntimeConfig)
      (using defnLazy: Definitions.Lazy, rp: Reporter, cf: Config)
  : List[FileUnit] =
    val (nss, nssDelayed) = sources |> Typer.parseStep |> Typer.typeStep

    locally:
      given Definitions = defnLazy.value

      nss |> linkStep(nssDelayed, defaultRuntimePackages, defaultMappings) |> translateStep(runtimeConfig)

  def linkStep
      (lazyLibs: pickle.LazyFileUnits, defaultRuntimePackages: List[String], defaultMappings: Map[String, String])
      (using defn: Definitions, rp: Reporter, cf: Config)
  : ProcessStep =
    Step("Link", (units: List[FileUnit]) => {
      for pkg <- defaultRuntimePackages do
        pickle.Decoder.loadPackage(pkg, lazyLibs) <| "link " + pkg

      for pkg <- Config.linkLibPaths.value do
        pickle.Decoder.loadPackage(pkg, lazyLibs) <| "link " + pkg

      val injection = Injection.plan(Config.injectBindings)
      val linkData = new LinkRewriter.LinkData(defaultMappings, injection)
      val implMap = detectMain(units, linkData.addUserMappings(Config.linkMap.value))

      val runtimeRoots = List("jo.runtime", "jo.py.runtime", "jo.rb.runtime").flatMap(defn.resolveContainerOpt)
      lazyLibs.forceIf: unit =>
        runtimeRoots.exists(unit.owner.containedIn)

      if !rp.hasErrors then
        injection.warnUnused()

      val libUnits = lazyLibs.force()

      // Under injection, `jo.main` resolves to a generated wrapper around its implementation
      val (symbolMap, allUnits) =
        implMap.get(defn.main) match
          case Some(impl) if !injection.isEmpty && injection.valid =>
            val wrapper = injection.synthesize(impl)
            (implMap + (defn.main -> wrapper.symbol), Injection.addWrapper(units ++ libUnits, impl, wrapper))

          case _ =>
            (implMap, units ++ libUnits)

      cf.setInternal(FrontEnd.rewireMap, symbolMap)

      // println("Units = " + allUnits.map(_.source.file))

      val rewriter = new LinkRewriter(symbolMap)
      rewriter.transform(allUnits)
    })

  /** Locate the implementation of `jo.main`
    *
    * Unless `--link` names it, the implementation is the one top-level
    * function named `main` with no parameters of any kind, ordinary, auto,
    * or type. It is then checked
    * against `jo.main` like any other link target.
    */
  private def detectMain
     (units: List[FileUnit], linkData: LinkRewriter.LinkData)
     (using rp: Reporter, defn: Definitions)
  : Map[Symbol, Symbol]  =

    if linkData.contains(defn.main.fullName) then
      linkData.resolve()

    else
      val mains = units.flatMap: unit =>
        unit.defs.collect:
          case fun: FunDef
          if !fun.symbol.is(Flags.Defer) && fun.name == "main"
             && fun.tparams.isEmpty && fun.params.isEmpty && fun.autos.isEmpty =>
            fun.symbol

      mains match
        case main :: Nil =>
          linkData.addUserMappings(Map(defn.main.fullName -> main.fullName)).resolve()

        case Nil =>
          rp.error(
            "No main function found. Define a top-level function named `main` with no ordinary, auto, or type parameters, " +
            s"or name the entry point with --link ${defn.main.fullName}=<path>"
          )
          linkData.resolve()

        case _ =>
          val nameLines = mains.map("- " + _.fullName).mkString(System.lineSeparator)
          rp.error(
            s"Multiple main functions found. Name the entry point with --link ${defn.main.fullName}=<path>:" +
            System.lineSeparator + nameLines
          )
          linkData.resolve()


  def translateStep(runtimeConfig: RuntimeConfig)(using defn: Definitions, rp: Reporter, cf: Config): ProcessStep =
    Step("Normalize", (units: List[FileUnit]) => {
      val liftPrim    = new phases.LiftPrimitiveMethods
      val patmat      = new phases.PatternMatcher(defn.resolveTerm(runtimeConfig.isLambdaValue))
      val tailcallopt = new phases.TailCallOpt
      units        |>
      patmat       |>
      liftPrim     |>
      tailcallopt
    })

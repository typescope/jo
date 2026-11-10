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
      val roles = injection.map(_.roles).getOrElse(Injection.Roles.empty)

      val linkData = new LinkRewriter.LinkData(defaultMappings, roles)
      val implMap = detectMain(units, linkData.addUserMappings(Config.linkMap.value), roles)

      val runtimeRoots = List("jo.runtime", "jo.py.runtime", "jo.rb.runtime").flatMap(defn.resolveContainerOpt)
      lazyLibs.forceIf: unit =>
        runtimeRoots.exists(unit.owner.containedIn)

      if !rp.hasErrors then
        injection.foreach(_.warnUnused())

      val libUnits = lazyLibs.force()

      // Under injection, `jo.main` resolves to a generated wrapper around its implementation
      val (symbolMap, allUnits) =
        (injection, implMap.get(defn.main)) match
          case (Some(plan), Some(impl)) if plan.valid =>
            val wrapper = plan.synthesize(impl)
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
    * Unless `--link` names it, the implementation is the one top-level `main`
    * that conforms to `jo.main`. Under injection the conformance rule is
    * relaxed: `main` may also receive a provided parameter.
    */
  private def detectMain
     (units: List[FileUnit], linkData: LinkRewriter.LinkData, roles: Injection.Roles)
     (using rp: Reporter, defn: Definitions)
  : Map[Symbol, Symbol]  =

    if linkData.contains(defn.main.fullName) then
      linkData.resolve()

    else
      val mainInfo = defn.main.tpe

      val cands = units.flatMap: unit =>
         unit.defs.filter:
           case defn: FunDef => !defn.symbol.is(Flags.Defer) && defn.name == "main"

           case _ => false

      val mains = cands.map(_.symbol).filter: sym =>
          roles.conforms(sym.tpe, mainInfo)

      // A candidate that conforms except for the internal parameters it receives
      val internalMains = cands.map(_.symbol).filter: sym =>
          roles.internalReceived(sym.tpe, mainInfo).nonEmpty

      (mains, internalMains) match
        case (main :: Nil, _) =>
          linkData.resolve(defn.main, main)

        case (Nil, main :: Nil) =>
          for param <- roles.internalReceived(main.tpe, mainInfo) do
            rp.error(s"${defn.main.fullName} implementation ${main.fullName} receives internal parameter ${param.fullName}")

          linkData.resolve(defn.main, main)

        case (Nil, _) =>
          val explain =
            if cands.isEmpty then
              ""
            else
              val nameLines = cands.map(defn => "- " + defn.symbol.fullName + ": " + defn.symbol.tpe.show).mkString(System.lineSeparator)
              s" None of the following candidates conform to the contract ${defn.main.fullName} (${mainInfo.show})" + System.lineSeparator + nameLines

          rp.error("No qualified main function found." + explain)
          linkData.resolve()

        case _ =>
          val nameLines = mains.map("- " + _.fullName).mkString(System.lineSeparator)
          rp.error("Multiple main function detected:" + System.lineSeparator + nameLines)
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

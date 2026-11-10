package phases

import ast.{ Trees => Ast }
import ast.Positions.Source

import sast.*
import sast.Trees.*
import sast.Symbols.*
import sast.Types.*

import parsing.Scanner
import parsing.Tokens.Token

import typing.NumericTyper
import typing.Inference.TargetType

import reporting.Reporter
import reporting.Config.{ InjectBinding, isValidPath }
import reporting.Diagnostics.{ Diagnostic, Kind, ReportItem, UnpositionedReportItem }

import common.StringUtil

import scala.collection.mutable

/** Capability injection, see the Entry Point section of docs/language/syntax/structure-and-convention.md
  *
  * An injection specification binds context parameters to factories or
  * literals and attaches to one deferred function, the target, which is
  * always `jo.main`. The target then resolves to a generated wrapper that
  * instantiates the bindings and calls the implementation of the target.
  *
  * A provided parameter may be received by the implementation of the
  * target, an internal parameter may not. Factories may receive either, as
  * well as the context parameters of the target at their outer values.
  */
object Injection:

  /** The value a binding instantiates */
  enum Value:
    case Factory(symbol: Symbol)
    case Lit(literal: Literal)

  /** A validated binding of a context parameter */
  class Binding(val spec: InjectBinding, val param: Symbol, val value: Value):
    def option: String = spec.option

    def isInternal: Boolean = spec.internal

    /** The bindings whose values this binding reads, set when the graph is built */
    var deps: List[Binding] = Nil

  /** An injection specification
    *
    * The plan serves two purposes. The link step checks the implementation
    * of the target against it: the implementation may receive a provided
    * parameter in addition to the parameters the target declares, but no
    * internal one. When the plan is valid, the link step also synthesizes
    * the wrapper from it.
    *
    * @param target   the deferred function the bindings attach to
    * @param bindings the valid bindings in instantiation order
    * @param valid    whether every binding is valid. When a binding is in
    *                 error, the implementation of the target is not checked,
    *                 since no program is produced.
    * @param unreceived whether no factory receives a parameter
    */
  class Plan(val target: Symbol, val bindings: List[Binding], val valid: Boolean, unreceived: Symbol => Boolean):
    def isEmpty: Boolean = bindings.isEmpty

    lazy val provided: Set[Symbol] = bindings.filterNot(_.isInternal).map(_.param).toSet
    lazy val internal: Set[Symbol] = bindings.filter(_.isInternal).map(_.param).toSet

    /** The contract of `deferred` for diagnostics: its type `deferredType`,
      * and the provided parameters when `deferred` is the target
      */
    def showContract(deferred: Symbol, deferredType: Type)(using Definitions): String =
      val extra = if deferred == target && valid then bindings.filterNot(_.isInternal).map(_.param.fullName) else Nil

      if extra.isEmpty then deferredType.show
      else s"${deferredType.show}, with provided ${extra.mkString(", ")}"

    /** Whether `implType` conforms to `deferredType`, the type of `deferred`.
      * The rule is relaxed for the implementation of the target.
      */
    def conforms(deferred: Symbol, implType: Type, deferredType: Type)(using Definitions): Boolean =
      if deferred != target then Subtyping.conforms(implType, deferredType)
      else if !valid then true
      else Subtyping.conforms(dropReceives(implType, provided), deferredType)

    /** Report an error for each internal parameter that `impl`, the
      * implementation of `deferred`, receives. Returns whether any was reported.
      */
    def reportInternal(deferred: Symbol, impl: Symbol)(using defn: Definitions, rp: Reporter): Boolean =
      val received = if deferred == target && valid then internalReceived(impl.tpe, deferred.tpe) else Nil

      for param <- received do
        rp.error(s"${deferred.fullName} implementation ${impl.fullName} receives internal parameter ${param.fullName}")

      received.nonEmpty

    /** The internal parameters received by the implementation, when they are
      * the only reason it does not conform to the target
      */
    private def internalReceived(implType: Type, targetType: Type)(using Definitions): List[Symbol] =
      implType match
        case procType: ProcType
        if internal.nonEmpty
           && !Subtyping.conforms(dropReceives(implType, provided), targetType)
           && Subtyping.conforms(dropReceives(implType, provided ++ internal), targetType) =>
          procType.receives.filter(internal.contains)

        case _ => Nil

    private def dropReceives(tpe: Type, params: Set[Symbol])(using Definitions): Type =
      tpe match
        case procType: ProcType if params.nonEmpty =>
          procType.copy(receivesInfo = procType.receives.filterNot(params.contains))

        case _ => tpe

    /** Warn about internal parameters that no factory receives
      *
      * The warning is secondary: it is not issued next to an error.
      */
    def warnUnused()(using rp: Reporter): Unit =
      for binding <- bindings if binding.isInternal && unreceived(binding.param) do
        rp.warn(s"${binding.option}: no factory receives ${binding.param.fullName}")

    /** Generate the wrapper of `impl`, the implementation of the target
      *
      * The wrapper has the signature of the target. Its body instantiates the
      * bindings of parameters that the target does not receive, one nested
      * `with` each in instantiation order, then rebinds the parameters of the
      * target in a single `with`, and calls `impl` with the arguments of the
      * target unchanged. The right-hand sides of a `with` are evaluated in the
      * enclosing scope, so every factory receives the outer values of the
      * parameters of the target.
      */
    def synthesize(impl: Symbol)(using defn: Definitions): FunDef =
      val targetType = target.tpe.asProcType
      val targetReceives = targetType.receives
      val span = impl.span

      val wrapperType = targetType.copy(receivesInfo = targetReceives)
      val wrapperSym = TermSymbol.create(
        impl.name + "$injected", wrapperType, Flags.Fun | Flags.Synthetic, Visibility.Default, impl.owner, impl.sourcePos
      )

      def assign(binding: Binding): Assign =
        val rhs = binding.value match
          case Value.Factory(factory) => Ident(factory)(span).appliedTo()
          case Value.Lit(literal) => Literal(literal.constant)(span)

        Assign(Ident(binding.param)(span), rhs, isDefine = false)

      TreeOps.createFunDef(wrapperSym): (paramRefs, autoRefs) =>
        val call = Apply(Ident(impl)(span), paramRefs, autoRefs)(span)
        val (rebound, fresh) = bindings.partition(binding => targetReceives.contains(binding.param))

        val inner =
          if rebound.isEmpty then call
          else With(call, rebound.map(assign))

        fresh.foldRight(inner): (binding, body) =>
          With(body, assign(binding) :: Nil)

  /** Add the wrapper to the unit that defines `impl`, or to a unit of its own */
  def addWrapper(units: List[FileUnit], impl: Symbol, wrapper: FunDef): List[FileUnit] =
    def defines(unit: FileUnit): Boolean =
      var found = false
      unit.foreach:
        case fdef: FunDef if fdef.symbol == impl => found = true
        case _ =>
      found

    units.indexWhere(defines) match
      case -1 =>
        units :+ FileUnit(impl.owner.enclosingContainer, imports = Nil, defs = wrapper :: Nil, impl.source)

      case index =>
        val unit = units(index)
        units.updated(index, FileUnit(unit.owner, unit.imports, unit.defs :+ wrapper, unit.source))

  /** Validate the bindings and build the plan
    *
    * Every violation is an error that quotes the option. An internal
    * parameter no factory receives is a warning.
    */
  def plan(specs: List[InjectBinding])(using defn: Definitions, rp: Reporter): Plan =
    val target = defn.main
    val targetReceives = target.tpe.asProcType.receives

    var valid = true

    def fail(message: String): Unit =
      rp.error(message)
      valid = false

    // 1. Resolve the parameters and reject duplicates
    val declared = mutable.LinkedHashMap.empty[Symbol, InjectBinding]

    val resolved: List[(InjectBinding, Symbol)] =
      specs.flatMap: spec =>
        defn.resolveTermOpt(spec.param) match
          case None =>
            fail(s"${spec.option}: Failed to resolve symbol: ${spec.param}")
            None

          case Some(param) if !param.is(Flags.Context) =>
            fail(s"${spec.option}: ${spec.param} is not a context parameter")
            None

          case Some(param) =>
            declared.get(param) match
              case Some(first) =>
                fail(s"${spec.option}: ${param.fullName} is already bound by ${first.option}")
                None

              case None =>
                declared(param) = spec

                if spec.internal && targetReceives.contains(param) then
                  fail(s"${spec.option}: a context parameter of ${target.fullName} cannot be internal")
                  None
                else
                  Some((spec, param))

    // 2. Resolve the values
    //
    // The parameters received by factories are recorded as soon as a factory
    // resolves, so that a later error in its binding does not also make its
    // internal dependencies look unused.
    val received = mutable.Set.empty[Symbol]

    val bindings: List[Binding] =
      resolved.flatMap: (spec, param) =>
        val value =
          if isLiteral(spec.value) then readLiteral(spec, param.tpe)
          else if isValidPath(spec.value) then resolveFactory(spec, param.tpe, received)
          else
            fail(s"${spec.option}: expected a factory path or a literal")
            None

        if value.isEmpty then valid = false

        value.map(Binding(spec, param, _))

    val bound = bindings.map(binding => binding.param -> binding).toMap

    // 3. Build the binding graph
    for binding <- bindings do
      binding.value match
        case Value.Factory(factory) =>
          binding.deps =
            factory.tpe.asProcType.receives.flatMap: param =>
              if targetReceives.contains(param) then None
              else
                bound.get(param) match
                  case Some(dep) => Some(dep)

                  case None =>
                    if !declared.contains(param) then
                      fail(s"${binding.option}: factory receives ${param.fullName}, which is neither bound nor received by ${target.fullName}")
                    None

        case Value.Lit(_) =>

    // 4. Order the bindings by their dependencies and reject cycles
    val Visiting = 1
    val Done = 2
    val state = mutable.Map.empty[Binding, Int]
    val stack = mutable.ArrayBuffer.empty[Binding]
    val order = mutable.ArrayBuffer.empty[Binding]

    def visit(binding: Binding): Unit =
      state(binding) = Visiting
      stack += binding

      for dep <- binding.deps do
        state.get(dep) match
          case Some(Visiting) =>
            val cycle = stack.drop(stack.indexOf(dep)) :+ dep
            fail(s"${dep.option}: cycle among bindings: ${cycle.map(_.param.fullName).mkString(" -> ")}")

          case Some(_) =>

          case None => visit(dep)
      end for

      stack.remove(stack.size - 1)
      state(binding) = Done
      order += binding

    for binding <- bindings if !state.contains(binding) do visit(binding)

    Plan(target, order.toList, valid, unreceived = param => !received.contains(param))
  end plan

  /** A literal starts with a digit, a minus sign or a quote, or is a boolean keyword */
  private def isLiteral(value: String): Boolean =
    value == "true" || value == "false" || value.nonEmpty && {
      val c = value.head
      c.isDigit || c == '-' || c == '"' || c == '\''
    }

  private def resolveFactory
      (spec: InjectBinding, paramType: Type, received: mutable.Set[Symbol])
      (using defn: Definitions, rp: Reporter)
  : Option[Value] =
    val path = spec.value

    defn.resolveTermOpt(path) match
      case None =>
        if resolvesAsNonTerm(path) then
          rp.error(s"${spec.option}: $path is not a function")
        else
          rp.error(s"${spec.option}: Failed to resolve symbol: $path")
        None

      case Some(sym) if !sym.isFunction || sym.isOneOf(Flags.Method | Flags.Class | Flags.Constructor) =>
        rp.error(s"${spec.option}: $path is not a function")
        None

      case Some(factory) =>
        val procType = factory.tpe.asProcType
        received ++= procType.receives

        if procType.tparams.nonEmpty then
          rp.error(s"${spec.option}: a factory takes no type parameters")
          None

        else if procType.params.nonEmpty || procType.autos.nonEmpty then
          rp.error(s"${spec.option}: a factory takes no parameters")
          None

        else if !Subtyping.conforms(procType.resultType, paramType) then
          rp.error(s"${spec.option}: ${procType.resultType.show} does not conform to ${paramType.show}")
          None

        else
          Some(Value.Factory(factory))

  /** Whether the path names a type, such as a class, or a container */
  private def resolvesAsNonTerm(path: String)(using defn: Definitions): Boolean =
    val parts = path.split('.').toList
    Definitions.resolveStatic(defn.rootNameTable, parts, SymbolKind.Type).nonEmpty
    || Definitions.resolveStatic(defn.rootNameTable, parts, SymbolKind.Container).nonEmpty

  /** Read a literal with the lexer and type it with the parameter type as the
    * expected type, as it would be in source. Adapters are not applied.
    */
  private def readLiteral(spec: InjectBinding, paramType: Type)(using defn: Definitions, rp: Reporter): Option[Value] =
    val target = defn.main
    val span = target.span

    // Lexing and typing report against the value text. Their diagnostics
    // are re-reported without a position, prefixed with the option.
    val scratch = rp.fresh()
    given Source = new Source(spec.value)
    given Reporter = scratch
    given TargetType = TargetType.Known(paramType)

    val scanner = new Scanner(spec.value)
    val first = scanner.next()

    // The value must consist of exactly one literal. The raw text is checked
    // rather than the next token, which would skip a trailing comment.
    def consumesAll(end: Int): Boolean = end == spec.value.length

    val literal: Option[Literal] =
      first.token match
        case lit: Token.IntLit if consumesAll(first.span.endOffset) =>
          Some(NumericTyper.typeIntLit(Ast.IntLit(lit.value, lit.isHex)(span)))

        case Token.FloatLit(value) if consumesAll(first.span.endOffset) =>
          Some(NumericTyper.typeFloatLit(Ast.FloatLit(value)(span)))

        case Token.CharLit(value) if consumesAll(first.span.endOffset) =>
          Some(NumericTyper.typeCharLit(Ast.CharLit(value)(span)))

        case Token.BoolLit(value) if consumesAll(first.span.endOffset) =>
          Some(Literal(Constant.Bool(value))(span))

        case Token.StringStart(1) =>
          readString(scanner, spec).collect:
            case (value, end) if consumesAll(end) => Literal(Constant.String(value))(span)

        case _ => None

    for diag <- scratch.reports do
      diag.kind match
        case Kind.Error => rp.error(s"${spec.option}: ${messageOf(diag)}")
        case Kind.Warning => rp.warn(s"${spec.option}: ${messageOf(diag)}")
        case Kind.Info =>

    literal match
      case None =>
        if !scratch.hasErrors then
          rp.error(s"${spec.option}: expected a factory path or a literal")
        None

      case Some(_) if scratch.hasErrors => None

      case Some(lit) =>
        if !Subtyping.conforms(lit.tpe, paramType) then
          rp.error(s"${spec.option}: ${lit.tpe.widen.show} does not conform to ${paramType.show}")
          None
        else
          Some(Value.Lit(lit))

  /** Read the content of a single-line string literal after its opening
    * quote, returning it with the offset just past the closing quote
    */
  private def readString(scanner: Scanner, spec: InjectBinding)(using rp: Reporter): Option[(String, Int)] =
    val content = new StringBuilder

    while true do
      val info = scanner.nextString(1)

      info.token match
        // The scanner reports a line break inside a single-line string as an
        // empty line without consuming it, as the parser also treats it.
        case Token.StringLine(text) if text.isEmpty =>
          rp.error("Unclosed string literal")
          return None

        case Token.StringLine(text) =>
          content ++= text

        case Token.StringEnd =>
          try return Some((StringUtil.unescape(content.toString, StringUtil.EscapePolicy.Disable("")), info.span.endOffset))
          catch
            case e: StringUtil.EscapeError =>
              rp.error(e.message)
              return None

        case Token.InterpolationStart =>
          rp.error("a string literal may not contain interpolation")
          return None

        case _ =>
          rp.error("unclosed string literal")
          return None
    end while

    None

  private def messageOf(diag: Diagnostic): String =
    diag match
      case item: ReportItem => item.message
      case item: UnpositionedReportItem => item.message
      case other => other.toString
end Injection

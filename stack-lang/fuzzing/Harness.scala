package fuzzing

import java.util.concurrent.TimeoutException

import sast.Definitions
import sast.NameTable

import parsing.Parser

import reporting.Reporter
import reporting.Reporter.FatalError
import reporting.Config

import typing.Typer

/** Which compiler phases to exercise on an input. */
enum Target:
  case Parse, Type

/** Reason a crash was flagged as a bug. */
enum CrashKind:
  /** Compiler raised `FatalError.InternalError` — author explicitly flagged this path as buggy. */
  case InternalError

  /** Any other uncaught `Throwable` from the compiler (AssertionError, NullPointerException,
    * MatchError, StackOverflowError, IllegalStateException, ...). */
  case Uncaught

/** The classified result of running the compiler on one input. */
enum Outcome:
  /** Compiler accepted the input with no errors. */
  case Ok

  /** Compiler rejected the input via its normal error channel. Not a bug. */
  case Rejected

  /** Compiler crashed. This is a bug. */
  case Crashed(kind: CrashKind, throwable: Throwable)

  /** Compiler exceeded the per-input wall-clock budget. Tracked separately from crashes. */
  case Timeout

/** The single point of contact between the fuzzer and the compiler. Runs one input,
  * classifies the outcome, and isolates the compiler state so that consecutive calls
  * don't leak reporters or symbol tables across iterations.
  */
object Harness:

  val defaultTimeoutSeconds: Int = 10

  /** Bug oracle:
    *   - `FatalError.CodeError`, `FatalError.StopAfterPhase`, `Parser.SyntaxError` → Rejected
    *   - `FatalError.InternalError`                                                → Crashed(InternalError)
    *   - Anything else uncaught                                                    → Crashed(Uncaught)
    *   - Reporter-reported errors without an exception                             → Rejected
    *   - Wall-clock overrun                                                        → Timeout
    */
  def run(sourceFile: String, target: Target, timeoutSeconds: Int = defaultTimeoutSeconds): Outcome =
    try
      Reporter.timeout(timeoutSeconds):
        runUnsafe(sourceFile, target)
    catch
      case _: TimeoutException            => Outcome.Timeout
      case e: FatalError.InternalError    => Outcome.Crashed(CrashKind.InternalError, e)
      case _: FatalError.CodeError        => Outcome.Rejected
      case _: FatalError.StopAfterPhase   => Outcome.Rejected
      case _: Parser.SyntaxError          => Outcome.Rejected
      case e: Throwable                   => Outcome.Crashed(CrashKind.Uncaught, e)

  private def runUnsafe(sourceFile: String, target: Target): Outcome =
    given rp: Reporter = Reporter.createReporter(buffer = true)
    given Config       = Config(Map.empty)

    target match
      case Target.Parse =>
        (sourceFile :: Nil) |> Typer.parseStep

      case Target.Type =>
        val rootNameTable = new NameTable
        given lazyDefn: Definitions.Lazy = Definitions.Lazy(rootNameTable)
        (sourceFile :: Nil) |> Typer.parseStep |> Typer.typeStep

    if rp.hasErrors then Outcome.Rejected else Outcome.Ok
  end runUnsafe

end Harness

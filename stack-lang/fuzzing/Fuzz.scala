package fuzzing

import java.nio.file.Files

import scala.util.Random

/** Command-line entry point for the fuzzer.
  *
  * Subcommands:
  *   `fuzz parse  [options]`         byte-mutate seeds, feed the parser
  *   `fuzz type   [options]`         byte-mutate seeds, feed parse + typer
  *   `fuzz replay FILE [parse|type]` run a single file, print outcome
  */
object Fuzz:

  private val Usage: String =
    """Usage:
      |  fuzz parse  [options]
      |  fuzz type   [options]
      |  fuzz replay [--parse | --type] FILE    (default: --type)
      |
      |Options for `parse` and `type`:
      |  --seeds DIR        seed corpus                 (default: tests/pos)
      |  --out   DIR        findings output dir         (default: out/fuzz)
      |  --iters N          max iterations              (default: unlimited)
      |  --budget S         wall-clock budget, seconds  (default: 300)
      |  --rng-seed N       RNG seed for reproducibility
      |  --timeout S        per-input timeout, seconds  (default: 10)
      |  --mutator K        token | byte                (default: token)
      |  --no-reduce        skip the reduction step for new crashes
      |  --verbose          print progress every 200 iterations
      |""".stripMargin

  def main(args: Array[String]): Unit =
    args.toList match
      case "parse"  :: rest => runFuzz(Target.Parse, rest)
      case "type"   :: rest => runFuzz(Target.Type, rest)
      case "replay" :: rest => runReplay(rest)
      case _                =>
        System.err.println(Usage)
        System.exit(1)

  //--------------------------------------------------------------------------
  // `replay`

  private def runReplay(args: List[String]): Unit =
    def go(xs: List[String], target: Target, file: Option[String]): (Target, String) = xs match
      case Nil =>
        file match
          case Some(f) => (target, f)
          case None    =>
            System.err.println("replay: expected a FILE argument\n")
            System.err.println(Usage)
            System.exit(1)
            (target, "")

      case "--parse" :: rest => go(rest, Target.Parse, file)
      case "--type"  :: rest => go(rest, Target.Type,  file)

      case arg :: rest if !arg.startsWith("--") && file.isEmpty =>
        go(rest, target, Some(arg))

      case bad :: _ =>
        System.err.println(s"Unknown replay argument: $bad\n")
        System.err.println(Usage)
        System.exit(1)
        (target, "")
    end go

    val (target, file) = go(args, Target.Type, None)

    Harness.run(file, target) match
      case Outcome.Ok            => println(s"[ok] $file")
      case Outcome.Rejected      => println(s"[rejected] $file")
      case Outcome.Timeout       => println(s"[timeout] $file")
      case c @ Outcome.Crashed(_, t) =>
        val fp = Oracle.fingerprint(c, target).get
        println(s"[crashed] ${fp.display}")
        println()
        println(s"${t.getClass.getName}: ${t.getMessage}")
        for f <- t.getStackTrace.take(20) do println("    at " + f)
  end runReplay

  //--------------------------------------------------------------------------
  // `parse` / `type`

  private case class Options(
      seeds:          String  = "tests/pos",
      out:            String  = "out/fuzz",
      iters:          Int     = Int.MaxValue,
      budgetSeconds:  Int     = 300,
      rngSeed:        Long    = System.currentTimeMillis(),
      timeoutSeconds: Int     = Harness.defaultTimeoutSeconds,
      mutatorKind:    String  = "token",
      reduce:         Boolean = true,
      verbose:        Boolean = false,
  )

  private def parseOptions(args: List[String]): Options =
    def go(xs: List[String], acc: Options): Options = xs match
      case Nil                         => acc
      case "--seeds"    :: v :: rest   => go(rest, acc.copy(seeds          = v))
      case "--out"      :: v :: rest   => go(rest, acc.copy(out            = v))
      case "--iters"    :: v :: rest   => go(rest, acc.copy(iters          = v.toInt))
      case "--budget"   :: v :: rest   => go(rest, acc.copy(budgetSeconds  = v.toInt))
      case "--rng-seed" :: v :: rest   => go(rest, acc.copy(rngSeed        = v.toLong))
      case "--timeout"  :: v :: rest   => go(rest, acc.copy(timeoutSeconds = v.toInt))
      case "--mutator"  :: v :: rest   => go(rest, acc.copy(mutatorKind    = v))
      case "--no-reduce" :: rest       => go(rest, acc.copy(reduce         = false))
      case "--verbose"  :: rest        => go(rest, acc.copy(verbose        = true))
      case bad :: _                    =>
        System.err.println(s"Unknown option: $bad\n")
        System.err.println(Usage)
        System.exit(1)
        acc

    go(args, Options())
  end parseOptions

  private def runFuzz(target: Target, args: List[String]): Unit =
    val opt   = parseOptions(args)
    val seeds = Corpus.load(opt.seeds :: Nil)

    if seeds.isEmpty then
      System.err.println(s"No .jo seeds found under: ${opt.seeds}")
      System.exit(1)

    val mutator: Mutator = opt.mutatorKind match
      case "byte"  => ByteMutator
      case "token" => TokenMutator
      case bad     =>
        System.err.println(s"Unknown mutator: $bad (expected: byte | token)")
        System.exit(1)
        ByteMutator

    println(s"fuzz: target=$target seeds=${seeds.size} mutator=${opt.mutatorKind} rng-seed=${opt.rngSeed} budget=${opt.budgetSeconds}s")

    val rng       = new Random(opt.rngSeed)
    val seedBytes = seeds.map(_.content)
    val findings  = new Findings(opt.out, mutatorKind = opt.mutatorKind, verbose = opt.verbose)
    val deadline  = System.currentTimeMillis() + opt.budgetSeconds * 1000L
    val tmp       = Files.createTempFile("fuzz-", ".jo")
    val startMs   = System.currentTimeMillis()

    var iter = 0
    try
      while iter < opt.iters && System.currentTimeMillis() < deadline do
        val seed    = seeds(rng.nextInt(seeds.size))
        val mutated = mutator.mutate(seed.content, rng, seedBytes)
        Files.write(tmp, mutated)

        Harness.run(tmp.toString, target, opt.timeoutSeconds) match
          case c @ Outcome.Crashed(_, t) =>
            val fp    = Oracle.fingerprint(c, target).get
            val isNew = findings.recordCrash(fp, mutated, t)
            if isNew && opt.reduce then
              val reduced = Reducer.reduce(mutated, target, fp)
              Files.write(findings.bucketDir(fp).resolve("reduced.jo"), reduced)

          case Outcome.Timeout => findings.recordTimeout()
          case _               => ()

        iter += 1
        if opt.verbose && iter % 200 == 0 then
          val elapsedS = math.max(1, (System.currentTimeMillis() - startMs) / 1000)
          println(s"[$iter iters, ${iter / elapsedS}/s] ${findings.summary}")
      end while

    finally
      Files.deleteIfExists(tmp)

    println()
    println(s"done: $iter iterations, ${findings.summary}")
  end runFuzz

end Fuzz

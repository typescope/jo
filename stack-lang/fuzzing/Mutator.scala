package fuzzing

import scala.util.Random

/** Strategy for turning a seed input into a mutated candidate.
  *
  * Two strategies live in this package:
  *   - [[ByteMutator]]  — language-oblivious byte operations. Cheapest, always
  *     makes progress even on non-UTF-8 or malformed input. Exercises the
  *     scanner and parser error-recovery paths.
  *   - [[TokenMutator]] — operates on scanner tokens, so mutations preserve
  *     identifier/keyword/operator shape and (usually) indentation. Produces
  *     inputs more likely to reach the typer.
  *
  * Both strategies share the [[Mutator.Keywords]] / [[Mutator.Operators]] /
  * [[Mutator.Punctuation]] pools below.
  */
trait Mutator:
  def mutate(input: Array[Byte], rng: Random, others: IndexedSeq[Array[Byte]]): Array[Byte]

object Mutator:

  private[fuzzing] val Keywords: Array[String] = Array(
    "allow", "auto", "as", "begin", "case", "break", "class", "continue",
    "def", "defer", "do", "else", "end", "extend", "extension", "false",
    "for", "if", "import", "in", "interface", "is", "like", "match",
    "namespace", "new", "object", "param", "pattern", "private", "receives",
    "return", "section", "then", "true", "type", "union", "val", "var",
    "view", "while", "with",
  )

  private[fuzzing] val Operators: Array[String] = Array(
    "+", "-", "*", "/", "%", "|", "&", "^", ">", "<", "=", "!", "?",
    "==", "!=", "<=", ">=", "&&", "||", "::", ":=", "..", "...",
    "=>", "?=>", ":", ".",
  )

  private[fuzzing] val Punctuation: Array[String] = Array("(", ")", "[", "]", "{", "}", ",", ";")

end Mutator

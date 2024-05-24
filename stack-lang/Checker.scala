import Sast.*

import Reporter.Span

/**
  * Check stack safety
  *
  * - Fences can execute with empty value stack.
  * - Value and function definitions work with empty value stack and result in
  *   one value.
  * - The condition of an if-statement works as a fence and result in one value.
  * - Empty stack is never popped.
  */
class Checker(using Reporter):
  import Checker.ValueStack

  def check(word: Word)(using vs: ValueStack): Unit =
    word match
      case _: Word.IntLit | _: Word.BoolLit => vs.push(1)

      case Word.Assign(sym, words) =>
        vs.expect(0, "No result expected before assignment", word.pos)

        check(words)

        vs.expect(1, "1 result expected for " + sym, word.pos)
        vs.clear()

      case Word.If(cond, thenp, elsep) =>
        val vsCond = new ValueStack
        check(cond)(using vsCond)

        vsCond.expect(
          1, "1 result expected for if condition",
          cond.head.pos | cond.last.pos)

        val vs1 = new ValueStack
        val vs2 = new ValueStack
        check(thenp)(using vs1)
        check(elsep)(using vs2)

        vs1.checkSame(
          vs2,
          s"Expect both branches of if/else have the same stack state",
          word.pos)

        if vs2.isError then
          vs.setError()
        else
          vs2.commit(vs)

      case Word.While(cond, body) =>
        vs.expect(0, "No result expected before while loop", word.pos)
        vs.clear()

        check(cond)

        vs.pop(
          1, "1 result expected for if condition",
          cond.head.pos | cond.last.pos)

        check(body)

        vs.expect(0, "No result expected in while loop", body.last.pos)
        vs.clear()

      case Word.Ident(sym) =>
        val info = sym.info
        val need = info.paramCount

        vs.pop(need, s"$need elements expected for $sym", word.pos)
        vs.push(info.resCount)

  def check(words: List[Word])(using vs: ValueStack): Unit =
    for word <- words do check(word)

  def check(fun: Fun): Unit =
    val vs = new ValueStack
    check(fun.body)(using vs)

    val sym = fun.symbol
    val resCount = sym.info.resCount
    vs.expect(resCount, s"$resCount result(s) expected for $sym", fun.pos)

object Checker:
  /**
    * Represent the number of values on the value stack.
    *
    * Used to check stack safety.
    */
  class ValueStack:
    /** Don't expose size in order to handle errors */
    private var size: Int = 0

    def setError() =
      size = -1

    def isError = size < 0

    def checkSame(that: ValueStack, msg: String, pos: Span)(using Reporter) =
      if isError then
        that.setError()
      else if that.isError then
        this.setError()
      else if this.size != that.size then
        this.setError()
        that.setError()
        Reporter.error(s"$msg, found = $size and ${that.size}", pos)

    def expect(sizeExpect: Int, msg: String, pos: Span)(using Reporter): Unit =
      if !isError && this.size != sizeExpect then
        Reporter.error(s"$msg, found = $size", pos)
        setError()

    def commit(vs: ValueStack): Unit =
      assert(!isError)
      vs.push(size)

    def push(n: Int) =
      if n < 0 then setError()
      else size += n

    def clear() = size = 0
    def pop(n: Int, msg: String, pos: Span)(using Reporter) =
      if !isError && size < n then
        Reporter.error(s"$msg, found = $size", pos)
        setError()
      else
        size -= n

import Sast.*

/**
  * Check stack safety
  *
  * - Fences can execute with empty value stack.
  * - Value and function definitions work with empty value stack and result in
  *   one value.
  * - The condition of an if-statement works as a fence and result in one value.
  * - Empty stack is never popped.
  */
object Checker:
  /**
    * Represent the number of values on the value stack.
    *
    * Used to check stack safety.
    */
  class ValueStack:
    private var size0: Int = 0

    def size: Int = size0
    def push(n: Int) = size0 += n
    def pop(n: Int)  =
      assert(size0 >= n)
      size0 -= n


  def check(word: Word)(using vs: ValueStack): Unit =
    word match
      case _: Word.IntLit | _: Word.BoolLit => vs.push(1)

      case Word.If(cond, thenp, elsep) =>
        val vsCond = new ValueStack
        check(cond)(using vsCond)

        if vsCond.size != 1 then
          throw new Exception(
              "1 result expected for if condition, found = " + vsCond.size)

        val vs1 = new ValueStack
        val vs2 = new ValueStack
        check(thenp)(using vs1)
        check(elsep)(using vs2)

        if vs1.size != vs2.size then
          throw new Exception(
              "Branches of if should end up with the same stack state, found = "
              + vs1.size + " and " + vs2.size)

        vs.push(vs1.size)

      case Word.Ident(sym) =>
        val info = sym.info
        val need = info.paramCount
        val found = vs.size

        if found < need then
          throw new Exception(
              s"$need elements expected for $sym, found = $found")
        vs.pop(need)
        vs.push(info.resCount)

  def check(words: List[Word])(using vs: ValueStack): Unit =
    for word <- words do check(word)

  def check(defn: Def): Unit =
    defn match
      case valDef: Def.ValDef =>
        val vs = new ValueStack
        check(valDef.words)(using vs)

        if vs.size != 1 then
          throw new Exception(
              "1 result expected for " + defn.symbol + ", found = " + vs.size)

      case funDef: Def.FunDef =>
        val vs = new ValueStack
        check(funDef.words)(using vs)

        val sym = funDef.symbol
        val resCount = sym.info.resCount
        val size = vs.size
        if size != resCount then
          throw new Exception(
              s"$resCount result(s) expected for $sym, found = $size")

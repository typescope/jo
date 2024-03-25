import java.io.PrintWriter
import scala.collection.mutable

import Sast.*
import Symbol.{ PrimSymbol, FunSymbol }

/**
  * JavaScript platform with code optimization
  */
class JSPlatformOpt(outFile: String) extends Platform:
  private val pw =  new PrintWriter(outFile)

  private  val uniqueName = new UniqueName
  export uniqueName.freshName

  // Make keywords unavailable
  List(
    "for", "while", "function", "var", "let", "break", "continue", "if",
    "const", "class", "constructor"
  ).foreach: w =>
    freshName(w)

  class ValueStack:
    val stack: mutable.ArrayBuffer[String] = new mutable.ArrayBuffer

    def pop(): String =
      if stack.nonEmpty then stack.remove(stack.size - 1)
      else throw new Exception("Stack is empty")

    def pop(n: Int): Unit =
      stack.dropRightInPlace(n)

    def push(v: String): Unit = stack.append(v)

    def peek(i: Int): String = stack(i)

    def clear() = stack.clear()

    def size: Int = stack.size

    override def toString() = stack.toString()

    def asSingleJSValue(): String =
      val count = this.size
      assert(count > 0, "Empty stack")
      if count == 1 then
        pop()
      else
        var i = 0
        val arrayItemsStr = new StringBuilder
        while i < count do
          val item = vs.peek(i)
          arrayItemsStr.append(item)
          if i != count - 1 then
            arrayItemsStr.append(", ")
          i = i + 1

        stack.clear()
        s"[$arrayItemsStr]"

  private val vs: ValueStack = new ValueStack

  private val symbol2UniqueName: mutable.Map[Symbol, String] = mutable.Map.empty

  private var indentCount = 0
  private def addLine(code: String): Unit =
    pw.append("  " * indentCount).append(code).append("\n")

  private def newLine(): Unit =
    pw.append("\n")

  private def indent(work: => Unit): Unit =
    indentCount += 1
    work
    indentCount -= 1

  def entry(init: => Unit): Unit =
    newLine()
    init
    newLine()

  def mapSymbolToJSName(sym: Symbol): String =
    val isOperator = !sym.name(0).isLetter
    val uniqueName =
      if isOperator then freshName("operator")
      else freshName(sym.name)

    symbol2UniqueName(sym) = uniqueName
    uniqueName

  def declare(sym: Symbol): Unit =
    assert(!sym.isPrim, "Unexpected primitive symbol " + sym)
    val uniqueName = mapSymbolToJSName(sym)
    if sym.isVal then
      addLine(s"var $uniqueName; // ${sym.name}")

  /**
    * Call the funtion.
    */
  def call(fun: FunSymbol): Unit =
    val name = symbol2UniqueName(fun)
    val paramCount = fun.info.paramCount
    val resCount = fun.info.resCount
    var i: Int = 0
    val paramStr = new StringBuilder
    while i < paramCount  do
      val arg = vs.peek(vs.size - paramCount + i)
      paramStr.append(arg)
      if i != paramCount - 1 then
        paramStr.append(", ")
      i = i + 1

    vs.pop(paramCount)

    if resCount == 0 then
      addLine(s"$name($paramStr);");
    else if resCount == 1 then
      vs.push(s"$name($paramStr)");
    else
      // result binding
      val resName = freshName("res")
      addLine(s"const $resName = $name($paramStr);");
      i = 0
      while i < resCount  do
        vs.push(s"$resName[$i]")
        i = i + 1

  /** Initialize a value definition
    *
    * Calling the passed function will compile the initializer.
    */
  def initVal(sym: Symbol, initializer: () => Unit): Unit =
    vs.clear()
    initializer()
    val name = symbol2UniqueName(sym)
    val rhs = vs.pop()
    addLine(s"$name = $rhs;")

  /** Compile a function
    *
    * Calling the passed function will compile the body of the function.
    */
  def function(sym: FunSymbol, params: List[Symbol], body: () => Unit): Unit =
    vs.clear()
    val name = symbol2UniqueName(sym)
    val resCount = sym.info.resCount
    uniqueName.newScope:
      val paramStr = params.map(mapSymbolToJSName).mkString(", ")
      addLine(s"function $name($paramStr) { // ${sym.name}")
      indent:
        body()
        assert(vs.size == resCount, s"Stack size mismatch, expect $resCount, found = " + vs)
        val retStr = vs.asSingleJSValue()
        addLine(s"return $retStr;")

      addLine("}\n")

  /** Compile a conditional statement, i.e if/then/else */
  def conditional(ifWord: Word.IfStat, compile: List[Word] => Unit): Unit =
    val resCount = ifWord.info.resCount
    compile(ifWord.cond)
    val condStr = vs.pop()
    if resCount == 0 then
      addLine(s"if ($condStr) {")
      indent:
        compile(ifWord.thenp)
        assert(vs.size == 0, "Expect empty stack, found = " + vs)
      if ifWord.elsep.nonEmpty then
        addLine("} else {")
        indent:
          compile(ifWord.elsep)
      addLine("}")
    else
      assert(ifWord.elsep.nonEmpty)
      val resName = freshName("resIf")
      addLine(s"let $resName;")
      addLine(s"if ($condStr) {")
      indent:
        compile(ifWord.thenp)
        assert(vs.size == resCount, s"Stack size mismatch, expect = $resCount, found = " + vs)
        val retStr = vs.asSingleJSValue()
        addLine(s"$resName = $retStr;")
      addLine("} else {")
      indent:
        compile(ifWord.elsep)
        val retStr = vs.asSingleJSValue()
        addLine(s"$resName = $retStr;")
      addLine("}")

      if resCount == 1 then
        vs.push(resName);
      else
        var i = 0
        while i < resCount  do
          vs.push(s"$resName[$i]")
          i = i + 1

  /** Push an integer literal to value stack */
  def push(v: Int): Unit =
    vs.push(v.toString)

  /** Push a Boolean literal to value stack */
  def push(v: Boolean): Unit =
    vs.push(v.toString)

  /** Push the value associated with the given symbol to value stack */
  def push(sym: Symbol): Unit =
    val name = symbol2UniqueName(sym)
    vs.push(name)

  def binary(op: String): Unit =
    val operand2 = vs.pop()
    val operand1 = vs.pop()
    vs.push(s"($operand1 $op $operand2)")

  def div(): Unit =
    val operand2 = vs.pop()
    val operand1 = vs.pop()
    vs.push(s"(($operand1 / $operand2)>>0)")

  def bnot(): Unit =
    val operand = vs.pop()
    vs.push(s"(!$operand)")

  def print(): Unit =
    val operand = vs.pop()
    addLine(s"console.log($operand);")

  /**
    * Compile a primitive
    *
    */
  def primitive(sym: PrimSymbol): Unit =
    sym match
      case predef.add    =>   binary("+")
      case predef.sub    =>   binary("-")
      case predef.mul    =>   binary("*")
      case predef.div    =>   div()
      case predef.mod    =>   binary("%")
      case predef.gt     =>   binary(">")
      case predef.lt     =>   binary("<")
      case predef.ge     =>   binary(">=")
      case predef.le     =>   binary("<=")
      case predef.srl    =>   binary(">>")
      case predef.sll    =>   binary("<<")
      case predef.land   =>   binary("&")
      case predef.lor    =>   binary("|")
      case predef.lxor   =>   binary("^")
      case predef.band   =>   binary("&&")
      case predef.bor    =>   binary("||")
      case predef.bnot   =>   bnot()
      case predef.eql    =>   binary("===")
      case predef.p      =>   print()
      case _             =>   throw new Exception("Unknown primitive: " + sym.name)
  end primitive


  /** Prepare to start the compilation */
  def start(): Unit =
    addLine("(function() {")
    indentCount += 1

  /** Finish compilation */
  def finish(): Unit =
    if vs.size > 0 then
      addLine(vs.asSingleJSValue())
    indentCount -= 1
    addLine("})()")
    pw.close()

end JSPlatformOpt

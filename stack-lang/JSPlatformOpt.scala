import java.io.PrintWriter
import scala.collection.mutable

import Sast.*

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

  enum Item:
    case Const(value: String)
    case Ref(name: String)
    case Expr(code: String)

    def toJS: String =
      this match
        case Const(v)   =>  v
        case Ref(n)     =>  n
        case Expr(e)    =>  e

  class ValueStack:
    val stack: mutable.ArrayBuffer[Item] = new mutable.ArrayBuffer

    def pop(): String =
      if stack.nonEmpty then stack.remove(stack.size - 1).toJS
      else throw new Exception("Stack is empty")

    def pop(n: Int): Seq[String] =
      assert(this.size >= n, s"size = $size, n = $n")
      val slice = stack.slice(this.size - n, this.size)
      stack.dropRightInPlace(n)
      slice.map(_.toJS).toSeq

    def push(v: Item): Unit = stack.append(v)

    def get(i: Int): Item = stack(i)

    def set(i: Int, v: Item): Unit = stack(i) = v

    def clear() = stack.clear()

    def size: Int = stack.size

    override def toString() = stack.toString()

    def combineToJS(): String = combineToJS(this.size)

    /**
      * Combine the number of items to a JS code.
      *
      * Multiple elements are wrapped in an array.
      */
    def combineToJS(count: Int): String =
      assert(count > 0, "Nothing to do for count == 0")
      assert(count <= size, s"Expect $count items, but size is ${size}")
      if count == 1 then
        pop()
      else
        var i = this.size - count
        val arrayItemsStr = new StringBuilder
        while i < this.size do
          val item = vs.get(i)
          arrayItemsStr.append(item.toJS)
          if i != count - 1 then
            arrayItemsStr.append(", ")
          pop()
          i = i + 1

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
    assert(!sym.isPrimitive, "Unexpected primitive symbol " + sym)
    val uniqueName = mapSymbolToJSName(sym)
    if sym.isValue then
      addLine(s"var $uniqueName; // ${sym.name}")

  /**
    * Bind all values in stack.
    *
    * This happens when the chain of computation is interrupted by an effectful
    * operation such as printing or operations that return multiple values.
    *
    * Given that computations in the stack may produce effects as well, we need
    * to maintain the order of effects.
    *
    * When it happens, we do the following:
    *
    * 1. Bind all expressions in the stack to variables in the generated code.
    *
    * 2. Replace the corresponding expressions with the variables.
    *
    * 3. Generate code for the effectful operation.
    *
    * As an optimization, a binding can be avoided if the stack item is not
    * effectful.
    */
  def bindExpressions(): Unit =
    val count = vs.size
    var i = 0
    while i < count do
      val item = vs.get(i)
      item match
        case Item.Expr(e) =>
          val name = freshName("x")
          addLine(s"const $name = $e;")
          vs.set(i, Item.Ref(name))
        case _ =>
      i = i + 1

  /**
    * Call the funtion.
    */
  def call(fun: Symbol): Unit =
    val name = symbol2UniqueName(fun)
    val paramCount = fun.info.paramCount
    val resCount = fun.info.resCount
    var i: Int = 0
    val args = vs.pop(paramCount)
    val argsStr = args.mkString(", ")

    if resCount == 0 then
      bindExpressions()
      addLine(s"$name($argsStr);")
    else if resCount == 1 then
      vs.push(Item.Expr(s"$name($argsStr)"))
    else
      bindExpressions()
      // result binding
      val resName = freshName("res")
      addLine(s"const $resName = $name($argsStr);");
      i = 0
      while i < resCount  do
        vs.push(Item.Ref(s"$resName[$i]"))
        i = i + 1

  /** Initialize a value definition
    *
    * Calling the passed function will compile the initializer.
    */
  def initVal(vdef: Def.ValDef, compile: Compiler): Unit =
    vs.clear()
    compile(vdef.words)
    val name = symbol2UniqueName(vdef.symbol)
    val rhs = vs.pop()
    addLine(s"$name = $rhs;")

  /** Compile a function
    *
    * Calling the passed function will compile the body of the function.
    */
  def function(fdef: Def.FunDef, compile: Compiler): Unit =
    vs.clear()
    val sym = fdef.symbol
    val name = symbol2UniqueName(sym)
    val resCount = sym.info.resCount
    uniqueName.newScope:
      val paramStr = fdef.params.map(mapSymbolToJSName).mkString(", ")
      addLine(s"function $name($paramStr) { // ${sym.name}")
      indent:
        compile(fdef.words)
        assert(vs.size == resCount, s"Stack size mismatch, expect $resCount, found = " + vs)
        val retStr = vs.combineToJS()
        addLine(s"return $retStr;")

      addLine("}\n")

  /** Compile a conditional statement, i.e if/then/else */
  def conditional(ifword: Word.If, compile: List[Word] => Unit): Unit =
    bindExpressions()

    val resCount = ifword.info.resCount
    compile(ifword.cond)

    val condStr = vs.pop()
    if resCount == 0 then
      addLine(s"if ($condStr) {")
      indent:
        compile(ifword.thenp)
        assert(vs.size == 0, "Expect empty stack, found = " + vs)

      if ifword.elsep.nonEmpty then
        addLine("} else {")
        indent:
          compile(ifword.elsep)
      addLine("}")

    else
      assert(ifword.elsep.nonEmpty)

      val resName = freshName("resIf")
      addLine(s"let $resName;")

      addLine(s"if ($condStr) {")
      indent:
        compile(ifword.thenp)
        assert(vs.size == resCount, s"Stack size mismatch, expect = $resCount, found = " + vs)
        val retStr = vs.combineToJS(resCount)
        addLine(s"$resName = $retStr;")
      addLine("} else {")
      indent:
        compile(ifword.elsep)
        val retStr = vs.combineToJS(resCount)
        addLine(s"$resName = $retStr;")
      addLine("}")

      if resCount == 1 then
        vs.push(Item.Ref(resName));
      else
        var i = 0
        while i < resCount  do
          vs.push(Item.Ref(s"$resName[$i]"))
          i = i + 1

  /** Push an integer literal to value stack */
  def push(v: Int): Unit =
    vs.push(Item.Const(v.toString))

  /** Push a Boolean literal to value stack */
  def push(v: Boolean): Unit =
    vs.push(Item.Const(v.toString))

  /** Push the value associated with the given symbol to value stack */
  def push(sym: Symbol): Unit =
    val name = symbol2UniqueName(sym)
    vs.push(Item.Ref(name))

  def binary(op: String): Unit =
    val operand2 = vs.pop()
    val operand1 = vs.pop()
    vs.push(Item.Expr(s"($operand1 $op $operand2)"))

  def div(): Unit =
    val operand2 = vs.pop()
    val operand1 = vs.pop()
    vs.push(Item.Expr(s"(($operand1 / $operand2)>>0)"))

  def bnot(): Unit =
    val operand = vs.pop()
    vs.push(Item.Expr(s"(!$operand)"))

  def print(): Unit =
    val operand = vs.pop()
    bindExpressions()
    addLine(s"console.log($operand);")

  /**
    * Compile a primitive
    *
    */
  def primitive(sym: Symbol): Unit =
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
      addLine(vs.combineToJS())
    indentCount -= 1
    addLine("})()")
    pw.close()

end JSPlatformOpt

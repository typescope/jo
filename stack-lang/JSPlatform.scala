import java.io.PrintWriter
import scala.collection.mutable

import Sast.{ predef, Symbol }

/**
  * JavaScript platform
  */
class JSPlatform(outFile: String) extends Platform:
  private val pw =  new PrintWriter(outFile)

  private  val uniqueName = new UniqueName
  export uniqueName.freshName

  // Make keywords unavailable
  List(
    "for", "while", "function", "var", "let", "break", "continue", "if",
    "const", "class", "constructor"
  ).foreach: w =>
    freshName(w)

  private val vs: String = freshName("_valueStack")
  private val pop: String = freshName("pop")
  private val push: String = freshName("push")

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

  /** Declare the symbol to the platform as a preparation for compilation */
  def declare(sym: Symbol): Unit =
    val isOperator = !sym.name(0).isLetter
    val uniqueName =
      if isOperator then freshName("operator")
      else freshName(sym.name)

    symbol2UniqueName(sym) = uniqueName

    if sym.isVal then
      addLine(s"var $uniqueName; // ${sym.name}")


  /**
    * Call the funtion.
    */
  def call(fun: Symbol): Unit =
    val name = symbol2UniqueName(fun)
    addLine(s"$name();");

  /** Initialize a value definition
    *
    * Calling the passed function will compile the initializer.
    */
  def initVal(sym: Symbol, initializer: () => Unit): Unit =
    initializer()
    val name = symbol2UniqueName(sym)
    addLine(s"$name = $pop();")

  /** Compile a function
    *
    * Calling the passed function will compile the body of the function.
    */
  def function(sym: Symbol, body: () => Unit): Unit =
    val name = symbol2UniqueName(sym)
    addLine(s"function $name() { // ${sym.name}")
    indent:
      uniqueName.newScope:
        body()
    addLine("}\n")

  /** Push an integer literal to value stack */
  def push(v: Int): Unit =
    addLine(s"$push($v);")


  /** Push a Boolean literal to value stack */
  def push(v: Boolean): Unit =
    addLine(s"$push($v);")

  /** Push the value associated with the given symbol to value stack */
  def push(sym: Symbol): Unit =
    val name = symbol2UniqueName(sym)
    addLine(s"$push($name);")

  /** Push a procedure literal to value stack
    *
    * Calling the passed function will compile the body of the procedure.
    */
  def push(proc: () => Unit): Unit =
    addLine(s"$push(() => {")
    indent:
      uniqueName.newScope:
        proc()
    addLine("});")

  def choose(): Unit =
    val local = freshName("choose_tmp")
    addLine(s"let $local = $vs[$vs.length - 3];")
    addLine(s"if ($local) { $local = $vs[$vs.length - 2]; }")
    addLine(s"else { $local = $vs[$vs.length - 1] };")
    addLine(s"$vs[$vs.length - 3] = $local;")
    addLine(s"$pop(); $pop();")

  def peek(): Unit =
    val local = freshName("peek_temp")
    addLine(s"const $local = $pop();")
    addLine(s"$push($vs[$vs.length - 1 - $local]);")

  def swap(): Unit =
    val local = freshName("tmp")
    addLine(s"const $local = $vs[$vs.length - 1];")
    addLine(s"$vs[$vs.length - 1] = $vs[$vs.length - 2];")
    addLine(s"$vs[$vs.length - 2] = $local;")

  def binary(op: String): Unit =
    val operand1 = freshName("operand1")
    val operand2 = freshName("operand2")
    addLine(s"const $operand1 = $pop();")
    addLine(s"const $operand2 = $pop();")
    addLine(s"$push($operand2 $op $operand1);")

  def div(): Unit =
    val operand1 = freshName("operand1")
    val operand2 = freshName("operand2")
    addLine(s"const $operand1 = $pop();")
    addLine(s"const $operand2 = $pop();")
    addLine(s"$push(($operand2 / $operand1)>>0);")

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
      case predef.bnot   =>   addLine(s"$push(!$pop());")
      case predef.run    =>   addLine(s"$pop()();")
      case predef.eql    =>   addLine(s"$push($pop() === $pop());")
      case predef.dup    =>   addLine(s"$push($vs[$vs.length - 1]);")
      case predef.swap   =>   swap()
      case predef.peek   =>   peek()
      case predef.pop    =>   addLine(s"$pop();")
      case predef.choose =>   choose()
      case predef.p      =>   addLine(s"console.log($pop());")
      case _             =>   throw new Exception("Unknown primitive: " + sym.name)
  end primitive


  /** Prepare to start the compilation */
  def start(): Unit =
    addLine("(function() {")

    indentCount += 1
    addLine(s"var $vs = [];")
    addLine(s"function $pop() { return $vs.pop(); }\n")
    addLine(s"function $push(v) { $vs.push(v); }\n")

  /** Finish compilation */
  def finish(): Unit =
    indentCount -= 1
    addLine("})()")
    pw.close()

end JSPlatform

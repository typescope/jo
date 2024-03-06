import java.io.PrintWriter
import scala.collection.mutable

import Sast.predef

/**
  * JavaScript platform
  */
class JSPlatform extends Platform:
  private val sb: StringBuilder = new StringBuilder

  private  val uniqueName = new UniqueName
  export uniqueName.freshName

  private val vs: String = freshName("_valueStack")
  private val pop: String = freshName("pop")
  private val push: String = freshName("push")

  private val symbol2UniqueName: mutable.Map[Sast.Symbol, String] = mutable.Map.empty

  def entry(init: => Unit): Unit =
    sb.append("\n\n")
    init
    sb.append("\n\n")

  /** Declare the symbol to the platform as a preparation for compilation */
  def declare(sym: Sast.Symbol): Unit =
    val uniqueName = freshName(sym.name)
    symbol2UniqueName(sym) = uniqueName

    if sym.isVal then
      sb.append(s"var $uniqueName;\n\n")

  /**
    * Call the funtion.
    */
  def call(fun: Sast.Symbol): Unit =
    val name = symbol2UniqueName(fun)
    sb.append(s"$name();\n");

  /** Initialize a value definition
    *
    * Calling the passed function will compile the initializer.
    */
  def initVal(sym: Sast.Symbol, initializer: () => Unit): Unit =
    initializer()
    val name = symbol2UniqueName(sym)
    sb.append("$name = $pop();\n")

  /** Compile a function
    *
    * Calling the passed function will compile the body of the function.
    */
  def function(sym: Sast.Symbol, body: () => Unit): Unit =
    sb.append("function ")
    sb.append(sym.name)
    sb.append("() {\n")
    body()
    sb.append("\n}\n\n")

  /** Push an integer literal to value stack */
  def push(v: Int): Unit =
    sb.append(s"$push($v);\n")


  /** Push a Boolean literal to value stack */
  def push(v: Boolean): Unit =
    sb.append(s"$push($v);\n")

  /** Push the value associated with the given symbol to value stack */
  def push(sym: Sast.Symbol): Unit =
    val name = symbol2UniqueName(sym)
    sb.append(s"$push($name);\n")

  /** Push a procedure literal to value stack
    *
    * Calling the passed function will compile the body of the procedure.
    */
  def push(proc: () => Unit): Unit =
    sb.append(vs)
    sb.append(".")
    sb.append(s"$push")
    sb.append("(")
    sb.append("() => {")
    proc()
    sb.append("});\n")

  def choose(): Unit =
    val local = freshName("choose_tmp")
    sb.append(s"let $local = $vs[$vs.length - 3];\n")
    sb.append(s"if ($local) { $local = $vs[$vs.length - 2]; } else { $local = $vs[$vs.length - 1] };\n")
    sb.append(s"$vs[$vs.length - 3] = $local;\n")
    sb.append(s"$pop(); $pop(); \n")

  def peek(): Unit =
    val local = freshName("peek_temp")
    sb.append(s"const $local = $pop();\n")
    sb.append(s"$push($vs[$vs.length - 1 - $local]);\n")

  def swap(): Unit =
    val local = freshName("tmp")
    sb.append(s"const $local = $vs[$vs.length - 1];\n")
    sb.append(s"$vs[$vs.length - 1] = $vs[$vs.length - 2];\n")
    sb.append(s"$vs[$vs.length - 2] = $local;\n")

  def binary(op: String): Unit =
    val operand1 = freshName("operand1")
    val operand2 = freshName("operand2")
    sb.append(s"const $operand1 = $pop();\n")
    sb.append(s"const $operand2 = $pop();\n")
    sb.append(s"$push($operand2 $op $operand1);\n")

  /**
    * Compile a primitive
    *
    */
  def primitive(sym: Sast.Symbol): Unit =
    sym match
      case predef.add    =>   binary("+")
      case predef.sub    =>   binary("-")
      case predef.mul    =>   binary("*")
      case predef.div    =>   binary("/")
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
      case predef.bnot   =>   sb.append(s"$push(!$pop());\n")
      case predef.run    =>   sb.append(s"$pop()();\n")
      case predef.eql    =>   sb.append(s"$push($pop() === $pop());\n")
      case predef.dup    =>   sb.append(s"$push($vs[$vs.length - 1]);\n")
      case predef.swap   =>   swap()
      case predef.peek   =>   peek()
      case predef.pop    =>   sb.append(s"$pop();\n")
      case predef.choose =>   choose()
      case predef.p      =>   sb.append(s"console.log($pop());\n")
      case _             =>   throw new Exception("Unknown primitive: " + sym.name)
  end primitive

  /**
    * Generate executable for the given assembly progrram.
    */
  def generate(outFile: String): Unit =
    new PrintWriter(outFile):
      write("(function() {\n")
      write(s"var $vs = [];\n\n")
      write(s"function pop() { return $vs.pop(); }\n\n")
      write(s"function push(v) { $vs.push(v); }\n\n")
      write(sb.toString)
      write("})()\n")
      close
end JSPlatform

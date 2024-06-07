import java.io.PrintWriter
import scala.collection.mutable

import Sast.*
import Symbols.*
import Types.*

/**
  * JavaScript platform
  */
class JSBackend(outFile: String) extends Backend:
  type Context = Unit

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

  private val symbol2UniqueName: mutable.Map[Symbol, String] = mutable.Map(
    predef.p -> "console.log"
  )

  private var indentCount = 0
  private def addLine(code: String): Unit =
    pw.append("  " * indentCount).append(code).append("\n")

  private def newLine(): Unit =
    pw.append("\n")

  private def indent(work: => Unit): Unit =
    indentCount += 1
    work
    indentCount -= 1

  def mapSymbolToJSName(sym: Symbol): String =
    val isOperator = !sym.name(0).isLetter
    val uniqueName =
      if isOperator then freshName("operator")
      else freshName(sym.name)

    symbol2UniqueName(sym) = uniqueName
    uniqueName

  def compile(prog: Prog): Unit =
    doCompile:
      for fun <- prog.funs do
        mapSymbolToJSName(fun.symbol)

      for sym <- prog.vals do
        val uniqueName = mapSymbolToJSName(sym)
        addLine(s"var $uniqueName; // ${sym.name}")

      // Compile functions
      for fun <- prog.funs do
        compile(fun)

      call(prog.main)


  /**
    * Call the funtion.
    */
  def call(fun: Symbol)(using Context): Unit =
    val name = symbol2UniqueName(fun)
    val funType = fun.info.asInstanceOf[Type.Proc]
    val paramCount = funType.paramCount

    var i: Int = paramCount - 1
    val args = new Array[String](paramCount)
    while i >= 0  do
      val argName = freshName(s"_arg_$i")
      args(i) = argName
      addLine(s"const $argName = $pop();")
      i = i - 1

    // the first stack item maps to the last parameter
    val paramStr = new StringBuilder
    i = 0
    while i < paramCount do
      paramStr.append(args(i))
      if i != paramCount - 1 then
        paramStr.append(", ")
      i = i + 1

    addLine(s"$name($paramStr);");

  /** Initialize a value definition
    *
    * Calling the passed function will compile the initializer.
    */
  def compile(assign: Word.Assign)(using Context): Unit =
    compile(assign.rhs)
    val name = symbol2UniqueName(assign.symbol)
    addLine(s"$name = $pop();")

  /** Compile a function
    *
    * Calling the passed function will compile the body of the function.
    */
  def compile(fdef: Fun)(using Context): Unit =
    val sym = fdef.symbol
    val name = symbol2UniqueName(sym)
    uniqueName.newScope:
      val paramStr = fdef.params.map(mapSymbolToJSName).mkString(", ")
      val localStr = fdef.locals.map(mapSymbolToJSName).mkString(", ")
      addLine(s"function $name($paramStr) { // ${sym.name}")
      indent:
        if fdef.locals.nonEmpty then addLine(s"var $localStr;")
        compile(fdef.body)
      addLine("}\n")

  def compile(ifword: Word.If)(using Context): Unit =
    compile(ifword.cond)
    addLine(s"if ($pop()) {")
    indent:
      compile(ifword.thenp)
    if !ifword.elsep.isEmpty then
      addLine("} else {")
      indent:
        compile(ifword.elsep)
    addLine("}")

  def compile(whileDo: Word.While)(using Context): Unit =
    compile(whileDo.cond)
    addLine(s"while ($pop()) {")
    indent:
      compile(whileDo.body)
      compile(whileDo.cond)
    addLine("}")

  /** Compile [x = 3, y = 5] */
  def compile(record: Word.RecordLit)(using Context): Unit =
    // TODO: field name is symbolic, +, -?
    val fieldValues = mutable.Map.empty[String, String]
    for (name, rhs) <- record.args do
      compile(rhs)
      val arg = freshName("arg")
      addLine(s"const $arg = $pop();")
      fieldValues(name) = arg
    end for
    val obj = fieldValues.map(_ + ":" + _).mkString("{", ", ", "}")
    addLine(s"$push($obj)")

  /** Compile p.x */
  def compile(select: Word.Select)(using Context): Unit =
    val field = select.name
    compile(select.qual)
    addLine(s"$push($pop().$field);")

  /** Push an integer literal to value stack */
  def push(v: Int)(using Context): Unit =
    addLine(s"$push($v);")


  /** Push a Boolean literal to value stack */
  def push(v: Boolean)(using Context): Unit =
    addLine(s"$push($v);")

  /** Push the value associated with the given symbol to value stack */
  def push(sym: Symbol)(using Context): Unit =
    val name = symbol2UniqueName(sym)
    addLine(s"$push($name);")

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
  def primitive(sym: Symbol)(using Context): Unit =
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
      case predef.eql    =>   addLine(s"$push($pop() === $pop());")
      case predef.p      =>   call(predef.p)
      case _             =>   throw new Exception("Unknown primitive: " + sym.name)
  end primitive


  /** Prepare to start the compilation */
  def doCompile(work: Context ?=> Unit): Unit =
    addLine("(function() {")

    indentCount += 1
    addLine(s"var $vs = [];")
    addLine(s"function $pop() { return $vs.pop(); }\n")
    addLine(s"function $push(v) { $vs.push(v); }\n")

    work(using ())

    indentCount -= 1
    addLine("})()")
    pw.close()

end JSBackend

import java.io.PrintWriter
import scala.collection.mutable

import Sast.*
import Symbols.*
import Types.*

import JSBackend.encodeSymbolic
import JSOptimized.{ ValueStack, Item }

/**
  * JavaScript platform with code optimization
  */
class JSOptimized(outFile: String) extends Backend:
  type Context = ValueStack

  private val pw =  new PrintWriter(outFile)

  private  val uniqueName = new UniqueName
  export uniqueName.freshName

  // Make keywords unavailable
  List(
    "for", "while", "function", "var", "let", "break", "continue", "if",
    "const", "class", "constructor"
  ).foreach: w =>
    freshName(w)

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

  def vs(using ctx: Context): ValueStack = ctx

  def mapSymbolToJSName(sym: Symbol): String =
    val isOperator = !sym.name(0).isLetter
    val uniqueName =
      if isOperator then freshName("operator")
      else freshName(sym.name)

    symbol2UniqueName(sym) = uniqueName
    uniqueName

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
  def bindExpressions()(using Context): Unit =
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

  def compile(prog: Prog): Unit =
    doCompile:
      for fun <- prog.funs do
        mapSymbolToJSName(fun.symbol)

      for ValDef(sym, _) <- prog.vals do
        val uniqueName = mapSymbolToJSName(sym)
        addLine(s"var $uniqueName; // ${sym.name}")

      // Compile functions
      for fun <- prog.funs do
        compile(fun)(using new ValueStack)

      val initName = symbol2UniqueName(prog.init)
      addLine(s"$initName();")

  /**
    * Call the funtion.
    */
  def call(fun: Symbol)(using Context): Unit =
    val name = symbol2UniqueName(fun)
    val funType = TypeOps.erasePolyType(fun.info).asProcType
    val paramCount = funType.paramCount
    val resCount = funType.resCount
    call(name, paramCount, resCount)

  def call(name: String, paramCount: Int, resCount: Int)(using Context): Unit =
    var i: Int = 0
    val args = vs.pop(paramCount)
    val argsStr = args.mkString(", ")

    if resCount == 0 then
      bindExpressions()
      addLine(s"$name($argsStr);")
    else if resCount == 1 then
      vs.push(Item.Expr(s"$name($argsStr)"))

  /** Initialize a value definition
    *
    * Calling the passed function will compile the initializer.
    */
  def compile(assign: Assign)(using Context): Unit =
    compile(assign.rhs)
    val name = symbol2UniqueName(assign.symbol)
    val rhs = vs.pop()
    addLine(s"$name = $rhs;")

  /** Compile a function
    *
    * Calling the passed function will compile the body of the function.
    */
  def compile(fdef: FunDef)(using Context): Unit =
    val sym = fdef.symbol
    val name = symbol2UniqueName(sym)

    val funType = TypeOps.erasePolyType(sym.info).asProcType
    val resCount = funType.resCount

    uniqueName.newScope:
      val paramStr = fdef.params.map(mapSymbolToJSName).mkString(", ")
      val localStr = fdef.locals.map(mapSymbolToJSName).mkString(", ")
      addLine(s"function $name($paramStr) { // ${sym.name}")
      indent:
        if fdef.locals.nonEmpty then addLine(s"var $localStr;")
        compile(fdef.body)
        assert(vs.size == resCount, s"expect $resCount, found = " + vs)
        if resCount != 0 then
          val retStr = vs.pop()
          addLine(s"return $retStr;")

      addLine("}\n")

  def compile(ifword: If)(using Context): Unit =
    bindExpressions()

    compile(ifword.cond)

    val condStr = vs.pop()
    if ifword.tpe.isVoid then
      addLine(s"if ($condStr) {")
      indent:
        compile(ifword.thenp)

      if !ifword.elsep.isEmpty then
        addLine("} else {")
        indent:
          compile(ifword.elsep)
      addLine("}")

    else
      assert(!ifword.elsep.isEmpty)

      val resName = freshName("resIf")
      addLine(s"let $resName;")

      addLine(s"if ($condStr) {")
      indent:
        compile(ifword.thenp)
        val retStr = vs.pop()
        addLine(s"$resName = $retStr;")
      addLine("} else {")
      indent:
        compile(ifword.elsep)
        val retStr = vs.pop()
        addLine(s"$resName = $retStr;")
      addLine("}")

      vs.push(Item.Ref(resName));

  def compile(whileDo: While)(using Context): Unit =
    bindExpressions()

    addLine(s"while (true) {")
    indent:
      compile(whileDo.cond)
      val condStr = vs.pop()
      addLine(s"if ($condStr) {")
      indent:
        compile(whileDo.body)
      addLine("} else break;")
    addLine("}")

  def compile(encoded: Encoded)(using Context): Unit =
    compile(encoded.repr)
    if encoded.isValueDrop then
      vs.pop()

  /** Compile [x = 3, y = 5] */
  def compile(record: RecordLit)(using Context): Unit =
    val fieldValues = new mutable.ArrayBuffer[(String, String)]
    for (name, rhs) <- record.args do
      compile(rhs)
      val encodedName = encodeSymbolic(name)
      fieldValues += encodedName -> vs.pop()
    end for
    val obj = fieldValues.map(_ + ":" + _).mkString("{", ", ", "}")
    vs.push(Item.Expr(obj))

  /** Compile p.x */
  def compile(select: Select)(using Context): Unit =
    val encodedField = encodeSymbolic(select.name)
    compile(select.qual)
    val qual = vs.pop()
    // TODO: binding required for mutable fields
    vs.push(Item.Ref(s"$qual.$encodedField"))

  /** Compile a reference to a function */
  def compile(ref: Ident)(using ctx: Context): Unit =
    val fun = symbol2UniqueName(ref.symbol)
    vs.push(Item.Ref(fun))

  /** Compile function call */
  def compile(app: Apply)(using Context): Unit =
    compile(app.fun)
    val funType = app.tpe.asFunctionType

    val closName = freshName("closure")
    addLine(s"let $closName = ${vs.pop()};")
    val selectEnv = closName + "." + ElimCapture.EnvFieldName
    vs.push(Item.Ref(s"$selectEnv"))

    val selectProc = closName + "." + ElimCapture.ProcFieldName
    this.call(selectProc, funType.paramCount + 1, funType.resCount)

  /** Push an integer literal to value stack */
  def push(v: Int)(using Context): Unit =
    vs.push(Item.Const(v.toString))

  /** Push a Boolean literal to value stack */
  def push(v: Boolean)(using Context): Unit =
    vs.push(Item.Const(v.toString))

  /** Push the value associated with the given symbol to value stack */
  def push(sym: Symbol)(using Context): Unit =
    val name = symbol2UniqueName(sym)
    if sym.isMutable then
      val nameCurValue = freshName(sym.name)
      addLine(s"const $nameCurValue = $name;")
      vs.push(Item.Ref(nameCurValue))
    else
      vs.push(Item.Ref(name))

  def binary(op: String)(using Context): Unit =
    val operand2 = vs.pop()
    val operand1 = vs.pop()
    vs.push(Item.Expr(s"($operand1 $op $operand2)"))

  def div()(using Context): Unit =
    val operand2 = vs.pop()
    val operand1 = vs.pop()
    vs.push(Item.Expr(s"(($operand1 / $operand2)>>0)"))

  def bnot()(using Context): Unit =
    val operand = vs.pop()
    vs.push(Item.Expr(s"(!$operand)"))

  def abort()(using Context): Unit =
    val operand = vs.pop()
    addLine(s"throw $operand;")

    // return a dummy value for compiler invariant -- abort never returns
    vs.push(Item.Const("-1"));

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
      case predef.bnot   =>   bnot()
      case predef.eql    =>   binary("===")
      case predef.p      =>   call(predef.p)
      case runtime.abort =>   abort()
      case _             =>   throw new Exception("Unknown primitive: " + sym.name)
  end primitive


  /** Prepare to start the compilation */
  def doCompile(work: => Unit): Unit =
    addLine("(function() {")
    indentCount += 1

    work

    indentCount -= 1
    addLine("})()")
    pw.close()

end JSOptimized

object JSOptimized:
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

    def size: Int = stack.size

    override def toString() = stack.toString()

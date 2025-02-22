package typing

import scala.collection.mutable

import ast.Ast
import sast.*
import sast.Sast.*
import sast.Types.*
import sast.Symbols.*

import ast.Positions.*
import reporting.Reporter

import Namer.Scope
import Inference.*

object ExprTyper:
  /** The precedence of a function word
    *
    * We fix the precedence of names in the compiler according to established
    * convention in mathematics and programming languages.
    *
    * We make `and/or/not` the same precedence to make it simpler for
    * programmers.
    *
    * We disallow setting precedence of other function names (no matter it is
    * user-defined or primitive) for usability: programmers should not learn and
    * remember precedence beyond the basic ones. When in doubt, parentheses can
    * be used.
    */
  def precedence(word: Word): Int =
    assert(word.tpe.isProcType || word.tpe.isPolyType, word)
    word match
      case Ident(sym)        => precedence(sym.name)
      case TypeApply(fun, _) => precedence(fun)
      case _                 => 100


  /** The precedence of common operators
    *
    * While users may define other operators, it is on purpose to disallow
    * defining precedence for them --- no one can understand/remember them.
    *
    * Programs that do not depend on precedence rules are easier to understand.
    */
  def precedence(fun: String): Int =
    fun match
      case "and" | "or" | "not"                     => 10
      case ">"   | "<"  | ">=" | "<=" | "==" | "!=" => 20
      case "+"   | "-"                              => 30
      case "<<"  | ">>" | "|"  | "&"  | "^"         => 40
      case "*"   | "/"  | "%"                       => 50
      case _                                        => 100

  /** The structure formed by precedence parsing and partial type checkig  */
  enum Item:
    /** An untyped word */
    case Raw(word: Ast.Word)

    /** An typed word */
    case Typed(word: Word)

    /** A call tree where the function is a typed ref tree */
    case Call(fun: Word, preArgs: List[Item], postArgs: List[Item])
             (val preTypes: List[Type], val postTypes: List[Type], val resultType: Type)

    /** A dotless infix method call */
    case InfixCall(obj: Item, method: Ast.Ident, arg: Item)

    def span: Span =
      this match
        case Raw(word) => word.span

        case Typed(word) => word.span

        case Call(fun, pre, post) =>
          (pre ++ post).foldRight(fun.span)(_.span | _)

        case InfixCall(obj, method, arg) =>
          obj.span | method.span | arg.span

/**
  * Perform type checking for an expression.
  *
  * Used to construct function call nodes.
  *
  * Instance of the class should be able to be reused to type check different
  * expression. Therefore, it should not contain any expression-specific state.
  */
class ExprTyper(namer: Namer, checker: Checker, inferencer: Inferencer):
  import ExprTyper.Item

  def transform(expr: Ast.Expr)(using  sc: Scope, rp: Reporter, so: Source, tt: TargetType): Word =
    expr.words: @unchecked match
    case head :: Nil =>
      namer.transform(head)

    case head :: rest =>
      val wordTyped =
        given TargetType = TargetType.Unknown
        namer.transform(head)
      val tp = wordTyped.tpe

      def isDotlessMethodCallPattern() = tp.isObjectType && rest.head.match
        case Ast.Ident(name) =>
          tp.getTermMember(name) match
            case Some(memType) => memType.isProcType
            case None => false

        case _ => false

      if tp.isError then
        wordTyped

      else if isDotlessMethodCallPattern() then
        // Dotless method call pattern, where the infix operator takes exactly one parameter
        val words = mutable.ListBuffer.from(expr.words)
        val item = parseDotless(words, -1)

        assert(words.isEmpty, words)
        typeItem(item)

      else if tp.hasApplyMethod then
        // function apply pattern, all remaing words are arguments
        val procType = tp.termMember("apply").asProcType
        val preTypes = procType.preParamTypes
        val postTypes = procType.postParamTypes
        val resultType = procType.resultType

        val autoApply = Select(wordTyped, "apply")(procType, wordTyped.span)
        val args = for word <- rest yield Item.Raw(word)
        val item = Item.Call(autoApply, preArgs = Nil, postArgs = args)(preTypes, postTypes, resultType)
        typeItem(item)

      else
        // mixed prefix/infix/postfix pattern, arity depends on type of the function
        val words = mutable.ListBuffer.from(expr.words)
        val values = mutable.ArrayBuffer.empty[Item]

        parseMixed(values, words, -1)

        if values.size > 1 then
          val rest = values.init
          val span = rest.head.span | rest.last.span
          Reporter.error("Found extra value, an expression should produce at most one value", span.toPos)

        assert(words.isEmpty, words)

        typeItem(values.last)
  end transform

  private def typeItem(item: Item)(using sc: Scope, rp: Reporter, so: Source, tt: TargetType): Word =
    item match
      case Item.Typed(word) => checker.adapt(word, tt)

      case Item.Raw(word) =>
        namer.transform(word)

      case call @ Item.Call(fun, preArgs, postArgs) =>
        val span = call.span
        if preArgs.size != call.preTypes.size then
          Reporter.error(
            s"Function ${fun.show} expects ${call.preTypes.size} pre arguments, found = ${preArgs.size}",
            span.toPos)
          errorTree(span)

        else if postArgs.size != call.postTypes.size then
          Reporter.error(
            s"Function ${fun.show} expects ${call.postTypes.size} post arguments, found = ${postArgs.size}",
            span.toPos)
          errorTree(span)

        else
          val preArgs2 =
            for (arg, paramType) <- preArgs.zip(call.preTypes) yield
              given TargetType = TargetType.Known(paramType)
              typeItem(arg)

          val postArgs2 =
            for (arg, paramType) <- postArgs.zip(call.postTypes) yield
              given TargetType = TargetType.Known(paramType)
              typeItem(arg)

          val word = Apply(fun, preArgs2 ++ postArgs2)(call.resultType, span)
          checker.adapt(word, tt)

      case Item.InfixCall(obj, meth, arg) =>
        val objWord = typeItem(obj)
        val objType = objWord.tpe
        val objSpan = obj.span

        if objType.isObjectType then
          // TODO: support poly method type
          objType.getTermMember(meth.name) match
            case Some(tp) =>
              if tp.isProcType then
                val procType = tp.asProcType
                val paramSize = procType.paramTypes.size
                if paramSize != 1 then
                  Reporter.error(
                    s"The method ${meth.name} takes ${paramSize} parameters. The dotless call syntax only supports methods of one parameter",
                    meth.span.toPos)
                  errorTree(meth.span)
                else
                  val argTyped =
                    given TargetType = TargetType.Known(procType.paramTypes.head)
                    typeItem(arg)

                  val fun = Select(objWord, meth.name)(procType, objSpan | meth.span)
                  val word = Apply(fun, argTyped :: Nil)(procType.resultType, item.span)
                  checker.adapt(word, tt)
              else
                Reporter.error( s"The member ${meth.name} is not a method", meth.pos)
                errorTree(meth.span)

            case None =>
              Reporter.error( s"Object of the type ${objType.show} does not have member ${meth.name}", objSpan.toPos)
              errorTree(objSpan)
        else
          Reporter.error(s"Object type expected, found = " + objWord.tpe.show, objSpan.toPos)
          errorTree(objSpan)


  /** Parse items from the words with the limit precedence for mixed prefix/infix/postfix pattern */
  private def parseMixed(values: mutable.ArrayBuffer[Item], words: mutable.ListBuffer[Ast.Word], precLimit: Int)(using rp: Reporter, sc: Scope, so: Source): Unit =
    // println("Parsing " + words + ", precedence = " + precedence)

    var continue = true

    //     3   2    1
    // x c * a + b max

    while continue && words.nonEmpty do
      val word = words.remove(0)
      word match
        case _: Ast.RefTree | _: Ast.TypeApply =>
          var wordTyped =
            given TargetType = TargetType.Unknown
            namer.transform(word)

          if wordTyped.tpe.isPolyType then
            val polyType = wordTyped.tpe.asPolyType

            val tvars = for tparam <- polyType.tparams yield TypeVar(tparam.name, this.inferencer)
            val targs = tvars.map(tvar => TypeTree(tvar)(word.span))
            val tpe = TypeOps.substTypeParams(polyType.resultType, tvars)

            wordTyped = TypeApply(wordTyped, targs)(tpe, word.span)

            val bounds = for tparam <- polyType.tparams yield tparam.info
            checker.delayedCheck {
              for tvar <- tvars do checker.checkInstantiated(tvar, word.pos)

              checker.checkBounds(bounds, targs)
            }
          end if

          val tp = wordTyped.tpe

          if tp.isProcType then
            val procType = tp.asProcType
            val precedence = ExprTyper.precedence(wordTyped)
            // infix, postfix, prefix
            if precedence > precLimit then
              val preTypes = procType.preParamTypes
              val postTypes = procType.postParamTypes
              val resultType = procType.resultType

              val preArgs = values.takeRight(preTypes.size).toList
              values.dropRightInPlace(preTypes.size)

              parseMixed(values, words, precedence)

              val postArgs = values.takeRight(postTypes.size).toList
              values.dropRightInPlace(postTypes.size)

              val call = Item.Call(wordTyped, preArgs, postArgs)(preTypes, postTypes, resultType)

              // continue if current function has higher binding power
              values += call
            else
              // TODO: wordTyped is discarded and it will be checked!
              // put back word
              words.insert(0, word)
              continue = false

          else
            values += Item.Typed(wordTyped)

        case _ =>
          values += Item.Raw(word)
    end while
  end parseMixed

  /** Parse items from the words with the limit precedence for dotless call syntax */
  private def parseDotless(words: mutable.ListBuffer[Ast.Word], precLimit: Int)(using rp: Reporter, so: Source): Item =
    // println("Parsing " + words + ", precedence = " + precLimit)

    var res = Item.Raw(words.remove(0))
    var continue = true

    // a + b - c * 2

    while continue && words.nonEmpty do
      val word = words.remove(0)
      word match
        case ident: Ast.Ident =>
          if words.isEmpty then
            continue = false
            Reporter.error("Exact one argument expected in dotless call syntax, found none", word.pos)

          else
            // TODO: support prefix operators?
            val precedence = ExprTyper.precedence(ident.name)
            // infix
            if precedence > precLimit then
              val rhs = parseDotless(words, precedence)
              res = Item.InfixCall(res, ident, rhs)
            else
              words.insert(0, word)
              continue = false

        case word =>
          continue = false
          words.clear
          Reporter.error("A method name expected here in dotless call syntax", word.pos)
      end match
    end while

    res
  end parseDotless

  def errorTree(span: Span): Word = Block(Nil)(ErrorType, span)
end ExprTyper

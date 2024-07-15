import scala.collection.mutable

import Sast.*
import Types.*
import Symbols.*
import Reporter.Span

import Namer.Scope

object NamerUtils:
  /**
    * Represent the types of values on the value stack.
    *
    * Used to check stack safety and construct function call nodes.
    */
  class PhraseTyper(namer: Namer, checker: Checker):
    private val words = mutable.ListBuffer.empty[Word]
    private val values = mutable.ArrayBuffer.empty[Word]
    private val statements = mutable.ArrayBuffer.empty[Word]

    def transform(phrase: Ast.Phrase)(using  sc: Scope, rp: Reporter): Word =
      val sc2 = sc.fresh()

      namer.transform(phrase.tdefs)(using sc2)
      for word <- phrase.words do
        words += namer.transform(word)(using sc2)

      process()

      if values.size > 1 then
        Reporter.error("At most one value expected, found = " + values.size, phrase.pos)

      val tp = if values.isEmpty then VoidType else values.last.tpe
      val words2 = (statements ++ values).toList
      Phrase(words2)(tp, phrase.span)

    def process()(using rp: Reporter): Unit =
      /** A function is automatically called if it's the first element in a
        * phrase unless no arguments is available
        */
      def isFunctionCall(tp: FunctionType): Boolean =
        this.values.isEmpty
        && (
          words.nonEmpty && words(0).tpe.isValueType
          || tp.paramTypes.isEmpty
        )

      while this.words.nonEmpty do
        val word = this.words.remove(0)
        val tp = word.tpe

        // TODO: type inference
        if tp.isPolyType then
          Reporter.error(s"Function ${Printing.show(word)} expects type arguments", word.pos)
          errorTree(word.span)

        else if tp.isProcType then
          call(word, tp.asProcType)

        else if tp.isFunctionType && isFunctionCall(tp.asFunctionType) then
          call(word, tp.asFunctionType)

        else if word.tpe.isValueType then
          push(word)

        else
          output(word)
      end while

    def call(fun: Word, procType: ProcType)(using Reporter): Unit =
      call(fun, procType.paramTypes, Nil, procType.resultType)

    def call(fun: Word, funType: FunctionType)(using Reporter): Unit =
      call(fun, Nil, funType.paramTypes, funType.resultType)

    def call(fun: Word, preTypes: List[Type], postTypes: List[Type], resType: Type)(using Reporter): Unit =
      println(Printing.show(fun))
      if values.size < preTypes.size then
        Reporter.error(
          s"Function ${Printing.show(fun)} expects ${preTypes.size} pre arguments, found = ${values.size}",
          fun.pos)
        push(errorTree(fun.span))

      else if words.size < postTypes.size then
        Reporter.error(
          s"Function ${Printing.show(fun)} expects ${postTypes.size} post arguments, found = ${words.size}",
          fun.pos)
        push(errorTree(fun.span))

      else
        val preArgs = values.takeRight(preTypes.size)
        for (arg, paramType) <- preArgs.zip(preTypes) do
          checker.checkType(arg, paramType)

        values.dropRightInPlace(preTypes.size)

        val postArgs = words.take(postTypes.size)
        for (arg, paramType) <- postArgs.zip(postTypes) do
          checker.checkType(arg, paramType)

        words.dropInPlace(postTypes.size)

        var span = preArgs.foldLeft(fun.span)(_ | _.span)
        span = postArgs.foldLeft(span)(_ | _.span)
        val call = Apply(fun, (preArgs ++ postArgs).toList)(resType, span)

        if resType.isValueType then
          push(call)
        else
          output(call)

    def push(value: Word): Unit =
      val tp = value.tpe
      assert(tp.isValueType, tp)
      values += value

    def output(word: Word): Unit =
      for word <- values do
        statements += Encoded(word)(VoidType)

      values.clear
      statements += word

    def errorTree(span: Span): Word = Phrase(Nil)(ErrorType, span)
  end PhraseTyper

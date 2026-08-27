package jvm

import ast.Positions.{ NoSpan, Source, Span }

import sast.Symbols.Symbol
import jvm.ClassFile.Label
import jvm.ClassFile.CodeWriter
import jvm.JVMTypes.JType
import jvm.JVMTypes.JType.J
import jvm.JVMTypes.isCategory2

import scala.collection.mutable

/** Local-slot allocation for one JVM method. */
final class MethodSlots:
  private val slots = mutable.Map.empty[Symbol, Int]
  private var next = 0

  def reserveUpTo(n: Int): Unit = if n > next then next = n

  def bind(sym: Symbol, tpe: JType): Int =
    val result = next
    slots(sym) = result
    next += (if isCategory2(tpe) then 2 else 1)
    result

  def apply(sym: Symbol): Int = slots(sym)
  def contains(sym: Symbol): Boolean = slots.contains(sym)

/** Attaches Jo source lines to the instructions of one method body.
  *
  * Two things make this more than a direct call to `CodeWriter.lineNumber`:
  *
  *  - An instruction that can throw is usually emitted *after* its operands
  *    (`invokestatic` after the arguments, `getfield` after the receiver,
  *    `athrow` after the thrown value), by which point the line in effect is
  *    the last operand's, not the call's. `here` puts the enclosing node's
  *    own line back before such an instruction, which is the line its frame
  *    should name in a stack trace.
  *  - Nodes nest, so "the current node's line" has to be saved across a
  *    recursive descent and restored on the way out, not just overwritten.
  *
  * Re-asserting a line already in effect costs nothing: `CodeWriter` drops
  * it rather than writing a duplicate `LineNumberTable` entry.
  */
final class LineNumbers(source: Source, cw: CodeWriter):
  private var current: Span = NoSpan

  /** Make `span`'s line current and mark it here. Returns the enclosing
    * node's span, which the caller hands back to `leave` on the way out.
    */
  def enter(span: Span): Span =
    val enclosing = current

    if span.start >= 0 then
      current = span
      here()

    enclosing

  def leave(enclosing: Span): Unit = current = enclosing

  /** Put the current node's line back in effect, for an instruction emitted
    * after operands that have since moved it elsewhere.
    */
  def here(): Unit =
    if current.start >= 0 then cw.lineNumber(source.offsetToLine(current.start) + 1)

/** Mutable state scoped to one method body. */
final class MethodContext(
  val cw: CodeWriter,
  val slots: MethodSlots,
  val returnType: JType,
  val selfSym: Option[Symbol],
  val source: Source,
  val argsArraySlot: Option[Int] = None,
  val localLabels: mutable.Map[Symbol, Label] = mutable.Map.empty
):
  /** Line bookkeeping for this body. The source is the bucket's rather than
    * each symbol's own, so a `LineNumberTable` entry can never name offsets
    * in a file other than the one the class file's `SourceFile` attribute
    * points a debugger at.
    */
  val lines = new LineNumbers(source, cw)

package jvm

import sast.Symbols.Symbol
import jvm.ClassFile.Label
import jvm.ClassFile.CodeWriter
import jvm.JVMTypes.JType
import jvm.JVMTypes.JType.J

import scala.collection.mutable

/** Local-slot allocation for one JVM method. */
final class MethodSlots:
  private val slots = mutable.Map.empty[Symbol, Int]
  private var next = 0

  def reserveUpTo(n: Int): Unit = if n > next then next = n

  def bind(sym: Symbol, tpe: JType): Int =
    val result = next
    slots(sym) = result
    next += (if tpe == J then 2 else 1)
    result

  def apply(sym: Symbol): Int = slots(sym)
  def contains(sym: Symbol): Boolean = slots.contains(sym)

  /** Total slots allocated, including parameters that the method never reads. */
  def used: Int = next

/** Mutable state scoped to one method body. */
final class MethodContext(
  val cw: CodeWriter,
  val slots: MethodSlots,
  val returnType: JType,
  val selfSym: Option[Symbol],
  val argsArraySlot: Option[Int] = None,
  val localLabels: mutable.Map[Symbol, Label] = mutable.Map.empty
)

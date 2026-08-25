package jvm

import sast.Symbols.Symbol
import jvm.ClassFile.{CodeWriter, Label}
import jvm.JVMTypes.JType
import jvm.JVMTypes.JType.J

import scala.collection.mutable

/** Local-slot allocation for one JVM method. */
final class JVMMethodSlots:
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

/** All mutable state scoped to compilation of one method body.
  *
  * Keeping this outside the program coordinator is the first boundary of the
  * short-lived `JVMMethodCompiler`: no method-local state should migrate back
  * into `JVMCodeGen` as that compiler is extracted.
  */
final class JVMMethodContext(
  val cw: CodeWriter,
  val slots: JVMMethodSlots,
  val returnType: JType,
  val selfSym: Option[Symbol],
  val argsArraySlot: Option[Int] = None,
  val localLabels: mutable.Map[Symbol, Label] = mutable.Map.empty
)

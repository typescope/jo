package typing

import scala.collection.mutable

/** Checks is the uniform interface for eager and delayed checks
  *
  * Some checks must be delayed after forcing the symbol, e.g., bounds check or
  * adapter validation to avoid cycles in forcing symbols.
  */
abstract class Checks:
  def add(check: () => Unit): Unit

object Checks:
  def delayed[T](op: Checks ?=> T): T =
    val checks = new mutable.ArrayBuffer[() => Unit]
    var frozen = false
    val ck = new Checks {

      def add(check: () => Unit): Unit =
        if frozen then throw new Exception("cannot add new task during checking")
        checks.addOne(check)
    }

    val res = op(using ck)

    frozen = true
    for check <- checks do check()
    res

  def eager[T](op: Checks ?=> T): T =
    val ck = new Checks:
      def add(check: () => Unit): Unit = check()

    op(using ck)


  def add(check: => Unit)(using checks: Checks) = checks.add(() => check)

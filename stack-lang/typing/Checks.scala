package typing

import scala.collection.mutable

class Checks:
  private val checks = new mutable.ArrayBuffer[() => Unit]
  var checking = false

  def add(check: => Unit): Unit =
    if checking then throw new Exception("cannot add new task during checking")
    checks.addOne(() => check)

  def perform(): Unit =
    checking = true
    for check <- checks do check()
    checks.clear()
    checking = false

object Checks:
  def add(check: => Unit)(using checks: Checks) = checks.add(check)

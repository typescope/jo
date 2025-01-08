package common

import scala.collection.mutable

class WorkList[T]:
  private val todoList = new mutable.ArrayBuffer[T]
  private val doneList = mutable.Set.empty[T]

  def add(item: T): Unit =
    if !doneList.contains(item) then todoList += item

  def run(doItem: T => Unit): Unit =
    while todoList.nonEmpty do
      val item = todoList.last
      todoList.dropRightInPlace(1)
      assert(!doneList.contains(item), "Alreay done, item = " + item)
      doneList += item
      doItem(item)

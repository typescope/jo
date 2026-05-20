package common

class UniqueId:
  private var id: Int = 0

  def next(): Int =
    val res = id
    id = id + 1
    res

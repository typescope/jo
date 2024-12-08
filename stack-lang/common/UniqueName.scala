package common

/**
  * A simple unique name generator.
  */
class UniqueName:
  /** Name resource book keeeping */
  private var usedNames : Map[String, Int] = Map.empty

  def freshName(prefix: String): String =
    usedNames.get(prefix) match
      case Some(count) =>
        val updatedCount = count + 1
        usedNames = usedNames.updated(prefix, updatedCount)
        prefix + updatedCount

      case None =>
        usedNames = usedNames.updated(prefix, 0)
        prefix

  /**
    * Create a nested scope for allocating names
    *
    * Names allocated in a nested scope are invisible to outer scopes, thus the
    * name resouces can be reclaimed at the end of the scope.
    */
  def newScope[T](fn: => T): T =
    val current = usedNames
    val res = fn
    usedNames = current
    res

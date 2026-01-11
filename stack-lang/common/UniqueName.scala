package common

/**
  * A simple unique name generator.
  *
  * @param separator The separator between base name and number
  */
class UniqueName(separator: String):
  /** Name resource book keeeping */
  private var usedNames : Map[String, Int] = Map.empty

  def freshName(prefix: String): String =
    usedNames.get(prefix) match
      case Some(count) =>
        val updatedCount = count + 1
        val name = prefix + separator + updatedCount
        usedNames = usedNames.updated(prefix, updatedCount)
        // x
        // x1 might be used as well
        freshName(name)

      case None =>
        usedNames = usedNames.updated(prefix, 0)
        prefix

  /**
    * Create a nested scope for allocating names
    *
    * Names allocated in a nested scope are invisible to outer scopes, thus the
    * name resouces can be reclaimed at the end of the scope.
    */
  def newScope(separator: String): UniqueName =
    val uniq = new UniqueName(separator)
    uniq.usedNames = this.usedNames
    uniq

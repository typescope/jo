import scala.collection.mutable

/**
  * A simple unique name generator.
  */
class UniqueName:
  /** Name resource book keeeping */
  private val usedNames : mutable.Map[String, Int] = mutable.Map.empty

  def freshName(prefix: String): String =
    usedNames.get(prefix) match
      case Some(count) =>
        val updatedCount = count + 1
        usedNames(prefix) = updatedCount
        prefix + updatedCount

      case None =>
        usedNames(prefix) = 0
        prefix

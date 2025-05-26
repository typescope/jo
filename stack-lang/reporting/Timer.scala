package reporting

import common.KeyProps

/** Facility for time measurement with hierarchical breakdown
  *
  * It does not support concurrency.
  */
object Timer:
  private val PerfReportKey = new KeyProps.UpdatableKey[Seq[Result]]("perf")

  private case class Result(key: String, nanos: Long, items: Seq[Result])

  inline def measure[T](key: String, inline enable: Boolean)(inline op: T)(using rp: Reporter): T =
    inline if enable then {
      val itemsBefore = rp.getKeyOrElse(PerfReportKey)(Vector.empty)

      // collect nested items
      rp.updateKey(PerfReportKey, Vector.empty)

      val before = System.nanoTime()
      val res = op
      val after = System.nanoTime()

      val nestedItems = rp.getKey(PerfReportKey)
      val item = Result(key, after - before, nestedItems)

      rp.updateKey(PerfReportKey, itemsBefore :+ item)

      res
    }
    else op

  def apply[T](key: String)(op: => T)(using rp: Reporter): T =
    measure(key, enable = true)(op)

  def report()(using rp: Reporter): Unit =

    def printItem(res: Result, indent: Int): Unit =
      val prefix = "  " * indent
      System.out.printf("%-60s %d ms\n", prefix + res.key, res.nanos / 1000000)

      for item <- res.items do printItem(item, indent + 1)


    println("=========================== Time Report =============================")
    val items = rp.getKeyOrElse(PerfReportKey)(Vector.empty)
    for item <- items do printItem(item, indent = 0)

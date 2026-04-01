package pickle

import sast.*
import sast.Trees.*
import sast.Symbols.Symbol

import scala.collection.mutable

/** A library FileUnit that loads its bytes on demand.
 *
 * Three-stage lifecycle:
 *   1. Index phase   — symbol stubs registered; no file bytes allocated
 *   2. On-demand     — bytes loaded when any sym.info in this file is first accessed
 *   3. Force         — full FileUnit materialised for the link/translate pipeline
 *
 * wasAccessed becomes true as soon as ensureLoaded() fires, which happens inside
 * the addLazy thunks registered during the index phase.
 */
class LazyFileUnit(
  val owner: Symbol,
  private val accessed: java.util.concurrent.atomic.AtomicBoolean,
  private val delayed: () => FileUnit
):

  def wasAccessed: Boolean = accessed.get()

  private lazy val fileUnit: FileUnit = delayed()

  def force()(using Definitions): FileUnit =
    owner.info
    fileUnit


/** A collection of LazyFileUnits with built-in reachability forcing.
 *
 * Use force() for checking libraries — fixed-point: only units accessed during
 * type checking are materialised, forcing one may make others reachable.
 *
 * Use forceAll() for link packages — all units are materialised unconditionally.
 */
class LazyFileUnits:
  private val buf = mutable.ArrayBuffer[LazyFileUnit]()

  private[pickle] def addOne(unit: LazyFileUnit): Unit =
    buf += unit

  /** Force only the units that were accessed (transitively) during type checking.
   *
   * A BitSet tracks already-forced units. Each iteration scans buf for newly
   * accessed units; stops when no new unit is discovered.
   */
  def force()(using Definitions): List[FileUnit] =
    val n      = buf.size
    val forced = new java.util.BitSet(n)
    val result = mutable.ArrayBuffer[FileUnit]()
    var changed = true

    while changed do
      changed = false
      var i = 0

      while i < n do
        if !forced.get(i) && buf(i).wasAccessed then
          forced.set(i)
          result += buf(i).force()
          changed = true
        i += 1

    result.toList

  /** Force all units regardless of access — used for link packages. */
  def forceAll()(using Definitions): List[FileUnit] =
    buf.map(_.force()).toList

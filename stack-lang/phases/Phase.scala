package phases

import sast.SastOps
import sast.Sast.*

/** Shared code for phases */
abstract class Phase extends SastOps.TreeMap:
  def createContext(fdef: FunDef): Context

  def transform(nss: List[Namespace]): List[Namespace] =
    for ns <- nss yield transformNamespace(ns)

  def transformNamespace(ns: Namespace): Namespace =
    val defs = ns.defs.map:
      case fdef: FunDef =>
        given Context = createContext(fdef)
        val body2 = this(fdef.body)
        fdef.copy(body = body2)(fdef.span)

      case defn => defn

    Namespace(ns.symbol, ns.imports, defs)(ns.span)

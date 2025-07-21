package phases

import sast.*
import sast.Sast.*

import pickle.Encoder
import common.IO

class Pickler(using defn: Definitions) extends phases.Phase[Unit]:
  val contextObject = phases.Phase.DummyContext

  override def transformNamespace(ns: Namespace)(using ctx: Context): Namespace =
    val path = ns.symbol.fullName + ".sast"
    val buf = Encoder.encode(ns)
    IO.writeFile(path, buf.getBytes, 0, buf.length)
    ns

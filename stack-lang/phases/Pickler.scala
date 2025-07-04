package phases

import sast.*
import sast.Sast.*

import pickle.Encoder
import common.IO

class Pickler(using defn: Definitions) extends phases.Phase[Unit]:
  val contextObject = phases.Phase.DummyContext

  override def transformNamespace(ns: Namespace)(using ctx: Context): Namespace =
    val path = ns.symbol.fullName + ".sast"
    IO.withPrintWriter(path): pw =>
      val text = Encoder.encode(ns)
      text.write(pw)

    ns

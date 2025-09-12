package phases

import sast.*
import sast.Trees.*

import pickle.{ Encoder, Decoder, ReadBuffer }

import reporting.Reporter
import reporting.Config

import common.IO

import java.nio.charset.StandardCharsets.UTF_8

class Pickler(using defn: Definitions, rp: Reporter, cf: Config) extends phases.Phase[Unit]:
  val contextObject = phases.Phase.DummyContext

  override def transformNamespace(ns: Namespace)(using ctx: Context): Namespace =
    val fullName = ns.symbol.fullName
    val path = fullName + ".sast"
    val buf = Encoder.encode(ns)
    IO.writeFile(path, buf.getBytes, 0, buf.length)

    if cf.testPickling then
      val bytes = IO.fileAsBytes(path)
      given ReadBuffer = new ReadBuffer(bytes)
      val ns2 = Decoder.decode(ns.symbol.owner).force()

      val contentBefore = RawPrinter.print(ns).toString
      val contentAfter = RawPrinter.print(ns2).toString

      if contentBefore != contentAfter then
        val before = fullName + "-before.txt"
        val after = fullName + "-after.txt"
        println(s"Test pickling failed, please check files $before and $after.")

        IO.writeFile(before, contentBefore.getBytes(UTF_8))
        IO.writeFile(after, contentAfter.getBytes(UTF_8))
      end if
    end if

    ns

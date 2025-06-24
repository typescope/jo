package lib

import sast.*
import sast.Sast.*

object Serializer:
  def save(ns: Namespace)(using Definitions): Unit = ???

  def load(bytes: Array[Byte]): Namespace = ???

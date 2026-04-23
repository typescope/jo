package typing

import sast.*
import sast.Trees.FileUnit

import reporting.Config
import reporting.Reporter
import reporting.Config.InternalSetting

trait PostCheck:
  def check(units: List[FileUnit])(using Definitions, Reporter, Config): Unit

object PostCheck:
  val postChecks: InternalSetting[List[PostCheck]] = InternalSetting(Nil, "post-typing validation checks")

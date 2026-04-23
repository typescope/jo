package phases

import sast.*
import sast.Trees.FileUnit

import reporting.Config
import reporting.Reporter

trait PostCheck:
  def check(units: List[FileUnit])(using Definitions, Reporter, Config): Unit

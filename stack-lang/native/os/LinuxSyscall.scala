package native.os

import sast.Definitions
import sast.Symbols.*

import native.Assembly.Label
import native.Assembler.PatchableBuffer
import native.Linker

import scala.collection.mutable

abstract class LinuxSyscall()(using defn: Definitions) extends Linker:

  val Syscall = defn.resolveContainer("native.Syscall")

  val syscallSymbols = Set(
    Syscall.termMember("__sys_brk"),
    Syscall.termMember("__sys_exit"),
    Syscall.termMember("__sys_open"),
    Syscall.termMember("__sys_close"),
    Syscall.termMember("__sys_read"),
    Syscall.termMember("__sys_write"),
    Syscall.termMember("__sys_seek"),
    Syscall.termMember("__sys_newfstat"),
  )

  val syscallMap = mutable.Map.empty[Symbol, Label]

  def linkData()(using pb: PatchableBuffer): Unit = ()

  def linkCode()(using pb: PatchableBuffer): Unit =
    for (sym, label) <- syscallMap do linkSyscall(sym, label)

  def linkSyscall(symbol: Symbol, label: Label)(using PatchableBuffer): Unit

  def locate(sym: Symbol): Option[Label] =
    if syscallSymbols.contains(sym) then
      syscallMap.get(sym) match
        case None =>
          val label = Label(sym.name)
          syscallMap(sym) = label
          Some(label)

        case res => res

    else None

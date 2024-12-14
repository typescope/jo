package native.os

import sast.NameTable
import sast.Symbols.*

import native.Assembly.Label
import native.Assembler.PatchableBuffer
import native.Linker

abstract class LinuxSyscall(runtimeRootNameTable: NameTable) extends Linker:
  def resolveNamespace(path: String) =
    NameTable.resolvePath(runtimeRootNameTable, path, isType = false)

  val sys_exit_label = Label("__sys_exit")
  val sys_write_label = Label("__sys_write")
  val sys_brk_label = Label("__sys_brk")

  val Syscall = resolveNamespace("stk.runtime.native.Syscall")
  val Syscall_sys_brk = Syscall.termMember("sys_brk")
  val Syscall_sys_exit = Syscall.termMember("sys_brk")
  val Syscall_sys_write = Syscall.termMember("sys_write")

  def linkData()(using pb: PatchableBuffer): Unit = ()

  def linkCode()(using pb: PatchableBuffer): Unit =
    sysWrite()
    sysExit()
    sysBrk()

  def locate(sym: Symbol): Option[Label] =
    if sym.owner == Syscall then
      if sym == Syscall_sys_brk then Some(sys_brk_label)
      else if sym == Syscall_sys_exit then Some(sys_exit_label)
      else if sym == Syscall_sys_write then Some(sys_write_label)
      else throw new Exception("Unexpected symbol " + sym)
    else None

  def locate(qualid: String): Option[Label] = None

  def inits(): List[Symbol] = Nil

  /**
    * Implement sys_write in machine code.
    */
  def sysWrite()(using pb: PatchableBuffer): Unit

  /**
    * Implement abort in machine code.
    */
  def sysExit()(using pb: PatchableBuffer): Unit

  /**
    * Implement print in machine code.
    */
  def sysBrk()(using pb: PatchableBuffer): Unit

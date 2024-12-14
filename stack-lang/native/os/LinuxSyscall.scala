package native.os

abstract class LinuxSyscall(runtimeRootNameTable: NameTable) extends Linker:
  def resolveNamespace(path: String) =
    NameTable.resolvePath(runtimeRootNameTable, path, isType = false)

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
      if sym == Syscall_sys_brk then LinuxSyscall.sysBrkLabel
      else if sym = Syscall_sys_exit then LinuxSyscall.sysExitLabel
      else if sym = Syscall_sys_write then LinuxSyscall.sysWriteLabel
      else throw new Exception("Unexpected symbol " + sym)
    else None

  def locate(qualid: String): Option[Label] = None

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


object LinuxSyscall:
  val sysExitLabel = Label("__sys_exit")
  val sysWriteLabel = Label("__sys_write")
  val sysBrkLabel = Label("__sys_brk")

import Symbols.*
import Types.*

object NativeRuntime:
  //----------------------------------------------------------------------------
  // run-time symbols are only available to the compiler

  // the memory allocator
  private val allocateType = ProcType(NamedInfo("size", IntType) :: Nil, IntType, preParamCount = 0)
  val allocate = new Symbol("alloc", allocateType, Flags.Prim, owner = predefSym, sourcePos = null)

  // exit
  private val exitType = ProcType(Nil, VoidType, preParamCount = 0)
  val exit = new Symbol("exit", exitType, Flags.Prim, owner = predefSym, sourcePos = null)

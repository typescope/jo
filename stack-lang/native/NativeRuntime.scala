package native

import sast.*
import sast.Symbols.*
import sast.Types.*

/** Functions to support native platform at runtime */
object NativeRuntime:
  //----------------------------------------------------------------------------
  // run-time symbols are only available to the compiler

  // the memory allocator
  private val allocateType = ProcType(NamedInfo("size", IntType) :: Nil, IntType, preParamCount = 0)
  val allocate = new Symbol("alloc", allocateType, Flags.Fun, owner = Predef.predefSym, sourcePos = null)

  // finish - tell runtime program has finished
  private val finishType = ProcType(Nil, VoidType, preParamCount = 0)
  val finish = new Symbol("finish", finishType, Flags.Fun, owner = Predef.predefSym, sourcePos = null)

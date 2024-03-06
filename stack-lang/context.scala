import scala.collection.mutable

import Assembly.*

/**
  * Compilation context for code generation.
  */
class Context private(
    scope: Scope,
    platform: Platform,
    regAlloc: RegisterAllocator,
    codeBuffer: CodeBuffer):

  //---------------------------------------------------------------------

  export platform.freshName

  export regAlloc.{ useReg, useTwoReg }

  export scope.{ bind, resolve }

  export codeBuffer.{ add, addDataLabel, addCodeLabel, entry, getResult }

  //---------------------------------------------------------------------

  /** Create a new context with a new nested scope */
  def freshScope(): Context =
    new Context(Scope.NestedScope(scope), platform, regAlloc, codeBuffer)

  /**
    * Generate code to initialize the language runtime.
    */
  def initialize(startLabel: Label): Unit =
    platform.initialize(startLabel)(using this)

  /**
    * Generate code to run after main program finishes.
    */
  def finish(): Unit =
    platform.finish()(using this)

  /**
    * Return from a procedure or function.
    */
  def ret(): Unit = platform.ret()(using this)

  /**
    * Call the procedure or funtion at the given address.
    */
  def call(addr: Addr): Unit = platform.call(addr)(using this)

  /**
    * Pop the value on the top of the value stack to the given register.
    */
  def pop(destReg: Int): Unit = platform.pop(destReg)(using this)

  /**
    * Push value on the value stack.
    *
    * It could be address of a procedure, represented by a label.
    */
  def push(v: Operand | Label): Unit = platform.push(v)(using this)

  /** Compile a primitive */
  def primitive(sym: Sast.Symbol)(using Context): Unit =
    platform.primitive(sym)(using this)
end Context

object Context:
  def createContext(platform: Platform): Context =
    val regAlloc = new RegisterAllocator(platform.freeRegisters)
    val entry = Label(platform.freshName("_entry"))
    val codeBuffer = new CodeBuffer(entry)
    val rootScope = Scope.createRootScope()
    val rootContext = Context(
        rootScope,
        platform,
        regAlloc,
        codeBuffer)

    rootContext.freshScope()

  /**
    * A simple unique name generator.
    */
  class UniqueName:
    /** Name resource book keeeping */
    private val usedNames : mutable.Map[String, Int] = mutable.Map.empty

    def freshName(prefix: String): String =
      usedNames.get(prefix) match
        case Some(count) =>
          val updatedCount = count + 1
          usedNames(prefix) = updatedCount
          prefix + updatedCount

        case None =>
          usedNames(prefix) = 0
          prefix

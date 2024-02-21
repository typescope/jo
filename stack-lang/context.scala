import scala.collection.mutable

import Assembly.*
import Context.RegisterAllocator
import Context.CodeBuffer

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
  def initCode(startLabel: Label): Unit =
    platform.initialize(startLabel)(using this)

  /**
    * Generate code to run after main program finishes.
    */
  def exitCode(): Unit =
    platform.finish()(using this)

  /**
    * Print the value on top of the value stack.
    */
  def print(): Unit =
    platform.print()(using this)

  /**
    * Return from a procedure or function.
    */
  def ret(): Unit = platform.ret()(using this)

  /**
    * Call the procedure or funtion at the given address.
    */
  def call(addr: CodeAddr): Unit = platform.call(addr)(using this)

  /**
    * Pop the value on the top of the value stack to the given register.
    */
  def pop(destReg: Int): Unit = platform.pop(destReg)(using this)

  /**
    * Pop the value on the top of the value stack without using it.
    */
  def pop(): Unit = platform.pop()(using this)

  /**
    * Push the value at the specified index on the top of stack.
    *
    * [index ..., v, ... ]   =>  [v, ..., v, ...]
    */
  def peek(): Unit = platform.peek()(using this)

  /**
    * Push value on the value stack.
    *
    * It could be address of a procedure, represented by a label.
    */
  def push(v: Operand | Label): Unit = platform.push(v)(using this)

  /** Swap items on top of stack.
    *
    * It's implemented here to generate optimized code.
    */
  def swap(): Unit = platform.swap()(using this)

  /**
    * Duplicate the value on the top of stack.
    *
    * It's implemented here to generate optimized code.
    */
  def duplicate(): Unit = platform.duplicate()(using this)

  /** Choose between two values depending on the third.
    *
    *     [v1 v2 true  ...]   => [v2 ...]
    *     [v1 v2 false ...]   => [v1 ...]
    *
    * It's implemented here to generate optimized code.
    */
  def choose(): Unit = platform.choose()(using this)
end Context

object Context:
  def createContext(platform: Platform): Context =
    val regAlloc = new RegisterAllocator(platform.freeRegisters)
    val entry = Label(platform.freshName("_entry"))
    val codeBuffer = new CodeBuffer(entry)

    val rootContext = Context(
        Scope.createRootScope(),
        platform,
        regAlloc,
        codeBuffer)

    rootContext.freshScope()


  /**
    * Hold generated assembly data and code.
    */
  class CodeBuffer(val entry: Label):
    private val dataSection: mutable.ArrayBuffer[Data  | Label] = new mutable.ArrayBuffer
    private val codeSection: mutable.ArrayBuffer[Instr | Label] = new mutable.ArrayBuffer

    def add(data  : Data       ): Unit = dataSection.addOne(data)
    def add(instrs: List[Instr]): Unit = codeSection.addAll(instrs)
    def add(instr : Instr      ): Unit = codeSection.addOne(instr)

    def addDataLabel(label: Label): Unit = dataSection.addOne(label)
    def addCodeLabel(label: Label): Unit = codeSection.addOne(label)

    def getResult(): Prog = Prog(dataSection.toList, codeSection.toList, entry)

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

  /**
    * A simple register allocator.
    *
    * @param freeRegs All registers for temporary usage in a processor.
    *
    * The registers reserved for call stack pointer and value stack pointer are excluded.
    */
  private class RegisterAllocator(freeRegs: List[Int]):
    var freeIndex = 0

    /**
      * Allocate a temp register for usage.
      *
      * The allocated register will be released after the function return.
      *
      * TODO: spilling if no temp registers are available?
      */
    def useReg(fn: Int => Unit): Unit =
      if freeIndex >= freeRegs.size then
        throw new Exception("No register available")
      else
        val freeReg = freeIndex
        freeIndex += 1
        fn(freeRegs(freeReg))
        freeIndex -= 1


    /**
      * Allocate two temporary registers for usage.
      *
      * @see useReg
      */
    def useTwoReg(fn: (Int, Int) => Unit): Unit =
      useReg: r1 =>
        useReg: r2 =>
          fn(r1, r2)

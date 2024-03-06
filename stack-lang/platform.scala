import Assembly.*

import IO.ByteBuffer

/**
  * The platform abstraction encapsulates the logic about how
  * to implement the value stack and call stack.
  *
  * The value and call stack implementation is dependent on the
  * underlying operating system (if present) and processor.
  *
  * The platform installs an initializer which will initialize
  * the runtime environment, and then start executing the user
  * entry point.
  *
  * - It also provides platform services such as exit, print, etc.
  * - It encapsulates the details about generating the executable file.
  */
abstract class Platform:
  /**
    * Generate a fresh name for the compiled program.
    *
    * Platform generates the very first labels, that's the reason for it to hold the functionality.
    */
  def freshName(prefix: String): String

  /**
    * All registers for temporary usage.
    */
  def freeRegisters: List[Int]

  /**
    * Generate code to initialize the language runtime.
    */
  def initialize(mainLabel: Label)(using Context): Unit

  /**
    * Generate code to be run after main program finishes.
    */
  def finish()(using Context): Unit

  /**
    * Return from a procedure or function.
    */
  def ret()(using Context): Unit

  /**
    * Call the procedure or funtion at the given address.
    */
  def call(addr: Addr)(using Context): Unit

  /**
    * Pop the value on the top of the value stack to the given register.
    */
  def pop(destReg: Int)(using Context): Unit

  /**
    * Push value on the value stack.
    *
    * It could be address of a procedure, represented by a label.
    */
  def push(v: Value)(using Context): Unit

  /**
    * Compile a primitive
    *
    */
  def primitive(sym: Sast.Symbol)(using Context): Unit

  /**
    * Generate executable for the given assembly progrram.
    */
  def generate(prog: Prog)(using bb: ByteBuffer): Unit
end Platform

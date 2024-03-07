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
    * Generate entry code
    *
    * Calling the passed function will compile the user entry code.
    *
    * The method enables platform to add code around the program entry.  For example, new code can
    * be inserted before the entry to initialize the language runtime and after the entry to exit
    * gracely.
    */
  def entry(init: => Unit): Unit

  /** Declare the symbol to the platform as a preparation for compilation */
  def declare(sym: Sast.Symbol): Unit

  /** Call the funtion */
  def call(fun: Sast.Symbol): Unit

  /** Initialize a value definition
    *
    * Calling the passed function will compile the initializer.
    */
  def initVal(sym: Sast.Symbol, initializer: () => Unit): Unit

  /** Compile a function
    *
    * Calling the passed function will compile the body of the function.
    */
  def function(sym: Sast.Symbol, body: () => Unit): Unit

  /** Push an integer literal to value stack */
  def push(v: Int): Unit

  /** Push a Boolean literal to value stack */
  def push(v: Boolean): Unit

  /** Push the value associated with the given symbol to value stack */
  def push(sym: Sast.Symbol): Unit

  /** Push a procedure literal to value stack
    *
    * Calling the passed function will compile the body of the procedure.
    */
  def push(proc: () => Unit): Unit

  /** Compile a primitive */
  def primitive(sym: Sast.Symbol): Unit

  /** Prepare to start the compilation */
  def start(): Unit

  /** Finish compilation */
  def finish(): Unit
end Platform

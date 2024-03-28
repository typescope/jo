import Assembly.*

import IO.ByteBuffer
import Sast.*

/**
  * The platform abstraction encapsulates implementation details about the value
  * stack, call stack and primitive.
  *
  * The value stack, call stack and primitives are dependent on the underlying
  * operating system (if present) and processor, or virtual machine.
  *
  */
abstract class Platform:
  /**
    * Generate entry code
    *
    * Calling the passed function will compile the user entry code.
    *
    * The method enables platform to add code around the program entry.  For
    * example, new code can be inserted before the entry to initialize the
    * language runtime and after the entry to exit gracely.
    */
  def entry(init: => Unit): Unit

  /**
    * Declare the top-level symbol to the platform as a preparation for
    * compilation
    */
  def declare(sym: Symbol): Unit

  /** Call the funtion */
  def call(fun: Symbol): Unit

  /** Initialize a value definition
    *
    * Calling the passed function will compile the initializer.
    */
  def initVal(sym: Symbol, initializer: () => Unit): Unit

  /** Compile a function
    *
    * Calling the passed function will compile the body of the function.
    */
  def function(sym: Symbol, params: List[Symbol], body: () => Unit): Unit

  /** Compile a conditional statement, i.e if/then/else */
  def conditional(ifword: Word.If, compile: List[Word] => Unit): Unit

  /** Push an integer literal to value stack */
  def push(v: Int): Unit

  /** Push a Boolean literal to value stack */
  def push(v: Boolean): Unit

  /** Push the value associated with the given symbol to value stack */
  def push(sym: Symbol): Unit

  /** Compile a primitive */
  def primitive(sym: Symbol): Unit

  /** Prepare to start the compilation */
  def start(): Unit

  /** Finish compilation */
  def finish(): Unit
end Platform

package common

import scala.collection.mutable

/** Infrastructre for supporting special variables as in Common Lisp or Scoped
  * values in Java.
  */
object Dynamic:
  /** A map from keys to values
    *
    * TODO: Use thread-local store for thread safety.
    */
  private val container = new KeyProperties.Container {}

  class Lazy[+T](delayed: => T):
    private lazy val value: T = delayed
    def force(): T = value

  /** Install the mapping until end of the program
    *
    * The key must not have been installed.
    */
  def install[T](key: Key[T], value: T): Unit = container.addKey(key, value)

  def get[T](key: Key[T]): T = map(key).asInstanceOf[T]

  def reset(): Unit = container.clear()

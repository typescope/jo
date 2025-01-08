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
  private val map: mutable.Map[Key[?], Any] = mutable.Map.empty

  class Key[+T](val name: String)

  /** Install the mapping until end of the program
    *
    * The key must not have been installed.
    */
  def install[T](key: Key[T], value: T): Unit =
    assert(!map.contains(key))
    map(key) = value

  def withBinding[T, S](key: Key[T], value: T)(fn: => S): S =
    val old = map.get(key)
    map(key) = value
    val res = fn
    old match
      case None    => map.remove(key)
      case Some(v) => map(key) = v
    end match
    res

  def get[T](key: Key[T]): T = map(key).asInstanceOf[T]

  def reset(): Unit = map.clear()

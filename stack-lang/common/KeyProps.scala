package common

import scala.collection.immutable.ListMap

/** Support static key-value properties associated with an object */
object KeyProps:
  class Key[+T](val name: String)

  trait Container:
    /** A map from keys to values */
    private var map: ListMap[Key[?], Any] = ListMap()

    def addKey[T](key: Key[T], value: T): Unit =
      assert(!map.contains(key))
      map = map.updated(key, value)

    def getKey[T](key: Key[T]): T = map(key).asInstanceOf[T]

    def testKey[T](key: Key[T]): Option[T] = map.get(key).asInstanceOf[Option[T]]

    def getKeyOrElse[T](key: Key[T], default: => T): T =
      map.get(key) match
        case Some(v) => v.asInstanceOf[T]
        case None => default

    def getKeyOrUpdate[T](key: Key[T])(compute: => T): T =
      map.get(key) match
        case Some(v) => v.asInstanceOf[T]
        case None =>
          val value = compute
          map = map.updated(key, value)
          value

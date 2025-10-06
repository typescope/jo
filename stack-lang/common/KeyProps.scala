package common

import scala.collection.immutable.ListMap

/** Support static key-value properties associated with an object */
object KeyProps:
  class Key[+T](val name: String):
    override def toString() = name

  class UpdatableKey[+T](name: String) extends Key[T](name)

  trait Container:
    /** A map from keys to values */
    private var map: ListMap[Key[?], Any] = ListMap()

    def copyProps(that: Container): this.type =
      this.map = that.map
      this

    def addKey[T](key: Key[T], value: T): Unit =
      assert(!map.contains(key), "key = " + key + ", value = " + value)
      map = map.updated(key, value)

    def updateKey[T](key: UpdatableKey[T], value: T): Unit =
      map = map.updated(key, value)

    def getKey[T](key: Key[T]): T = map(key).asInstanceOf[T]

    def getKeyOrElse[T](key: Key[T])(default: => T): T =
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

    def hasKey[T](key: Key[T]): Boolean =
      map.contains(key)

    def testKey[T](key: Key[T]): Option[T] =
      map.get(key).asInstanceOf[Option[T]]

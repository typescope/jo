package sast

/** A light-weight lazy value */
class LazyValue[T](private var delayed: () => T):
  private var _value: T | Null = null

  def fill(v: T): LazyValue[T] =
    assert(_value == null, "Non-empty value: " + _value)
    _value = v
    this

  def value: T =
    if _value == null then
      _value = delayed()
      delayed = null
      _value.nn
    else
      _value.nn

object LazyValue:
  def eager[T](value: T): LazyValue[T] =
    LazyValue(delayed = null).fill(value)

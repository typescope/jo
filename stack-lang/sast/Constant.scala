package sast

enum Constant:
  case Bool(value: scala.Boolean)
  case Byte(value: scala.Int)
  case Char(value: scala.Int)
  case Int(value: scala.Int)
  case Long(value: scala.Long)
  case Float(value: scala.Double)
  case String(value: java.lang.String)

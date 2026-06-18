package sast

enum Constant:
  case Bool(value: scala.Boolean)
  case Int(value: scala.math.BigInt)
  case Float(value: scala.Double)
  case String(value: java.lang.String)

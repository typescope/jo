package sast

enum Constant:
  case Bool(value: scala.Boolean)
  case Int(value: scala.Int)
  case String(value: java.lang.String)

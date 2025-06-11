package sast

import Symbols.*
import Types.*

abstract class InfoProvider:
  def add(sym: Symbol, owner: Symbol, tp: Type): Unit

  def addLazy(sym: Symbol, owner: Symbol, infoLazy: () => Type, errorType: () => Type): Unit

  def addLazy(sym: Symbol, owner: Symbol, infoLazy: () => Type): Unit =
    addLazy(sym, owner, infoLazy, () => ErrorType)

  def get(sym: Symbol): Option[SymInfo]

  def info(sym: Symbol): Type = apply(sym).tpe

  def dealiasedInfo(sym: Symbol): Type =
    apply(sym).tpe match
      case StaticRef(sym) if sym.isAlias => dealiasedInfo(sym)
      case tp => tp

  def apply(sym: Symbol): SymInfo =
    get(sym) match
      case Some(info) => info
      case _ => throw new Exception("Not found info for " + sym)

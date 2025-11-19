package sast

import Symbols.*
import Types.*

import scala.collection.mutable

abstract class InfoProvider:
  def add(sym: Symbol, tp: Type): Unit

  def addLazy(sym: Symbol, infoLazy: () => Type, errorType: () => Type): Unit

  def addLazy(sym: Symbol, infoLazy: () => Type): Unit =
    addLazy(sym, infoLazy, () => ErrorType)

  def get(sym: Symbol): Option[Type]

  def info(sym: Symbol): Type = apply(sym)

  def dealiasedInfo(sym: Symbol): Type =
    apply(sym) match
      case StaticRef(sym) if sym.isAlias => dealiasedInfo(sym)
      case tp => tp

  def apply(sym: Symbol): Type =
    get(sym) match
      case Some(info) => info
      case _ => throw new Exception("Not found info for " + sym)

object InfoProvider:
  class InfoTransformer(provider: InfoProvider, transform: (Symbol, Type) => Type) extends InfoProvider:
    private val cache = mutable.Map.empty[Symbol, Type]

    def add(sym: Symbol, info: Type): Unit =
      cache(sym) = info

    def addLazy(sym: Symbol, infoLazy: () => Type, errorType: () => Type): Unit =
      // add to super provider, which supports lazy info
      provider.addLazy(sym, infoLazy, errorType)

    def get(sym: Symbol): Option[Type] =
      cache.get(sym) match
        case res: Some[_] => res

        case None =>
          provider.get(sym).map: info =>
            val info2 = transform(sym, info)
            cache(sym) = info2
            info2
      end match

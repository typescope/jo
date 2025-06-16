package sast

import Symbols.*
import Types.*

import scala.collection.mutable

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

object InfoProvider:
  class InfoTransformer(provider: InfoProvider, transform: SymInfo => SymInfo) extends InfoProvider:
    private val cache = mutable.Map.empty[Symbol, SymInfo]

    def add(sym: Symbol, owner: Symbol, info: Type): Unit =
      cache(sym) = SymInfo(sym, owner, info)

    def addLazy(sym: Symbol, owner: Symbol, infoLazy: () => Type, errorType: () => Type): Unit =
      // add to super provider, which supports lazy info
      provider.addLazy(sym, owner, infoLazy, errorType)

    def get(sym: Symbol): Option[SymInfo] =
      cache.get(sym) match
        case res: Some[_] => res

        case None =>
          provider.get(sym).map: symInfo =>
            val symInfo2 = transform(symInfo)
            cache(symInfo2.symbol) = symInfo2
            symInfo2
      end match

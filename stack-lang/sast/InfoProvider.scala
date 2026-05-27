package sast

import Symbols.Symbol
import Denotations.Denotation
import Types.*

import scala.collection.mutable

abstract class InfoProvider:
  def add(sym: Symbol, info: Denotation): Unit

  def addLazy(sym: Symbol, infoLazy: () => Denotation, errorType: () => Denotation): Unit

  def addLazy(sym: Symbol, infoLazy: () => Denotation): Unit =
    addLazy(sym, infoLazy, () => ErrorType)

  def get(sym: Symbol): Option[Denotation]

  def info(sym: Symbol): Denotation = apply(sym)

  /** Returns symbol info from the provider immediately before the latest installed transform.
    *
    * For base providers (no transform layer), this is identical to [[info]].
    */
  def prevInfo(sym: Symbol): Denotation = info(sym)

  def dealiasedInfo(sym: Symbol): Denotation =
    apply(sym) match
      case StaticRef(sym) if sym.isAlias => dealiasedInfo(sym)
      case tp => tp

  def apply(sym: Symbol): Denotation =
    get(sym) match
      case Some(info) => info
      case _ => throw new Exception("Not found info for " + sym + " @ " + sym.source.file + ", owner = " + sym.owner)

object InfoProvider:
  class InfoTransformer(provider: InfoProvider, transform: (Symbol, Denotation) => Denotation) extends InfoProvider:
    private val cache = mutable.Map.empty[Symbol, Denotation]

    def add(sym: Symbol, info: Denotation): Unit =
      cache(sym) = info

    def addLazy(sym: Symbol, infoLazy: () => Denotation, errorInfo: () => Denotation): Unit =
      // add to super provider, which supports lazy info
      provider.addLazy(sym, infoLazy, errorInfo)

    def get(sym: Symbol): Option[Denotation] =
      cache.get(sym) match
        case res: Some[_] => res

        case None =>
          provider.get(sym).map: info =>
            val info2 = transform(sym, info)
            cache(sym) = info2
            info2
      end match

    override def prevInfo(sym: Symbol): Denotation =
      provider.info(sym)

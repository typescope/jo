package sast

import sast.Denotations.Denotation
import sast.Symbols.*
import sast.Types.*
import sast.Trees.FunDef

import scala.collection.mutable

class SymbolIndex(val nameTable: NameTable, val initProvider: InfoProvider):
  private var provider: InfoProvider = initProvider
  private var cacheForInfoProvider: Cache = new Cache

  def cache: Cache = cacheForInfoProvider

  def info(sym: Symbol): Denotation = provider(sym)

  /** Returns symbol info from the provider immediately before the latest installed transform.
    *
    * This is useful in lowering phases that both install a transform and still need
    * access to pre-transform symbol info for decision making.
    */
  def prevInfo(sym: Symbol): Denotation = provider.prevInfo(sym)

  def add(sym: Symbol, info: Denotation): Unit =
    provider.add(sym, info)

  def addLazy(sym: Symbol, infoLazy: () => Denotation, errorType: () => Denotation): Unit =
    provider.addLazy(sym, infoLazy, errorType)

  def addLazy(sym: Symbol, infoLazy: () => Denotation): Unit =
    provider.addLazy(sym, infoLazy, () => ErrorType)

  /** Install a transformer for symbols
    *
    * Warning: Accessing `sym.info` will loop. Use the provided data instead.
    */
  def installTransform(transform: (Symbol, Denotation) => Denotation): Unit =
    provider = new InfoProvider.InfoTransformer(provider, transform)

    // Invalidate old cache
    cacheForInfoProvider = new Cache


  //----------------------------------------------------------------------------
  // Effects provider
  //
  val effectEngine: EffectAnalysis = new EffectAnalysis

  def receives(sym: Symbol)(using Definitions): List[Symbol] =
    effectEngine.effects(sym).keys.toList

  //----------------------------------------------------------------------------
  // Code provider
  //

  private val codeProvider = new CodeProvider

  def getCode(sym: Symbol): FunDef = codeProvider.get(sym).get

  def getCodeOpt(sym: Symbol): Option[FunDef] = codeProvider.get(sym)

  def setCode(sym: Symbol, code: FunDef): Unit = codeProvider.set(sym, code)

  //----------------------------------------------------------------------------
  // Doc comments
  //

  private val docComments = mutable.Map[Symbol, List[String]]()

  def setDocComment(sym: Symbol, doc: List[String]): Unit =
    if doc.nonEmpty then docComments(sym) = doc

  def docComment(sym: Symbol): List[String] =
    docComments.getOrElse(sym, Nil)

  //----------------------------------------------------------------------------
  // Annotations
  //

  type AnnotationsInfo = List[Annotation] | (() => List[Annotation])

  private val annotations = mutable.Map[Symbol, AnnotationsInfo]()

  def setAnnotations(sym: Symbol, annotsInfo: AnnotationsInfo): Unit =
    annotations(sym) = annotsInfo

  def annotations(sym: Symbol): List[Annotation] =
    annotations.get(sym) match
      case None => Nil
      case Some(info) =>
        info match
          case annots: List[Annotation] => annots
          case lazyInfo: (() => List[Annotation]) =>
            val annots = lazyInfo()
            annotations(sym) = annots
            annots

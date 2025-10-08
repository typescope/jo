package phases

import sast.*
import sast.Trees.*
import sast.Symbols.*

import reporting.Reporter

/** This phase rewrites symbol references based on link mappings.
  *
  * Link mappings allow redirecting references from one symbol to another,
  * with subtype checking to ensure type safety.
  */
class LinkRewriter(linkMap: Map[Symbol, Symbol])(using defn: Definitions, rp: Reporter) extends Phase[Unit]:
  val contextObject = Phase.DummyContext

  override def transformIdent(ident: Ident)(using ctx: Context): Word =
    val sym = ident.symbol

    // Only process deferred functions
    if sym.isAllOf(Flags.Defer | Flags.Fun) then
      linkMap.get(sym) match
        case Some(targetSym) =>
          // Replace with target symbol
          Ident(targetSym)(ident.span)
        case None =>
          // Check if deferred function has a default implementation
          if !sym.is(Flags.Default) then
            rp.error(s"Deferred function $sym has no default implementation and is not linked")
          ident
    else
      ident

object LinkRewriter:
  /** Parse link option strings and resolve to symbol mappings.
    *
    * @param linkStrings List of "source=target" strings
    * @param defn Definitions for symbol resolution
    * @return Map from source symbols to target symbols, validated with subtype checking
    */
  def parseLinkMappings(linkStrings: Map[String, String])(using defn: Definitions, rp: Reporter): Map[Symbol, Symbol] =
    val validMappings = scala.collection.mutable.Map.empty[Symbol, Symbol]

    for (sourcePath, targetPath) <- linkStrings do
      // Resolve symbols
      val sourceSymOpt = defn.resolveTermByPathOpt(sourcePath)
      val targetSymOpt = defn.resolveTermByPathOpt(targetPath)

      (sourceSymOpt, targetSymOpt) match
        case (None, _) =>
          rp.error(s"Failed to resolve source symbol: $sourcePath")

        case (_, None) =>
          rp.error(s"Failed to resolve target symbol: $targetPath")

        case (Some(sourceSym), Some(targetSym)) =>
          // Check that source is a deferred function
          if !sourceSym.is(Flags.Defer) then
            rp.error(s"Link source must be a deferred function: $sourcePath")
          else
            // Validate that target type is subtype of source type
            val sourceType = sourceSym.info
            val targetType = targetSym.info

            if !Subtyping.conforms(targetType, sourceType) then
              rp.error(
                s"Link type mismatch: $targetPath (${targetType.show}) is not a subtype of $sourcePath (${sourceType.show})"
              )
            else
              validMappings(sourceSym) = targetSym

    validMappings.toMap

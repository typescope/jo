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
class LinkRewriter(linkMap: Map[Symbol, Symbol])(using defn: Definitions, rp: Reporter) extends Phase:
  // Track which deferred functions have already been reported to avoid duplicate errors
  private val reportedErrors = scala.collection.mutable.Set.empty[Symbol]

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
          if !sym.is(Flags.Default) && !reportedErrors.contains(sym) then
            rp.error(s"Deferred function ${sym.fullName} has no default implementation and is not linked")
            reportedErrors.add(sym)
          ident
    else
      ident

object LinkRewriter:
  class LinkData(mappings: Map[String, String]):

    def resolve(deferMain: Symbol, userMain: Symbol)(using Reporter, Definitions): Map[Symbol, Symbol] =
      val symMap = parseLinkMappings(mappings)
      if symMap.contains(deferMain) then
        Reporter.error(s"The main function ${deferMain.fullName} is already linked to ${userMain.fullName}.")
        symMap
      else
        symMap + (deferMain -> userMain)

    def resolve()(using Reporter, Definitions): Map[Symbol, Symbol] =
      parseLinkMappings(mappings)

    def contains(path: String): Boolean = mappings.contains(path)

    def addUserMappings(userMappings: Map[String, String])(using Reporter): LinkData =
      for (source, userTarget) <- userMappings do
        mappings.get(source) match
          case Some(target) if target != userTarget =>
            Reporter.warn(s"User-supplied link mapping ignored due to conflicts with compiler default: $source=$userTarget (was $source=$target)")
          case _ =>
      end for

      new LinkData(mappings ++ userMappings)

  /** Parse link option strings and resolve to symbol mappings.
    *
    * @param linkStrings List of "source=target" strings
    *
    * @return Map from source symbols to target symbols, validated with subtype checking
    */
  def parseLinkMappings(linkStrings: Map[String, String])(using defn: Definitions, rp: Reporter): Map[Symbol, Symbol] =
    val validMappings = scala.collection.mutable.Map.empty[Symbol, Symbol]

    for (sourcePath, targetPath) <- linkStrings do
      // Resolve symbols
      val sourceSymOpt = defn.resolveTermOpt(sourcePath)
      val targetSymOpt = defn.resolveTermOpt(targetPath)

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

            // 1. First compare type, then compare effects for better error messages
            // 2. If only optional context params differ, retrofit by synthesizing the defaults instead of reporting errors

            if !Subtyping.conforms(targetType, sourceType) then
              rp.error(
                s"Link type mismatch: $targetPath (${targetType.show}) is not a subtype of $sourcePath (${sourceType.show})"
              )
            else
              validMappings(sourceSym) = targetSym

    validMappings.toMap

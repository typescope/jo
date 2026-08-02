package tool.template

import tool.Result

/** A parsed `jo new --template` ref: `[host:]identifier[#gitref][:name]`.
 *
 *  `identifier` is not interpreted here — its shape (e.g. GitHub's flat
 *  `owner/repo`) is host-specific and owned by that host's [[TemplateProvider]].
 *
 *  `gitref` sits between `identifier` and `name`, not after `name`, because
 *  it scopes the whole repo fetch (it's part of the URLs a `TemplateProvider`
 *  hits) while `name` is a manifest lookup resolved only after that fetch
 *  completes — the same order GitHub's own `tree/{branch}/{path}` URLs use.
 */
case class TemplateRef(host: String, identifier: String, name: Option[String], gitref: String):
  /** Renders back in `[host:]identifier[#gitref][:name]` form. `host` and
   *  `gitref` are omitted when they're just the defaults (`gh`, `HEAD`) — an
   *  unpinned ref printing `#HEAD` would be noise, not information. But a
   *  `gitref` that *was* pinned (`#v2`) always shows: a success message
   *  built from this must not let a pinned source print as if it weren't.
   */
  def canonical: String =
    val hostPart   = if host == TemplateRef.defaultHost then "" else s"$host:"
    val gitrefPart = if gitref == TemplateRef.defaultGitref then "" else s"#$gitref"
    s"$hostPart$identifier$gitrefPart" + name.map(n => s":$n").getOrElse("")

object TemplateRef:
  private val defaultHost   = "gh"
  private val defaultGitref = "HEAD"

  /** Parses `[host:]identifier[#gitref][:name]`.
   *
   *  Proceeds outside-in. The host prefix, if any, is only ever recognized
   *  before the first `/`, so it's stripped first. `:name` is then split off
   *  from the end: git ref names can't contain `:` (disallowed by
   *  `git-check-ref-format`), so any colon remaining after the host prefix
   *  is unambiguously the name separator, regardless of whether a `#gitref`
   *  precedes it. What's left after that splits on `#` for `gitref`.
   */
  def parse(raw: String): Result[TemplateRef] =
    if raw.isEmpty then
      return Result.Err(s"empty template ref (expected [host:]identifier[#gitref][:name])")

    val slashIdx = raw.indexOf('/')
    val searchLimit = if slashIdx >= 0 then slashIdx else raw.length
    val hostColonIdx = raw.indexOf(':') match
      case idx if idx >= 0 && idx < searchLimit => Some(idx)
      case _                                    => None

    hostColonIdx match
      case Some(idx) =>
        val host = raw.substring(0, idx)
        if TemplateProvider.supportedHosts.contains(host) then parseRest(host, raw.substring(idx + 1))
        else Result.Err(s"unsupported template host '$host' (supported: ${TemplateProvider.supportedHosts.toList.sorted.mkString(", ")})")

      case None =>
        parseRest(defaultHost, raw)

  private def parseRest(host: String, rest: String): Result[TemplateRef] =
    val (beforeName, name) = rest.lastIndexOf(':') match
      case idx if idx >= 0 => (rest.substring(0, idx), Some(rest.substring(idx + 1)))
      case _               => (rest, None)

    if name.exists(_.isEmpty) then
      return Result.Err(s"invalid template ref: empty template name after ':'")

    val (identifier, gitref) = beforeName.indexOf('#') match
      case idx if idx >= 0 => (beforeName.substring(0, idx), beforeName.substring(idx + 1))
      case _               => (beforeName, defaultGitref)

    if gitref.isEmpty then
      Result.Err(s"invalid template ref: empty git ref after '#'")

    else if identifier.isEmpty then
      Result.Err(s"invalid template ref: missing identifier")

    else
      Result.Ok(TemplateRef(host, identifier, name, gitref))

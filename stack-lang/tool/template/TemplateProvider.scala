package tool.template

import java.nio.file.Path

import tool.Result

/** Resolves and fetches third-party `jo new --template` sources.
 *
 *  One implementation per host (`GithubTemplateProvider` for `gh`), plus
 *  `LocalTemplateProvider`, which reads a fixture straight off disk instead
 *  of going over the network — that's what lets `jo new --template`
 *  scenarios be tested without a server.
 */
trait TemplateProvider:
  /** The manifest entries declared by `jo-templates.jsonl` at `identifier`/`gitref`.
   *
   *  Used only by `--list`, a standalone lookup with nothing to combine it
   *  against — unlike `fetch` below, there's no risk in this being a fetch
   *  of its own.
   */
  def manifest(identifier: String, gitref: String): Result[List[TemplateEntry]]

  /** Fetches `identifier`/`gitref` exactly once and populates `destDir` with
   *  the template `name` resolves to.
   *
   *  `name` must be resolved against the manifest found in that same fetch,
   *  never one obtained separately — see `TemplateArchive.extract` for why
   *  that would be unsound when `gitref` is mutable.
   */
  def fetch(identifier: String, gitref: String, name: Option[String], destDir: Path): Result[Unit]

object TemplateProvider:
  private val byHost: Map[String, TemplateProvider] = Map("gh" -> GithubTemplateProvider.default)

  /** Adding a second host is one new implementation plus one entry here —
   *  `New.scala` never needs to change.
   */
  def forHost(host: String): Result[TemplateProvider] =
    byHost.get(host) match
      case Some(provider) => Result.Ok(provider)
      case None            => Result.Err(s"error: unsupported template host '$host' (supported: ${byHost.keys.toList.sorted.mkString(", ")})")

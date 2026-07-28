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
  /** The manifest entries declared by `jo-templates.jsonl` at `identifier`/`gitref`. */
  def manifest(identifier: String, gitref: String): Result[List[TemplateEntry]]

  /** Populates `destDir` with the contents of `path` (a manifest entry's `path`). */
  def fetch(identifier: String, gitref: String, path: String, destDir: Path): Result[Unit]

object TemplateProvider:
  private val byHost: Map[String, TemplateProvider] = Map("gh" -> GithubTemplateProvider.default)

  /** Adding a second host is one new implementation plus one entry here —
   *  `New.scala` never needs to change.
   */
  def forHost(host: String): Result[TemplateProvider] =
    byHost.get(host) match
      case Some(provider) => Result.Ok(provider)
      case None            => Result.Err(s"error: unsupported template host '$host' (supported: ${byHost.keys.toList.sorted.mkString(", ")})")

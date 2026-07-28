package tool.template

import tool.{Json, Result}

/** One entry declared in a `jo-templates.jsonl` manifest.
 *
 *  `path` is relative to the repo root; it is never typed by the ref
 *  consumer (see `TemplateRef`) — only the template author, in the manifest.
 */
case class TemplateEntry(name: String, path: String, description: Option[String])

object TemplateManifest:
  private val nameRegex = "^[a-zA-Z0-9][a-zA-Z0-9_-]*$".r

  /** Parses `jo-templates.jsonl`, one JSON object per line.
   *
   *  All validation (malformed JSON, duplicate names, invalid paths) happens
   *  eagerly here, before any name resolution — a bad manifest is reported
   *  once, up front.
   */
  def parse(text: String): Result[List[TemplateEntry]] =
    text.linesIterator.zipWithIndex.foldLeft(Result.Ok(List.empty[TemplateEntry])): (acc, lineAndIdx) =>
      acc.flatMap: entries =>
        val (line, i) = lineAndIdx

        if line.trim.isEmpty then
          Result.Ok(entries)

        else
          parseLine(line.trim) match
            case Left(err) =>
              Result.Err(s"error: malformed line ${i + 1} in jo-templates.jsonl: $err")

            case Right(entry) =>
              validate(entry, entries).map(_ => entries :+ entry)

  /** Resolves which entry a ref selects, per the rules in templates.md:
   *  an explicit `:name` must match exactly; with no `:name`, the manifest
   *  must declare exactly one entry. Ambiguity is always an error, never
   *  guessed.
   */
  def resolve(entries: List[TemplateEntry], requestedName: Option[String], repoLabel: String): Result[TemplateEntry] =
    requestedName match
      case Some(name) =>
        entries.find(_.name == name) match
          case Some(entry) => Result.Ok(entry)
          case None =>
            Result.Err(s"error: no template '$name' in $repoLabel. Available: ${entries.map(_.name).mkString(", ")}")

      case None =>
        entries match
          case Nil          => Result.Err(s"error: jo-templates.jsonl in $repoLabel declares no templates")
          case entry :: Nil => Result.Ok(entry)
          case many         => Result.Err(s"error: $repoLabel declares multiple templates, pick one: ${many.map(_.name).mkString(", ")}")

  // ---- Internals ---------------------------------------------------------------

  private def parseLine(line: String): Either[String, TemplateEntry] =
    Json.parseObj(line).flatMap: obj =>
      for
        name <- requireStr(obj, "name")
        path <- requireStr(obj, "path")
      yield
        val description = obj.get("description").collect { case s: String => s }
        TemplateEntry(name, path, description)

  private def requireStr(obj: Map[String, Any], key: String): Either[String, String] =
    obj.get(key) match
      case Some(s: String) => Right(s)
      case Some(_)          => Left(s"'$key' must be a string")
      case None             => Left(s"missing required field '$key'")

  private def validate(entry: TemplateEntry, existing: List[TemplateEntry]): Result[Unit] =
    if existing.exists(_.name == entry.name) then
      Result.Err(s"error: duplicate template name '${entry.name}' in jo-templates.jsonl")

    else if !nameRegex.matches(entry.name) then
      Result.Err(s"error: invalid template name '${entry.name}' in jo-templates.jsonl (must match [a-zA-Z0-9][a-zA-Z0-9_-]*)")

    else if entry.path.startsWith("/") || entry.path.split("/").contains("..") then
      Result.Err(s"error: invalid path '${entry.path}' for template '${entry.name}' in jo-templates.jsonl (must be relative, no '..' segments)")

    else
      Result.unit

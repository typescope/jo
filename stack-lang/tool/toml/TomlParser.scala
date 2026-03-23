package tool.toml

import Token.*

/** Recursive descent TOML parser for the jo build tool subset. */
class TomlParser(tokens: List[ScannedToken]):
  private var pos = 0

  // ---- Token stream helpers ------------------------------------------------

  private def cur: ScannedToken = tokens(pos)
  private def curTok: Token = cur.token
  private def curLine: Int = cur.line

  private def peek: Token =
    if pos + 1 < tokens.length then tokens(pos + 1).token else TEOF

  private def advance(): ScannedToken =
    val t = tokens(pos)
    if pos < tokens.length - 1 then pos += 1
    t

  private def skipNewlines(): Unit =
    while curTok == TNewline do advance()

  private def expect(tok: Token): ScannedToken =
    if curTok != tok then
      throw TomlError(s"expected $tok but got $curTok", curLine)
    advance()

  // ---- Top-level -----------------------------------------------------------

  /** Parse a TOML document and return a nested map. */
  def parse(): TomlDoc =
    // Accumulate as flat list of (path, value) pairs; path is a List[String]
    // so quoted keys with dots are never re-split.
    val flat = collection.mutable.ListBuffer.empty[(List[String], TomlValue)]
    val usedPaths = collection.mutable.Set.empty[List[String]]

    // Current table path prefix (empty = root)
    var tablePrefix: List[String] = Nil
    val declaredTables = collection.mutable.Set.empty[List[String]]
    val arrayTableKeys = collection.mutable.Set.empty[List[String]]

    def setKey(path: List[String], value: TomlValue): Unit =
      if usedPaths.contains(path) then
        throw TomlError(s"duplicate key '${path.mkString(".")}'", curLine)
      usedPaths += path
      flat += ((path, value))

    def appendArrayTable(path: List[String], entry: TomlValue): Unit =
      arrayTableKeys += path
      flat += ((path, entry))   // multiple entries with same path = array-of-tables

    skipNewlines()
    while curTok != TEOF do
      if curTok == TLBracket then
        if peek == TLBracket then
          // [[array-of-tables]]
          advance(); advance()
          val path = parseKey()
          expect(TRBracket)
          expect(TRBracket)
          skipNewlines()
          tablePrefix = path
          val entryFlat = collection.mutable.ListBuffer.empty[(List[String], TomlValue)]
          val entryPaths = collection.mutable.Set.empty[List[String]]
          while curTok != TEOF && curTok != TLBracket do
            if curTok == TNewline then skipNewlines()
            else
              val (kv, kvLine) = parseKeyVal()
              if entryPaths.contains(kv._1) then
                throw TomlError(s"duplicate key '${kv._1.mkString(".")}' in array-of-tables", kvLine)
              entryPaths += kv._1
              entryFlat += kv
          val tbl = buildNestedFromPairs(entryFlat.toList, arrayTableKeys = Set.empty)
          appendArrayTable(path, TomlValue.Tbl(tbl))
        else
          // [standard-table]
          advance()
          val path = parseKey()
          expect(TRBracket)
          skipNewlines()
          if declaredTables.contains(path) then
            throw TomlError(s"table '${path.mkString(".")}' defined more than once", curLine)
          declaredTables += path
          tablePrefix = path
      else if curTok == TNewline then
        skipNewlines()
      else
        val (kv, _) = parseKeyVal()
        setKey(tablePrefix ++ kv._1, kv._2)

    buildNestedFromPairs(flat.toList, arrayTableKeys.toSet)

  // ---- Nested map builder --------------------------------------------------

  /** Build a nested TomlDoc from a flat list of (path, value) pairs.
   *  Multiple entries with the same path are treated as array-of-tables. */
  private def buildNestedFromPairs(
    pairs: List[(List[String], TomlValue)],
    arrayTableKeys: Set[List[String]]
  ): TomlDoc =
    val result = collection.mutable.LinkedHashMap.empty[String, TomlValue]
    for (path, value) <- pairs do
      if arrayTableKeys.contains(path) then
        // Accumulate into Arr at the leaf
        insertAt(result, path, value, append = true)
      else
        insertAt(result, path, value, append = false)
    result.toMap

  private def insertAt(
    map: collection.mutable.LinkedHashMap[String, TomlValue],
    path: List[String],
    value: TomlValue,
    append: Boolean
  ): Unit = path match
    case Nil => throw TomlError("empty key", 0)
    case key :: Nil =>
      if append then
        map.get(key) match
          case Some(TomlValue.Arr(existing)) => map(key) = TomlValue.Arr(existing :+ value)
          case None                          => map(key) = TomlValue.Arr(List(value))
          case _                             => throw TomlError(s"key '$key' is not an array", 0)
      else
        map(key) = value
    case key :: rest =>
      val sub = map.get(key) match
        case Some(TomlValue.Tbl(existing)) => collection.mutable.LinkedHashMap.from(existing)
        case None                          => collection.mutable.LinkedHashMap.empty[String, TomlValue]
        case Some(TomlValue.Arr(items)) =>
          // Descend into last array-of-tables entry
          items.lastOption match
            case Some(TomlValue.Tbl(entry)) => collection.mutable.LinkedHashMap.from(entry)
            case _ => throw TomlError(s"cannot descend into non-table array entry at '$key'", 0)
        case _ => throw TomlError(s"key conflict at '$key'", 0)
      insertAt(sub, rest, value, append)
      // Write back
      map.get(key) match
        case Some(TomlValue.Arr(items)) =>
          map(key) = TomlValue.Arr(items.init :+ TomlValue.Tbl(sub.toMap))
        case _ =>
          map(key) = TomlValue.Tbl(sub.toMap)

  // ---- Key and value parsers -----------------------------------------------

  private def parseKey(): List[String] =
    val parts = collection.mutable.ListBuffer.empty[String]
    parts += expectKey()
    while curTok == TDot do
      advance()
      parts += expectKey()
    parts.toList

  private def expectKey(): String =
    curTok match
      case TKey(s) => advance(); s
      case TStr(s) => advance(); s   // quoted keys allowed
      case _ => throw TomlError(s"expected key but got $curTok", curLine)

  /** Returns (path -> value, line-of-key). inInlineTable suppresses newline check. */
  private def parseKeyVal(inInlineTable: Boolean = false): ((List[String], TomlValue), Int) =
    val keyLine = curLine
    val key = parseKey()
    expect(TEquals)
    val value = parseValue()
    if !inInlineTable then
      if curTok == TNewline then advance()
      else if curTok != TEOF && curTok != TRBrace then
        throw TomlError(s"expected newline after value but got $curTok", curLine)
    ((key, value), keyLine)

  private def parseValue(): TomlValue =
    curTok match
      case TStr(s)   => advance(); TomlValue.Str(s)
      case TInt(n)   => advance(); TomlValue.Integer(n)
      case TBool(b)  => advance(); TomlValue.Bool(b)
      case TLBracket => parseArray()
      case TLBrace   => parseInlineTable()
      case _ => throw TomlError(s"expected value but got $curTok", curLine)

  private def parseArray(): TomlValue =
    expect(TLBracket)
    skipNewlines()
    val items = collection.mutable.ListBuffer.empty[TomlValue]
    while curTok != TRBracket do
      items += parseValue()
      skipNewlines()
      if curTok == TComma then
        advance()
        skipNewlines()
      else if curTok != TRBracket then
        throw TomlError(s"expected ',' or ']' in array but got $curTok", curLine)
    expect(TRBracket)
    TomlValue.Arr(items.toList)

  private def parseInlineTable(): TomlValue =
    expect(TLBrace)
    val map = collection.mutable.LinkedHashMap.empty[String, TomlValue]
    skipNewlines()
    while curTok != TRBrace do
      val (kv, _) = parseKeyVal(inInlineTable = true)
      val key = kv._1.mkString(".")
      if map.contains(key) then throw TomlError(s"duplicate key '$key' in inline table", curLine)
      // For inline tables, store as nested structure
      val sub = collection.mutable.LinkedHashMap.from(map)
      insertAt(sub, kv._1, kv._2, append = false)
      map.clear()
      map.addAll(sub)
      skipNewlines()
      if curTok == TComma then
        advance()
        skipNewlines()
      else if curTok != TRBrace then
        throw TomlError(s"expected ',' or '}' in inline table but got $curTok", curLine)
    expect(TRBrace)
    TomlValue.Tbl(map.toMap)

object TomlParser:
  def parse(input: String): TomlDoc =
    val tokens = TomlScanner(input).scanAll()
    TomlParser(tokens).parse()

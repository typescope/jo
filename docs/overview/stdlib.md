# Jo Standard Library Reference

## Primitives
- **Int**: `+`, `-`, `*`, `/`, `%`, comparisons (`<`, `>`, `<=`, `>=`, `==`, `!=`). `.toString`.
- **Float**: `.toInt`, `.toString`. Same arithmetic operators.
- **Bool**: `true`, `false`. `&&`, `||`, `!`, `.toString`.
- **Char**: `.toInt`, `.toString`.
- **IO**: `println(msg)`, `print(msg)` — available via `receives stdout`.
- **Pair[A, B]**: `a ~ b` creates a pair. `val a ~ b = pair` to extract or use pattern match.

## String
Create: `"hello"`, `"hello \{name}"` (interpolation), `"""` for multi-line (content on next line).
- `.size`: Int — number of code points
- `.get(i)`: Char — code point at index
- `.+(other)`: String — concatenation
- `.==(other)`, `.!=(other)`: Bool
- `.substring(from, len)`: String — from index, len code points
- `.slice(from, len)`: String — alias of substring
- `.indexOf(other)`: Int — first occurrence, or -1
- `.lastIndexOf(other)`: Int
- `.indexOfFrom(other, from)`: Int — search from position
- `.contains(other)`: Bool
- `.startsWith(prefix)`: Bool
- `.endsWith(suffix)`: Bool
- `.trim`, `.trimStart`, `.trimEnd`: String — strip ASCII whitespace
- `.toLower`, `.toUpper`: String
- `.replace(target, replacement)`: String — replace all occurrences
- `.split(separator)`: List[String]
- `.splitBy(pat: Regex)`: List[String]
- `.lines`: List[Text]
- `.toInt`: Int (aborts on invalid input)
- `.toIntOpt`: Option[Int]
- `.toFloat`: Float (aborts on invalid input)
- `.toFloatOpt`: Option[Float]
- `.*(n)`: String — repeat n times
- `.isEmpty`: Bool
- `.compareTo(other)`: Int
- `.iterator`: Iterator[Char]
- `.exists(pat: Regex)`: Bool
- `.matchFirst(pat: Regex)`: Option[Match]
- `.matchAll(pat: Regex)`: List[Match]
- `.replaceFirst(pat: Regex, f: Match => String)`: String
- `.replaceAll(pat: Regex, f: Match => String)`: String

## List[T]
Create: `[1, 2, 3]`, `List.empty[T]`, `List.fill(n, value)`, `List.tabulate(n, f)`.
- `.size`: Int
- `.isEmpty`: Bool
- `.get(i)`: T — element at index
- `.+(v)`: List[T] — append
- `.prepend(v)`: List[T]
- `.++(other)`: List[T] — concatenate
- `.updated(i, value)`: List[T]
- `.slice(from, len)`: List[T]
- `.take(n)`, `.drop(n)`: List[T]
- `.reverse`: List[T]
- `.map(f)`: List[S]
- `.select(pred)`: List[T] — filter (keep matching)
- `.exclude(pred)`: List[T] — filter (remove matching)
- `.fold(zero, f)`: S — left fold with `f: (S, T) => S`
- `.find(pred)`: Option[T]
- `.exists(pred)`: Bool
- `.forall(pred)`: Bool
- `.contains(value)`: Bool
- `.count(pred)`: Int
- `.join(separator)`: String
- `.sort`: List[T] — sort (elements need `.compareTo`)
- `.sortBy(f)`: List[T] — sort by key function
- `.distinct`: List[T]
- `.groupBy(f)`: Map[K, List[T]]
- `.zip(other)`: List[T ~ S]
- `.zipWithIndex`: List[T ~ Int]
- `.partition(pred)`: List[T] ~ List[T]
- `.intersperse(sep)`: List[T]
- `.minOpt`, `.maxOpt`: Option[T]
- `.iterator`: Iterator[T]

## Option[T]
`Some(value)` or `None` (singleton object).
- `.isEmpty`: Bool
- `.getOrElse(default)`: T

## Map[K, V]
Create: `{"key": value, ...}`, `Map.empty[K, V]`.
- `.size`: Int
- `.isEmpty`: Bool
- `.contains(k)`: Bool
- `.get(k)`: V — aborts if missing
- `.getOpt(k)`: Option[V]
- `.getOrElse(k, default)`: V
- `.add(k, v)` / `.update(k, v)`: Map[K, V]
- `.remove(k)`: Map[K, V]
- `.++(other)`: Map[K, V] — merge
- `.merge(other, combine)`: Map[K, V] — merge with conflict resolver `(K, V, V) => V`
- `.select(pred)`: Map[K, V] — filter with `(K, V) => Bool`
- `.exclude(pred)`: Map[K, V]
- `.fold(zero, f)`: S — with `f: (S, K, V) => S`
- `.keys`: List[K]
- `.values`: List[V]
- `.keySet`: Set[K]
- `.mapValues(f)`: Map[K, S]
- `.find(pred)`: Option[K ~ V]
- `.toList`: List[K ~ V]

## Set[T]
Create: `{1, 2, 3}`, `Set.empty[T]`.
- `.size`: Int
- `.isEmpty`: Bool
- `.contains(x)`: Bool
- `.+(x)`: Set[T] — add
- `.-(x)`: Set[T] — remove
- `.++(other)`: Set[T] — union
- `.&(other)`: Set[T] — intersection
- `.diff(other)`: Set[T] — difference
- `.intersects(other)`: Bool
- `.subsetOf(other)`: Bool
- `.select(pred)`, `.exclude(pred)`: Set[T]
- `.fold(zero, f)`: S
- `.exists(pred)`, `.forall(pred)`: Bool
- `.find(pred)`: Option[T]
- `.count(pred)`: Int

## Range
Create: `1 to 10` (inclusive), `1 until 10` (exclusive).
- `.step(n)`: Range — set step value (e.g. `1 to 10 step 2`)
- `.withStep(n)`: Range — same as step
- `.toList`: List[Int]
- `.iterator`: Iterator[Int]
- Use in `for` loops: `for i in 1 to 10 do ...`

## mutable.ArrayBuffer[T]
Create: `mutable.ArrayBuffer.empty[T]`, `mutable.ArrayBuffer[1, 2, 3]`.
Import: `import jo.mutable`
- `.size`: Int
- `.get(i)`: T — element at index
- `.set(i, v)`: Unit — update element at index
- `.append(x)` / `.+=(x)`: Unit — add to end
- `.prepend(x)`: Unit — add to front
- `.insert(i, x)`: Unit — insert at index
- `.remove(i)`: T — remove at index, returns removed element
- `.clear`: Unit — remove all elements
- `.appendAll(iter)` / `.++=(iter)`: Unit — append from iterator
- `.fold(zero, f)`: S
- `.exists(pred)`: Bool
- `.forall(pred)`: Bool
- `.toList`: List[T]
- `.slice(from, len)`: List[T]
- `.iterator`: Iterator[T]

## mutable.Set[T]
Create: `val s: mutable.Set[Int] = {}`, `val s: mutable.Set[Int] = {1, 2, 3}`.
Import: `import jo.mutable`
- `.size`: Int
- `.contains(x)`: Bool
- `.add(x)` / `.+=(x)`: Unit — add element
- `.remove(x)` / `.-=(x)`: Unit — remove element
- `.addAll(xs)` / `.++=(xs)`: Unit — add all from list
- `.clear`: Unit
- `.fold(zero, f)`: S
- `.exists(pred)`: Bool
- `.forall(pred)`: Bool
- `.toList`: List[T]
- `.iterator`: Iterator[T]

## mutable.Map[K, V]
Create: `val m: mutable.Map[String, Int] = {}`, `val m: mutable.Map[String, Int] = {"a": 1, "b": 2}`.
Import: `import jo.mutable`
- `.size`: Int
- `.contains(k)`: Bool
- `.get(k)`: V — aborts if missing
- `.getOpt(k)`: Option[V]
- `.getOrElse(k, default)`: V
- `.update(k, v)` / `.add(k, v)` / `.set(k, v)`: Unit — insert or update
- `.remove(k)`: Unit
- `.clear`: Unit
- `.fold(zero, f)`: S — with `f: (S, K, V) => S`
- `.keys`: List[K]
- `.values`: List[V]
- `.toList`: List[K ~ V]
- `.iterator`: Iterator[K ~ V]

# Changelog

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Changed

- Singleton object type tests use reference equality in the Python and JavaScript
  backends, avoiding `isinstance` and `instanceof` checks.

## [0.14.7] - 2026-10-29

### Added

- Types with a `==` method can use infix `!=` without defining it separately.
  The compiler expands the global intrinsic to the negation of that equality
  method. Existing `!=` methods retain precedence. ([#201], [JIP 0010])

[JIP 0010]: docs/jips/0010-intrinsic-not-equals.md

### Fixed

- Python `String.lines` and `String.lineIterator` search for newlines with the
  host string operations instead of iterating over every code point. Line
  separators and lazy iteration behavior are unchanged. ([#200])

- Pairs support component-wise equality, lexicographic ordering, and hashing
  through `==`, `compareTo`, and `hashCode`. Collections can synthesize
  `Eq[S ~ T]`, `Ord[S ~ T]`, and `Hash[S ~ T]` from the component instances,
  enabling list equality, sorting, and `distinct` for pairs. ([#199])

### Security

- No security-relevant changes.

### Compatibility

- Intrinsic `!=` requires a suitable non-polymorphic `==` member. An `Eq[T]`
  instance alone does not enable it; existing `!=` methods retain precedence.
- The new equality operators and Pair methods require the updated bundled
  standard library. Use the compiler and runtime libraries from this release
  together.
- SAST format 2.0, build specifications, and `.joy` package formats are unchanged.
  Existing libraries do not require recompilation for format compatibility.

[#199]: https://github.com/typescope/jo/pull/199
[#200]: https://github.com/typescope/jo/pull/200
[#201]: https://github.com/typescope/jo/pull/201

## [0.14.6] - 2026-10-27

### Changed

- `jo versions use <version>` installs a published compiler version when it is
  not already installed, then activates it. Installation failures leave the
  active compiler unchanged. ([#194])

### Fixed

- Private union definitions pass their visibility modifiers to all branches,
  including generated factory functions and patterns. Branches can carry
  private payload types without reporting false visibility leaks. ([#195])

- Decimal `Int` and `Long` literals are checked against their full signed ranges.
  Out-of-range values that previously wrapped to zero or another value with the
  same sign now report an integer literal overflow error. ([#196])

### Security

- No security-relevant changes.

### Compatibility

- Decimal literals must fit the signed `Int` range unless `Long` is expected,
  in which case they must fit its signed 64-bit range. Use a `Long` annotation
  or ascription for larger values. Hexadecimal bit patterns are unchanged.
  ([#196])
- Branches of private unions follow the union's declared visibility. Access
  outside that scope is rejected. ([#195])
- SAST format 2.0, build specifications, and `.joy` package formats are unchanged.
  Existing libraries do not require recompilation for format compatibility.

[#194]: https://github.com/typescope/jo/pull/194
[#195]: https://github.com/typescope/jo/pull/195
[#196]: https://github.com/typescope/jo/pull/196

## [0.14.5] - 2026-10-26

### Fixed

- Lambdas with `receives` clauses compile correctly when returned by functions,
  passed through generic calls, or called directly from indexed collections
  such as `ts[0]()`. These cases no longer cause stale-type errors with
  `--check-tree` or compiler crashes. ([#192])
- Function types used as operands in type expressions are parenthesized when
  printed, preserving the grouping of their arrows. ([#192])

### Security

- No security-relevant changes.

### Compatibility

- Existing source programs, standard libraries, runtimes, and published packages
  remain compatible.
- SAST format 2.0, build specifications, and `.joy` package formats are unchanged.
  Existing libraries do not require recompilation for format compatibility.

[#192]: https://github.com/typescope/jo/pull/192

## [0.14.4] - 2026-10-24

### Added

- `Float.format` supports general (`.3g`) notation with significant-digit
  precision, choosing fixed or scientific notation after rounding and removing
  trailing fractional zeros. Precision zero is treated as one significant digit.
  ([#184])

### Changed

- Array bounds checks are implemented in the standard library, with private
  unchecked intrinsics for backend access. Public indexing, assignment, and
  clearing retain their bounds checks. ([#189])

### Fixed

- Pattern expressions inside parentheses can span lines, including
  constructor-pattern arguments. ([#185])
- Erasure adapts a `return` value to the result type of the function it
  returns from, rather than to an enclosing label's type. Early returns inside
  tail-call-optimized functions no longer fail tree checking. ([#187])
- `String.startsWith` and `endsWith` are backend intrinsics that use the host
  string's prefix and suffix checks. `endsWith` no longer counts and indexes
  code points across the whole string on JavaScript, and `startsWith` no longer
  walks code point iterators, which were especially slow on Ruby and Python.
  ([#188])

### Security

- No security-relevant changes.

### Compatibility

- Existing source programs and published packages remain compatible. Use the
  compiler, standard library, and runtimes from this release together for the
  updated array intrinsics and general floating-point formatting.
- `String.startsWith` and `endsWith` require both strings to be well-formed
  Unicode. Behavior for inputs containing unpaired surrogates is undefined.
  ([#188])
- SAST format 2.0, build specifications, and `.joy` package formats are unchanged.
  Existing libraries do not require recompilation for format compatibility.

[#184]: https://github.com/typescope/jo/pull/184
[#185]: https://github.com/typescope/jo/pull/185
[#187]: https://github.com/typescope/jo/pull/187
[#188]: https://github.com/typescope/jo/pull/188
[#189]: https://github.com/typescope/jo/pull/189

## [0.14.3] - 2026-10-23

### Added

- `Array.iterator` supports direct `for` loops over arrays.
- `Float.toLong` truncates toward zero, saturates at the signed 64-bit limits,
  and returns zero for NaN.
- `Float.round`, `floor`, and `ceil` return integral `Float` values, preserving
  NaN, infinities, and signed zeros. Halfway values round away from zero.
- `Float.format` supports fixed (`.3f`) and scientific (`.3e`) notation with
  precision from 0 to 10000, rounding the exact binary value to nearest with
  ties to even. Signed zeros, NaN, and infinities are preserved. ([#181])
- `List` provides `==` and `!=` for ordered element equality using `Eq[T]`.
- `Set` provides `==` and `!=` for membership equality. Comparisons require
  orderings that agree on element equivalence.
- `Option` provides `==` and `!=`, comparing contained values with `Eq[T]`.
- `Result` provides `==` and `!=`, comparing matching variants' payloads
  with their `Eq` instances. ([#181])

### Changed

- Documentation uses published `ansi`, `jtest`, and `harpe` packages and the
  Jo 0.14 compatibility line. The home page shows the integration diagram and
  improves code snippet styling on phones. ([#178], [#179])

### Fixed

- `String.startsWith` stops comparing at the first unequal code point, avoiding
  full-string searches and quadratic failed prefix checks on JavaScript. ([#180])

### Security

- No security-relevant changes.

### Compatibility

- Existing source programs and published packages remain compatible. New
  standard library APIs require the updated standard library; use the compiler,
  standard library, and runtimes from this release together. Floating-point
  operations remain available on the interpreter, JavaScript, Ruby, and Python
  backends; native backends do not support `Float`. ([#181])
- SAST format 2.0, build specifications, and `.joy` package formats are unchanged.
  Existing libraries do not require recompilation for format compatibility.
- `String.words` was withdrawn before release; use `split` or `splitBy` with
  explicit separator semantics instead. ([#182])

[#178]: https://github.com/typescope/jo/pull/178
[#179]: https://github.com/typescope/jo/pull/179
[#180]: https://github.com/typescope/jo/pull/180
[#181]: https://github.com/typescope/jo/pull/181
[#182]: https://github.com/typescope/jo/pull/182

## [0.14.2] - 2026-10-22

### Changed

- `List.join` uses native array joining on JavaScript, Ruby, and Python,
  avoiding quadratic string concatenation on Ruby and on Python with nonempty
  separators. ([#171])

### Removed

- The top-level `Int` operators `+`, `-`, `*`, `/`, `%`, `>`, `<`, `>=`, `<=`,
  `==`, and `!=` are removed. The `Int` methods remain. A lambda parameter
  whose type was inferred only from arithmetic or comparison, such as
  `x => x + 1` without an expected type, now needs a type annotation. See
  [JIP-0008]. ([#174])

### Security

- No security-relevant changes.

### Compatibility

- Programs that relied on the removed top-level `Int` operators no longer
  type-check. This includes lambda parameters typed only through arithmetic
  or comparison, and arithmetic with a `Char` on the left, such as
  `c - '0'`, which resolved through the top-level functions because `Char`
  widens to `Int`. Annotate the parameter, or convert explicitly with
  `c.toInt - '0'.toInt`. ([#174])
- SAST format 2.0 remains compatible in both directions with Jo 0.14.0 and
  0.14.1. No published library depends on the removed operators, so existing
  libraries do not require recompilation. Build specifications and `.joy`
  package formats are unchanged.

[#171]: https://github.com/typescope/jo/pull/171
[#174]: https://github.com/typescope/jo/pull/174
[JIP-0008]: https://jo-lang.org/jips/0008-drop-top-level-int-operators

## [0.14.1] - 2026-10-18

### Changed

- `Long.hashCode` mixes the high and low 32 bits, and mutable hash maps and
  sets spread high hash bits into their bucket indices to reduce collisions.
  ([#163])
- Integer shift documentation explicitly excludes negative counts: valid
  counts are `0..31` for `Int` and `0..63` for `Long`. ([#162])

### Removed

- The regex multiline flag `m` is no longer supported. `^` and `$` match only
  the start and end of the entire input; `$` matches after any final newline.
  Use `String.lineIterator` for line-oriented matching. The `i` and `s` flags
  remain available. See [JIP-0007]. ([#162])

### Fixed

- Ruby code generation handles capitalized local names and Ruby keywords used
  as identifiers or members without name conflicts. ([#167])
- Collection `toString` output omits the truncation marker when a collection
  contains exactly 100 elements. Lambda source positions survive SAST
  serialization correctly. ([#164])
- Lambda parameters must either all have type annotations or all omit them;
  partially annotated parameter lists now report a syntax error. ([#166])
- Regex end anchors consistently require the end of the entire input in the
  interpreter and Python backend, including input ending in a newline. ([#162])

### Security

- Ruby string literals escape `#` so literal text cannot become Ruby
  interpolation and execute injected code. Regenerate Ruby output with this
  compiler to apply the fix. ([#167])

### Compatibility

- Regex literals using `(?m)` are rejected, and dynamic regex compilation
  rejects the `m` flag. Migrate line-oriented matching to
  `String.lineIterator`; use `s` when `.` must match newlines. Code relying on
  `$` matching before a final newline must handle that newline explicitly.
  Use the updated compiler, standard library, and runtimes together for the
  revised regex contract. ([#162])
- Lambdas with partially annotated parameters must annotate every parameter
  or omit all annotations. ([#166])
- `Long.hashCode` values and hash collection iteration order can change.
  Collection output no longer includes `...` at exactly 100 elements.
  ([#163], [#164])
- SAST format 2.0 remains compatible in both directions with Jo 0.14.0.
  Existing libraries do not require recompilation for format compatibility;
  regenerate Ruby applications to receive the string escaping fix. Build
  specifications and `.joy` package formats are unchanged.

[#162]: https://github.com/typescope/jo/pull/162
[#163]: https://github.com/typescope/jo/pull/163
[#164]: https://github.com/typescope/jo/pull/164
[#166]: https://github.com/typescope/jo/pull/166
[#167]: https://github.com/typescope/jo/pull/167
[JIP-0007]: https://jo-lang.org/jips/0007-drop-multiline-regex-flag

## [0.14.0] - 2026-10-13

### Added

- Mutable collections provide `isEmpty`; `mutable.List` also provides `push`
  and `pop` for stack operations. `Array.clear` clears an element slot, and
  mutable lists clear removed elements to release retained references. ([#142])
- `compile.location` requests a source location as a value, and
  `SourceLocation.toString` returns `file:line`. ([#143])

### Changed

- `abort`, `assert`, and the `...` placeholder include the source file and line
  in failure messages. `assert` is now a compiler intrinsic: its message and
  source location are evaluated only when its condition is false. ([#143], [#145])
- `Option.getOrElse` and `Result.getOrElse` take an ordinary default value
  instead of a thunk. The default is evaluated before the call. ([#145])
- `String.lines` and `String.lineIterator` return no lines for empty input and
  omit the extra empty line after a final line separator. ([#146])
- Vararg calls with a single splice avoid unnecessary copying, and dynamic
  interop varargs are handled consistently across backends. ([#147])
- String comparison and trimming use faster runtime implementations. Trimming
  removes only ASCII whitespace: space, tab, newline, carriage return, vertical
  tab, and form feed. Python and Ruby use native array construction for
  `Array.fill`. ([#151], [#154], [#156])
- Type checking reports clearer function-call, type-pattern, and type-mismatch
  errors. Pattern coverage tracks the correct input type through nested,
  generic, and guarded patterns. SAST serialization preserves source positions
  for parameter defaults and auto candidates, and validates pattern data.
  Incompatible SAST files report compatible compiler versions and rebuild
  guidance. ([#144], [#157], [#158], [#160])

### Removed

- Nullary thunk adaptation: passing an expression where `() => T` is expected
  now requires an explicit `() => expression`. See [JIP-0006]. ([#145])

### Fixed

- JavaScript, Ruby, and Python code generation preserves grouping for nested
  expressions. ([#149])
- Parser syntax errors are reported without crashing, including malformed
  string interpolations. ([#150])
- Closures created in Python and Ruby tail-recursive loops capture the values
  from their own iteration. ([#152])
- Decimal `Float` parsing handles rounding, overflow, and long mantissas with
  matching exponents correctly. ([#153])
- Explicit call type arguments accept function types while preserving indexing
  of lambda-valued elements. ([#155])

### Security

- Array access and clearing check bounds across backends. Mutable lists clear
  removed slots so unused references are not retained. ([#142])
- Type tests and patterns enforce encapsulation restrictions, including
  interface and polymorphic patterns. ([#144])

### Compatibility

- Code relying on implicit nullary thunk adaptation must use `() => expression`.
  `getOrElse` callers passing a thunk must pass a value instead; use explicit
  matching when a fallback must remain lazy. `assert` callers passing a message
  thunk must pass a string expression, which is still evaluated only on failure.
  ([#145])
- String line splitting no longer produces an empty line for empty input or an
  extra empty line for a final separator. Trimming preserves non-whitespace
  ASCII control characters that were previously removed. `String.compareTo`
  guarantees only the sign of its result. ([#146], [#151], [#154])
- Failure output now includes source locations, and stricter type-pattern
  checks can reject previously accepted source. ([#143], [#144])
- Libraries must be recompiled. SAST format 2.0 is incompatible in both
  directions with the previous 1.1 format. Use the compiler, standard library,
  and runtime libraries from this release together. Custom runtimes must adapt
  to the updated deferred functions, including `abortImpl` and `Array.clear`.
  ([#142], [#143], [#157], [#158], [#160])
- Build specifications and `.joy` package formats are unchanged.

[#142]: https://github.com/typescope/jo/pull/142
[#143]: https://github.com/typescope/jo/pull/143
[#144]: https://github.com/typescope/jo/pull/144
[#145]: https://github.com/typescope/jo/pull/145
[#146]: https://github.com/typescope/jo/pull/146
[#147]: https://github.com/typescope/jo/pull/147
[#149]: https://github.com/typescope/jo/pull/149
[#150]: https://github.com/typescope/jo/pull/150
[#151]: https://github.com/typescope/jo/pull/151
[#152]: https://github.com/typescope/jo/pull/152
[#153]: https://github.com/typescope/jo/pull/153
[#154]: https://github.com/typescope/jo/pull/154
[#155]: https://github.com/typescope/jo/pull/155
[#156]: https://github.com/typescope/jo/pull/156
[#157]: https://github.com/typescope/jo/pull/157
[#158]: https://github.com/typescope/jo/pull/158
[#160]: https://github.com/typescope/jo/pull/160
[JIP-0006]: https://jo-lang.org/jips/0006-drop-nullary-thunk-adaptation

## [0.13.11] - 2026-10-02

### Added

- `Array.fill` creates an array containing copies of a value, and
  `Array.tabulate` initializes each element from its index. ([#136])

### Changed

- CLI help aligns command descriptions and keeps `run` and `package`
  descriptions on the same line as their commands. ([#138])
- `Array.create` documentation clarifies that elements must be initialized
  before reading them. ([#136])

### Security

- No security-relevant changes.

### Compatibility

- Existing source code remains compatible. Code using `Array.fill` or
  `Array.tabulate` requires the new standard library.
- SAST files and runtime libraries remain compatible in both directions.
  Existing libraries do not require recompilation. Build specifications and
  `.joy` package formats are unchanged.

[#136]: https://github.com/typescope/jo/pull/136
[#138]: https://github.com/typescope/jo/pull/138

## [0.13.10] - 2026-10-01

### Changed

- Mutable `List`, `Map`, and `Set` constructors accept an optional initial
  capacity. Mutable collection internals are now private, and non-positive
  capacities are handled safely.

### Security

- No security-relevant changes.

### Compatibility

- Mutable `Map` and `Set` constructor signatures changed from internal bucket
  arguments to an optional initial capacity. Existing source using the public
  empty factories remains compatible; code constructing these classes with the
  old internal arguments must use the new constructor API.
- SAST files, runtime libraries, build specifications, and `.joy` packages are
  compatible in both directions. Standard library recompilation is not required
  for existing projects.

## [0.13.9] - 2026-09-29

### Fixed

- Sequence patterns correctly distinguish union cases and track guarded repeat
  coverage, avoiding spurious unreachable and incomplete-match warnings while
  preserving required-element length checks. Wildcard repeats (`.._`) no longer
  create a binding or call `slice` for discarded elements. Generated Ruby names
  avoid reserved numbered parameters. ([#131])
- Effect analysis respects declared `receives` bounds when determining lambda
  captures and call-site context arguments, including transitive calls. ([#129])
- The Ruby backend correctly evaluates multiline return expressions. The native
  stack backend releases loop-condition registers before compiling loop bodies,
  avoiding unnecessary register exhaustion in nested loops. ([#133])

### Security

- No security-relevant changes.

### Compatibility

- Source syntax and library interfaces are unchanged. Corrected sequence-pattern
  diagnostics can report previously accepted redundant fallbacks as unreachable;
  builds treating warnings as errors may need those fallbacks removed.
- Wildcard repeats no longer invoke `slice` for discarded elements.
- No library recompilation is required. `.sast` files, standard and runtime
  libraries remain compatible in both directions. Build specifications and
  `.joy` package formats are unchanged.

[#129]: https://github.com/typescope/jo/pull/129
[#131]: https://github.com/typescope/jo/pull/131
[#133]: https://github.com/typescope/jo/pull/133

## [0.13.8] - 2026-09-28

### Fixed

- The JavaScript and Python backends preserve grouping on the right-hand side
  of left-associative arithmetic operators. The Python backend also emits the
  correct precedence for unary arithmetic operators. ([#127])

### Security

- No security-relevant changes.

### Compatibility

- Existing source code and libraries remain compatible in both directions.
- No library recompilation is required. `.sast` files remain compatible in both
  directions.

[#127]: https://github.com/typescope/jo/pull/127

## [0.13.7] - 2026-09-27

### Changed

- `List` is backed by a flat array instead of a 32-way trie. `get` is O(1)
  with no tree walk. `slice`, `take` and `drop` share the elements in O(1),
  and copy only a slice too small to be worth keeping the array alive.
  Appending with `+` or `++` writes into free space at the end of the shared
  array, so `acc = acc + v` in a loop is O(1) amortized per element. Only the
  first of `l + a` and `l + b` extends `l` in place. The second copies `l`.
- `ListBuilder.result` finishes the builder and hands its array to the list
  it returns. Adding to a builder after `result` aborts. Calling `result`
  again returns a list of the same elements that copies when appended to.
- `List.builder` starts with room for 8 elements instead of 32.
- On the Python and Ruby backends, a list is extended in place only by the
  thread that created its array. Other threads copy, so lists stay immutable
  when threads started through the FFI share them.
- `List.updated` copies the whole list and is O(n), where it was O(log n). Use
  `mutable.List` to change elements by index.

### Security

- No security-relevant changes.

### Compatibility

- Source code that adds to a `ListBuilder` after calling `result` must be
  changed, as it now aborts.
- Every runtime must link two new deferred functions, `jo.ListImpl.newClaim`
  and `jo.ListImpl.tryExtend`. The bundled runtimes provide them. A custom
  runtime supplied with `--link` must provide them too.
- Libraries must be recompiled against this standard library.

## [0.13.6] - 2026-09-27

### Fixed

- Package resolution now reports server and network failures directly instead
  of misreporting every registry index failure as a missing package. An HTTP
  404 remains a `package not found` error. ([#120])
- `jo build`, `jo check` and `jo doc` process a shared dependency only once in
  diamond-shaped module graphs. Their progress output no longer repeats the
  shared module, and output paths are consistently relative to the command's
  working directory. ([#121])

### Security

- No security-relevant changes.

### Compatibility

- Existing source code and libraries remain compatible in both directions.
- Scripts that match command output may observe more accurate registry errors,
  relative paths, and the removal of duplicate progress lines. ([#120], [#121])
- No library recompilation is required. `.sast` files remain compatible in both
  directions.

[#120]: https://github.com/typescope/jo/pull/120
[#121]: https://github.com/typescope/jo/pull/121

## [0.13.5] - 2026-09-19

### Added

- A union type may now contain one lambda type as a branch, as in
  `Int | (() => Int)`. The lambda branch is matched with a type pattern such as
  `case f: (() => Int) => f()`. Since only the fact that a value is a lambda can
  be checked at runtime, a union type has at most one lambda branch, and a
  lambda type pattern must be implied by the scrutinee type. See [Union Types]
  and [JIP-0004]. ([#118])

### Security

- No security-relevant changes.

### Compatibility

- Existing source code continues to compile, and the standard library and
  runtime libraries remain compatible in both directions. ([#118])
- No library recompilation is required. `.sast` files remain compatible in both
  directions.

[#118]: https://github.com/typescope/jo/pull/118
[Union Types]: https://jo-lang.org/language/types/union-types
[JIP-0004]: https://jo-lang.org/jips/0004-lambda-union-branch

## [0.13.4] - 2026-09-16

### Added

- JIP-0003, a rejected proposal for a `@deconstruct` annotation on pattern
  definitions, kept for the record. ([#115])

### Changed

- Pattern matching is faster. A pattern generated for a class now reads the
  fields directly. Other pattern definitions return their outputs instead of
  filling a result array allocated by the caller. ([#116])

### Security

- No security-relevant changes.

### Compatibility

- Existing source code continues to compile, and the standard library and
  runtime libraries remain compatible in both directions. ([#116])
- Libraries must be recompiled. Pattern definitions in `.sast` files now carry
  flags, so `.sast` files produced by earlier compilers are not loadable by this
  release, and files produced by this release are not loadable by earlier
  compilers. The SAST format version is unchanged, so loading a mismatched file
  is not reported as a version mismatch. ([#116])

[#115]: https://github.com/typescope/jo/pull/115
[#116]: https://github.com/typescope/jo/pull/116

## [0.13.3] - 2026-09-13

### Added

- `ListBuilder[T]`, created with `List.builder[T](capacity)`, collects elements
  into a `List` without rebuilding the trie on every append. It provides `add`,
  `addAll`, `addList`, `+=`, `++=`, `size` and `result`. Prefer it to
  `acc = acc + v` in a loop. ([#112])
- The `@discardableResult` annotation marks a definition whose result may be
  dropped without the "value is silently dropped" warning. It is intended for
  chainable builder APIs. Mutating collection methods such as `mutable.Set.add`
  keep returning `Unit`. See [Dropping Values]. ([#112])

### Changed

- `List` construction and bulk operations are substantially faster. List
  literals `[a, b, c]` now desugar to a `ListBuilder`. `List.fill`,
  `List.tabulate`, `map`, `reverse`, `sort`, `slice`, `zip`, `++` and `.toList`
  on `Range`, `Map`, `Set` and the mutable collections build the trie bottom up
  instead of appending element by element. Speedups on the JavaScript backend
  range from 3x to 20x. ([#112])
- `List.distinct` runs in O(n) using hashing instead of O(n²). ([#112])
- Programs compiled to Python import modules named by a literal
  `py.module("...")` once at program start instead of at each call site. Module
  access and list literals are about 10x faster. ([#113])

### Removed

- `List.prepend`. Use `[v] ++ list` instead. ([#112])

### Fixed

- The parser no longer crashes during error recovery on a malformed assignment
  target or on a union type with a missing branch. ([#111])

### Security

- No security-relevant changes.

### Compatibility

- Code that calls `List.prepend` no longer compiles. Replace `list.prepend(v)`
  with `[v] ++ list`. ([#112])
- `List.distinct` now also requires `.hashCode` on the element type, consistent
  with its `==`. A type without a suitable `hashCode` can supply one at the call
  site with an `auto` `Hash[T]` instance. ([#112])
- Code compiled against this standard library may use APIs such as
  `ListBuilder` that earlier standard libraries lack. Old code continues to work
  with the new standard library. ([#112])
- Python programs import every module named by a literal `py.module("...")`
  at startup, so a missing module is reported when the program starts rather
  than at first use. Modules named by a computed string are still imported at
  the call site. ([#113])
- No library recompilation is required. `.sast` files remain compatible in both
  directions.

[#111]: https://github.com/typescope/jo/pull/111
[#112]: https://github.com/typescope/jo/pull/112
[#113]: https://github.com/typescope/jo/pull/113
[Dropping Values]: https://jo-lang.org/language/expressions/drop-values

## [0.13.2] - 2026-09-08

### Changed

- The `asInt`, `asFloat`, `asString` and `asBool` shortcuts on `py.Dynamic`,
  `rb.Dynamic` and `js.Dynamic` now check that the foreign value has exactly the
  corresponding host type and abort with a descriptive error otherwise. In
  particular, Python's `asInt` rejects `bool` values even though Python treats
  `bool` as a subclass of `int`. The generic `cast[T]` remains an unchecked
  reinterpretation for typed wrappers and other advanced uses. ([#107])
- Refreshed the `jo-lang.org` logo and site visuals. ([#109])

### Fixed

- The parser no longer crashes when a reserved word such as `section`, `union`,
  `view`, `interface` or `namespace` appears in a `val` or `for` binder
  position; it reports a source-located diagnostic instead. ([#106])
- The typer no longer crashes on vararg applications whose function or argument
  types are not yet fully initialized. Adaptation and inference for varargs are
  now handled uniformly during type checking rather than inside `.appliedTo`.
  ([#108])

### Security

- Values crossing the Python, Ruby and JavaScript FFI boundaries through the
  `as*` shortcuts are now type-checked at the boundary, so a mistyped foreign
  value fails immediately instead of propagating into typed Jo code. ([#107])

### Compatibility

- Code that relied on the `as*` shortcuts silently reinterpreting a foreign
  value of a different host type now aborts at runtime. Use `cast[T]` where an
  unchecked reinterpretation is intended. ([#107])
- No library recompilation is required.

[#106]: https://github.com/typescope/jo/pull/106
[#107]: https://github.com/typescope/jo/pull/107
[#108]: https://github.com/typescope/jo/pull/108
[#109]: https://github.com/typescope/jo/pull/109

## [0.13.1] - 2026-08-30

### Added

- Auto parameters of type `jo.compile.SourceLocation` receive a compiler-
  synthesized source path and line number when no local auto value is available.
  This lets helpers report the outermost call site without explicit plumbing.
  ([#103])

### Changed

- `jo doc` now takes compiler documentation flags verbatim from each module's
  `doc-options`. The top-level `[doc]` table is no longer supported, and normal
  `compile-options` no longer affect documentation generation. ([#103])

### Fixed

- `jo compile --query` no longer reports a false missing-entry error when a
  selector such as `jo.String` resolves to equivalent symbols loaded from
  multiple compilation units. ([#103])

### Security

- No security-relevant changes.

### Compatibility

- Build specs with a top-level `[doc]` table must move those settings to the
  documented module's `doc-options` array. ([#103])
- No library recompilation is required.

[#103]: https://github.com/typescope/jo/pull/103

## [0.13.0] - 2026-08-25

### Added

- `--source-root <dir>` records source paths relative to `<dir>` in `.sast`
  files and generated documentation, so absolute build paths stay out of
  published artifacts. A file outside the root is recorded by name only. `jo
  build` defaults it to the module's project directory. ([#94])
- The `jo.py`, `jo.rb` and `jo.js` runtime FFI APIs are published together with
  the standard library documentation, and each library lists its namespaces on
  the home page. ([#100])
- Annotations appear as their own kind in the generated documentation, with a
  badge in the UI, and doc comments now attach to annotation declarations.
  ([#100])

### Changed

- Optional context parameters are dropped. A declaration that carries a default,
  `param pageWidth: Int = 80`, is no longer accepted. A declaration now states
  only the requirement and the caller provides the value with `with pageWidth =
  80 in render(document)`. `with`, `receives`, `allow`, shadowing and capture
  keep their current meaning. See [JIP-0002] for the rationale. ([#95])
- `jo.*` is opened for every unit however the standard library arrived.
  `--no-stdlib` selects where the library comes from and no longer suppresses
  the automatic import. ([#100])
- The `jo.IO` capabilities are documented as capabilities and moved into a
  `section IO` of namespace `jo`. The paths `IO.stdout`, `IO.stderr` and
  `IO.args` are unchanged. ([#100])

### Removed

- `IO.stdin`, and with it the standard input capability. A program that reads
  input obtains it from a backend-specific API such as `py.input` and passes it
  on as its own context parameter. `jo.main` correspondingly receives
  `IO.stdout`, `IO.stderr` and `IO.args`. ([#100])

### Fixed

- Private sections are kept out of the generated documentation. ([#100])
- Empty namespaces are hidden in the documentation navigation list. ([#100])

### Security

- Dropping optional context parameters closes a capability hole. A default
  satisfied a requirement silently, so `allow none in e` denied `e` only the
  ambient context that lacked a default rather than all of it, and a `receives
  none` body could keep reading a parameter pinned to its declared value with
  nothing to notice at the call site. Every context parameter is now supplied by
  an explicit binding and is subject to `allow`. ([#95])
- `--source-root` keeps absolute filesystem paths out of `.sast` files and
  generated documentation. ([#94])

### Compatibility

- This release is source-breaking. Code that declares an optional context
  parameter no longer compiles. Give the declaration no default and bind it at
  the call site with `with`. ([#95])
- Libraries must be recompiled. `.sast` files produced by earlier compilers are
  not loadable by this release. Files produced by this release remain loadable
  by earlier compilers. ([#95])
- Code that uses `IO.stdin` no longer compiles, and a custom entry point that
  declares `receives IO.stdin` must drop it. ([#100])
- No build-spec changes are required. `--source-root` is optional, and artifacts
  generated without it record paths as before. ([#94])

[#94]: https://github.com/typescope/jo/pull/94
[#95]: https://github.com/typescope/jo/pull/95
[#100]: https://github.com/typescope/jo/pull/100
[JIP-0002]: https://jo-lang.org/jips/0002-drop-optional-context-params

## [0.12.5] - 2026-08-22

### Added

- Released versions of the standard library API documentation are published at
  `jo-lang.org/stdlib/<version>/`, linked from the site navigation. Each release
  ships its documentation as a `jo-stdlib-docs-<version>.tar.gz` asset, so older
  versions stay available at their own URL. ([#92])
- `jo compile --doc` accepts `--project-version`, shown next to the title in the
  documentation header. `jo doc` supplies it from `[module.<id>.package].version`;
  modules that declare no package show `version unknown`. ([#92])

### Changed

- The generated API documentation has a new layout: a fixed header carrying the
  project title, version and search; namespace navigation on the left; and a
  per-namespace index on the right that highlights the symbol being viewed.
  Definitions are grouped by category, and search ranks exact matches first.
  ([#92])

### Fixed

- `jo.lock` no longer records the compiler patch version, so a project locked
  with one compiler release builds with any compatible release. Unlike packages,
  the build tool does not install compilers, so an exact pin blocked builds
  without a way to satisfy it. ([#91])

### Security

- No security-relevant changes.

### Compatibility

- Existing lock files continue to work. The recorded exact `jo` patch version is
  ignored, and new lock files no longer record one. ([#91])
- No build-spec changes are required. `--project-version` is optional, and
  documentation generated without it renders as before apart from the header
  showing `version unknown`. ([#92])

[#91]: https://github.com/typescope/jo/pull/91
[#92]: https://github.com/typescope/jo/pull/92

## [0.12.4] - 2026-08-05

### Added

- GitHub templates can be listed and scaffolded from private repositories by
  using locally configured Git credentials, with SSH as a fallback when HTTPS
  access fails. ([#89])

### Fixed

- Check-visible dependencies now propagate correctly through source modules and
  registry packages, while dependencies behind a link-only boundary remain
  hidden during checking and are still supplied at link time. ([#88])

### Security

- Private-template access reuses Git's existing credential configuration and
  disables interactive Git and SSH prompts; Jo does not collect or store
  credentials. ([#89])

### Compatibility

- No build-spec changes are required. Projects affected by missing transitive
  check libraries now compile with their declared dependency graph, without
  exposing dependencies across link-only boundaries. ([#88])
- Public GitHub templates and the ZIP fallback continue to work as before;
  private repositories require `git` on `PATH` and preconfigured credentials.
  ([#89])

[#88]: https://github.com/typescope/jo/pull/88
[#89]: https://github.com/typescope/jo/pull/89

## [0.12.3] - 2026-08-03

### Added

- Experimental API symbol queries through `jo compile --query`, with symbol
  and source-file selectors, JSON output, optional field selection, and support
  for querying source files or loaded SAST libraries. ([#85])
- Third-party GitHub template support for `jo new`, including template listing,
  multi-template manifests, pinned Git refs, and preservation of executable
  bits and symlinks when `git` is available. ([#86])

### Security

- Template manifests and archive paths are validated to prevent absolute paths,
  parent traversal, duplicate output ownership, and writes outside the target
  project. Scaffolding is atomic and templates cannot run setup hooks. ([#86])

### Compatibility

- Existing `jo new <name>` and `jo new --lib <name>` behavior is unchanged.
  Template scaffolding is opt-in through `--template`. ([#86])
- API queries are an additive, experimental compiler mode. Existing compilation
  and documentation-generation commands are unchanged. ([#85])

[#85]: https://github.com/typescope/jo/pull/85
[#86]: https://github.com/typescope/jo/pull/86

## [0.12.2] - 2026-07-25

### Added

- Resource packaging support in `jo.toml`, including module-scoped resource
  declarations, resource copying for app builds, resource inclusion in package
  and release outputs, and `resource.Resources` APIs for reading bundled
  resources from Jo code. ([#77])
- JavaScript, Python, and Ruby resource-bundle runtime support. ([#77])
- Default values for class and union parameters. ([#76])

### Changed

- Module and package names are validated as ASCII-only identifiers, giving
  package and resource paths consistent cross-platform behavior. ([#77])
- `py.none`, `rb.nil`, `js.null`, and `js.undefined` now have type `Bottom`, so
  FFI sentinel values can be used in any expected result position. ([#82])

### Fixed

- Generated documentation no longer includes the closing `//]` marker from doc
  comments. ([#72])
- Resource syncing skips unchanged inputs. ([#77])
- Pattern matching now preserves the transformed scrutinee in rescue
  expressions. ([#80])
- SAST output is no longer emitted twice when typing fails. ([#79])

### Security

- No new ambient capabilities are introduced. Resources must be declared in the
  build spec, and resource source/destination paths are validated before they
  are copied or bundled. ([#77])

### Compatibility

- Build specs using non-ASCII module names, package names, or package
  dependency names are now rejected. ([#77])
- Projects can opt into the new `resources` build-spec entries, but existing
  projects without resource declarations do not need changes.

[#72]: https://github.com/typescope/jo/pull/72
[#76]: https://github.com/typescope/jo/pull/76
[#77]: https://github.com/typescope/jo/pull/77
[#79]: https://github.com/typescope/jo/pull/79
[#80]: https://github.com/typescope/jo/pull/80
[#82]: https://github.com/typescope/jo/pull/82

## [0.12.1] - 2026-07-16

### Fixed

- `link = true` source dependencies no longer expose their transitive checking
  dependencies to the depending module. Those transitive dependencies are still
  passed as link-time inputs, so linked implementations can use their own
  dependencies without leaking those names to user code.

### Security

- No new ambient capabilities are introduced. The build tool now preserves the
  checking boundary for linked dependencies by keeping their implementation
  dependencies out of the dependent module's check scope.

### Compatibility

- No build-spec changes. Projects that relied on accidentally accessing a
  transitive dependency through a `link = true` dependency must declare that
  dependency directly if it is intended to be visible to source code.

## [0.12.0] - 2026-07-16

### Added

- Multi-module project support in `jo.toml`, including ordered
  `[module.<id>]` sections, per-module source roots, source-module
  dependencies, module-aware build outputs, and module-aware
  `build`/`clean`/`deps`/`doc`/`lock`/`package`/`run`/`test` commands. ([#67])
- App-module dependencies can inherit and override link wiring, enabling test
  modules and app-as-library workflows. ([#67])
- `Bytes`, an immutable byte sequence, with `fill`, `size`, `get`, `slice`,
  and `toBase64` support across the interpreter, JavaScript, native, Python,
  and Ruby backends. ([#65])
- JIP-0001, documenting the regularized expression syntax. ([#62])

### Changed

- `jo.toml` now separates source dependencies (`modules`) from registry
  dependencies (`packages`), uses per-module `platform`, and requires explicit
  per-module `src` entries. ([#67])
- Expression syntax is more regular: `match`, dot chains, indented colon calls,
  `rescue`, `allow`, and `with` may be used in more expression positions, while
  inline colon calls are rejected directly inside comma-delimited contexts.
  ([#62])
- Package metadata now records the module platform from the build spec. ([#67])

### Fixed

- Class-parameter factory functions and patterns are synthesized for all
  parameterized classes unless overridden by user definitions. ([#61])
- Documentation comments are preserved when annotations appear before the
  documented definition or member. ([#63])
- Rebuilds clean stale SAST output when source files are removed or changed.
  ([#64])
- Dependency diagnostics cover missing `jo.toml`, missing `src`, module cycles,
  undefined modules, and clearer module labels. ([#67])

### Security

- No new ambient capabilities are introduced. FFI access remains explicit per
  module through `enable-ffi`, and `Bytes` exposes an opaque Jo API rather than
  backend-native byte-buffer representations. ([#65], [#67])

### Compatibility

- Existing single-module `jo.toml` files must be migrated to the
  `[module.<id>]` shape with explicit `src`; top-level source and dependency
  fields are no longer the current build-spec form. ([#67])
- Registry dependencies that were previously listed as source dependencies must
  move to `packages`, while source dependencies move to `modules`. ([#67])
- New code using `Bytes` requires the 0.12 compiler, standard library, and
  runtime backend support. ([#65])
- Inline colon calls that previously parsed in comma-delimited positions now
  require parentheses or indentation. ([#62])

[#61]: https://github.com/typescope/jo/pull/61
[#62]: https://github.com/typescope/jo/pull/62
[#63]: https://github.com/typescope/jo/pull/63
[#64]: https://github.com/typescope/jo/pull/64
[#65]: https://github.com/typescope/jo/pull/65
[#67]: https://github.com/typescope/jo/pull/67

## [0.11.5] - 2026-07-10

### Fixed

- Snapshot definition ordering during denotation transforms, fixing lambda
  erasure cases. ([#54])
- Context parameter desugaring for overriding methods now respects the target
  interface signature. ([#55])
- New-expression parsing in colon-call argument blocks. ([#59])

[#54]: https://github.com/typescope/jo/pull/54
[#55]: https://github.com/typescope/jo/pull/55
[#59]: https://github.com/typescope/jo/pull/59

## [0.11.4] - 2026-07-08

### Added

- `Long.parse` and broader numeric parsing coverage. ([#48])
- Compile options documentation, including `--explicit-this` and
  `--no-star-import` usage from the command line and build specs. ([#49])
- Doom Emacs installation notes for the Jo Emacs mode. ([#45])

### Fixed

- `Float.toInt` now truncates correctly in the JavaScript backend. ([#47])
- Synthesized named varargs now use the widened argument type as their type
  argument. ([#52])
- The data-query-agent and sandbox-agent examples use the correct compilation
  command. ([#46])

[#45]: https://github.com/typescope/jo/pull/45
[#46]: https://github.com/typescope/jo/pull/46
[#47]: https://github.com/typescope/jo/pull/47
[#48]: https://github.com/typescope/jo/pull/48
[#49]: https://github.com/typescope/jo/pull/49
[#52]: https://github.com/typescope/jo/pull/52

## [0.11.3] - 2026-07-01

### Added

- Custom commands: a `[commands]` table in `jo.toml` defines named shell
  commands, run as `jo <name>` (built-ins take precedence) or `jo exec <name>`
  (bypassing built-ins). ([#41])

### Fixed

- Test builds now respect the test module's `compile-options`. ([#40])

[#40]: https://github.com/typescope/jo/pull/40
[#41]: https://github.com/typescope/jo/pull/41

## [0.11.2] - 2026-06-27

### Fixed

- `jo versions install` wrote a launcher pointing at the wrong location for the
  bundled compiler jar, so a version installed this way failed to run. The
  installer now uses the launcher shipped in the release archive. ([#39])

[#39]: https://github.com/typescope/jo/pull/39

## [0.11.1] - 2026-06-27

### Added

- Multiline expression: an expression may continue onto the next line at the
  same or deeper indentation, separated by an infix operator. ([#35])
- Link-only compilation. ([#37])
- `bin/install --native` builds and installs a native executable launcher for
  faster startup with no JVM dependency, plus a docs guide for converting an
  existing install to native via GraalVM Native Image.

### Changed

- `break` and `continue` now have type `Bottom`, so they type-check in any
  expression position. ([#37])
- `jo run` logs at a higher level. ([#37])

### Removed

- Support for typed shape expressions. ([#36])

### Fixed

- `runInteractive`. ([#37])
- Typos in the language tour. ([#36])

[#35]: https://github.com/typescope/jo/pull/35
[#36]: https://github.com/typescope/jo/pull/36
[#37]: https://github.com/typescope/jo/pull/37

## [0.11.0] - 2026-06-18

Tightened numeric semantics for predictable, portable behavior across all
backends (interpreter, JavaScript, Ruby, Python, and native).

### Added

- `Long`, a signed 64-bit integer type, supported on every backend and usable
  in union types. ([#32])
- Bitwise complement operator `~` for `Int` and `Long`. ([#32])

### Changed

- `Int` is now standardized to signed 32-bit on every backend (previously
  platform-dependent), removing C-like portability concerns. ([#32])
- `Byte` is now unsigned with range `[0, 255]`. ([#32])
- `/` and `%` use truncating-toward-zero semantics, well-defined for negative
  operands (e.g. `(0 - 7) / 2 == -3`, `(0 - 7) % 2 == -1`). ([#32])
- Arithmetic overflow (`+`, `-`, `*`, unary `-`) and shifts with a count
  outside the type's bit width are now explicitly unspecified behavior. ([#32])

### Fixed

- Native x86: signed division/modulo ([#24]), signed right shift ([#23]), and
  variable shift lowering ([#19]).
- Reject Python keywords in dynamic interop names. ([#16])
- Harden `ReadBuffer` bounds checks. ([#13])

[#13]: https://github.com/typescope/jo/pull/13
[#16]: https://github.com/typescope/jo/pull/16
[#19]: https://github.com/typescope/jo/pull/19
[#23]: https://github.com/typescope/jo/pull/23
[#24]: https://github.com/typescope/jo/pull/24
[#32]: https://github.com/typescope/jo/pull/32

## [0.10.0] - 2026-06-04

First public release.

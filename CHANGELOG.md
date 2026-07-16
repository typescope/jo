# Changelog

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

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

# Changelog

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

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

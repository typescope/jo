# Changelog

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

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

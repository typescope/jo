# Changelog

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

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

---
author: Fengyun Liu
status: Accepted
created: 2026-11-01
title: Default namespace names
---

# JIP 0011 — Default namespace names

<JipMeta />

## Summary

Keep namespace declarations optional. When a file has no declaration, generate
its default namespace name from its normalized file name and source content:

```text
<normalized-file-name>_<content-hash>
```

The generated namespace follows the ordinary namespace rules. This proposal
only changes the default name.

## Motivation

Previously, a file without a namespace declaration received a namespace derived
only from its file name: `array.jo` became `Array`, and `test-lib.jo` became
`TestLib`.

These generated names participate in ordinary name lookup. A file named
`array.jo` therefore introduces a root namespace named `Array`, which takes
precedence over the implicitly imported standard-library section `jo.Array`:

```jo
// array.jo
def main =
  val values = Array.fill(3, 0)
  println(values.size)
```

The reference to `Array.fill` searches the generated namespace instead of the
standard-library section and fails. The programmer has not declared a namespace
named `Array`, and the relevant declaration is absent from the source text.

## Proposal

For a source file without a namespace declaration:

1. Take the file name without its directory and final extension.
2. Convert it to lowercase using locale-independent casing.
3. Replace characters other than ASCII letters, digits, and underscores with
   underscores. If the result begins with a digit, prefix it with one underscore
   so the generated name is a valid identifier.
4. Compute the SHA-256 digest of the complete source text encoded as UTF-8,
   including comments and whitespace. Use its first 64 bits, encoded as 16
   lowercase hexadecimal characters.
5. Form `<normalized-file-name>_<content-hash>`.

For example, `Array-Utils.jo` receives a name of the form
`array_utils_<content-hash>`. The hash suffix contains 16 hexadecimal characters.
Directories and absolute paths do not contribute to the name.

Existing rules for name lookup, visibility, duplicate definitions, and entry-point
discovery continue to apply.

Files with the same normalized file name and identical contents receive the same
default namespace. Compiling them together follows the ordinary namespace and
duplicate-definition rules.

## Justification

### Why not require a namespace declaration?

Mandatory declarations would avoid synthesized names, but add boilerplate to
every small program, example, experiment, and test. A program consisting of one
`main` function would need a namespace even when nothing refers to that name.

Optional declarations preserve that convenience. Programmers can state a
namespace when its name matters to the program. This follows Jo's principle that
[simple things should be simple](../language/design-principles.md#_7-alan-kays-rule).

### Why not use just the file name?

A basename is neither distinctive enough nor an explicit naming decision. Common
file names such as `array.jo`, `list.jo`, and `utils.jo` can shadow standard-library
names or merge definitions from unrelated directories, which create surprises for
programmers.

Reusing the file name as a namespace name is against Jo's design principle of
_explicitness over implicitness_.

### Why not use an empty namespace?

Putting every undeclared file into a shared empty namespace would pool their
top-level definitions. Two independent files could both define a helper named
`format` or a class named `Config`, then fail with duplicate-definition errors
when compiled together.

Even without duplicate names, a definition in one file could unexpectedly
satisfy a reference in another. Adding a source file would introduce unqualified
names into existing files, creating accidental dependencies between otherwise
unrelated code.

A shared empty namespace therefore replaces namespace-name conflicts with
definition-name conflicts and implicit sharing. Sharing should be made explicit.

### Why a content hash?

A content hash provides a deterministic way to give files with the same
name different namespaces to avoid accidental sharing.
Using a 64-bit prefix keeps generated names shorter than using the full digest.

The hash makes it impossible to reference the generated name as a stable cross-file API.
An explicit namespace declaration provides a stable name for sharing.
That is the purpose of the design: sharing should be made explicit.

## Compatibility

This is a source-incompatible change for code that references the old
namespace names derived from file names. For example, `import TestLib` no longer finds
a file named `test-lib.jo` solely because of that file name. Such code can declare
an explicit namespace and update its imports:

```jo
// test-lib.jo
namespace testlib

def double(n: Int): Int = n + n
```

```jo
// main.jo
import testlib.double

def main = println(double(10))
```

Code with explicit namespace declarations is unaffected. Standalone programs
that do not reference their generated namespace continue to work.

The SAST binary format is unchanged: generated namespaces use the same
representation as explicitly declared namespaces.


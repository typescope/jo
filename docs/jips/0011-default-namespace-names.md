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
its default namespace name from its normalized filename and source content:

```text
__<normalized-file-name>_<content-hash>
```

The generated namespace follows the ordinary namespace rules. This proposal
changes the default name, without introducing another kind of scope or special
lookup, import, or visibility rules.

## Motivation

Previously, a file without a namespace declaration received a namespace derived
only from its filename: `array.jo` became `Array`, and `test-lib.jo` became
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

Filename-only names also merge unrelated files that have the same basename.
Two `utils.jo` files in different directories receive the same namespace. Their
definitions can conflict or accidentally refer to each other.

The default should remain convenient for small programs without introducing
common names or routinely merging unrelated definitions.

## Proposal

### Name generation

For a source file without a namespace declaration:

1. Take the filename without its directory and final extension.
2. Convert it to lowercase using locale-independent casing.
3. Replace characters other than ASCII letters, digits, and underscores with
   underscores.
4. Compute the SHA-256 digest of the complete source text encoded as UTF-8,
   including comments and whitespace, and encode the digest as lowercase
   hexadecimal.
5. Form `__<normalized-file-name>_<content-hash>`.

For example, `Array-Utils.jo` receives a name of the form
`__array_utils_<content-hash>`. The hash in the actual name is the full digest.
Directories and absolute paths do not contribute to the name.

An explicit namespace declaration continues to determine the namespace directly:

```jo
namespace app.arrays

def make: Array[Int] = Array.fill(3, 0)
```

### Ordinary namespace behavior

A generated namespace is registered and resolved like any other root namespace.
Its definitions can be imported or qualified using its generated name. The `__`
prefix is a naming convention, not a new reserved syntax or an access restriction.

Existing rules for name lookup, visibility, duplicate definitions, and entry-point
discovery continue to apply. In particular, the compiler can still find `main`
without the programmer spelling its generated namespace name.

Files with the same normalized filename and identical contents receive the same
default namespace. Compiling them together follows the ordinary namespace and
duplicate-definition rules; this proposal does not give each file a separate
identity beyond its generated name.

## Justification

### Why not require a namespace declaration?

Mandatory declarations would avoid synthesized names, but add boilerplate to
every small program, example, experiment, and test. A program consisting of one
`main` function would need a namespace even when nothing refers to that name.

Optional declarations preserve that convenience. Programmers can state a
namespace when its name matters to the program. This follows Jo's principle that
[simple things should be simple](../language/design-principles.md#_7-alan-kays-rule).

### Why not use just the filename?

A basename is neither distinctive enough nor an explicit naming decision. Common
filenames such as `array.jo`, `list.jo`, and `utils.jo` can shadow standard-library
names or merge definitions from unrelated directories. Renaming a file can then
change how existing expressions resolve.

Lowercasing the name only moves the conflicts to another set of identifiers.
Giving filename-derived namespaces a special lookup priority would introduce
another resolution rule and still leave same-basename files merged.

The normalized filename retains a readable component in generated names. The
content hash distinguishes files with different contents, including files with
the same basename. The generated prefix and digest avoid introducing ordinary
names such as `Array` as defaults.

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
definition-name conflicts and implicit sharing. Requiring explicit namespaces
to avoid those surprises would bring back the boilerplate that optional
declarations are intended to avoid.

### Why a content hash?

A content hash makes generation reproducible without relying on random IDs,
allocation order, or directory layout. Moving the checkout or compiling the same
file from a different directory preserves its generated name. The compiler
already has the source text available, so computing the digest requires no
additional project configuration.

The tradeoff is that editing a file changes its default namespace name, even if
the edit only changes a comment. A generated name is consequently inconvenient
as a stable cross-file API. An explicit namespace declaration provides that
stable name using the existing language mechanism.

This keeps naming predictable while preserving ordinary namespace semantics,
following Jo's principles of
[naming discipline](../language/design-principles.md#_5-naming-discipline) and
[engineering simplicity](../language/design-principles.md#_8-engineering-simplicity).

## Compatibility

This is a source-incompatible change for code that references the old
filename-derived namespace names. For example, `import TestLib` no longer finds
a file named `test-lib.jo` solely because of that filename. Such code can declare
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
that do not reference their generated namespace continue to work, and programs
previously broken by an accidental filename-derived name can now resolve the
intended standard-library name.

The SAST binary format is unchanged: generated namespaces use the same
representation as explicitly declared namespaces.

## Implementation

The parser computes the name in `parsing.Parser.defaultNamespaceName` when no
namespace declaration is present. The existing namespace indexing and resolution
code handles the result.

Python and Ruby code generation encode the new underscore-prefixed names into
valid host-language identifiers. Python avoids double-underscore name mangling
inside methods; Ruby ensures generated class constants start with an uppercase
letter. These are backend naming details and add no namespace rules.

The regression tests cover normalization, the content digest, relocation,
same-basename files with different contents, explicit declarations, imports from
compiled libraries, and class and singleton methods across the backends.

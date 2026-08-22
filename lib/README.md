# Jo Standard Library

The standard library is confined code. It contains no FFI, and it cannot
originate a side effect. Everything defined here is computation over values.

Effects reach it as capabilities. `jo.IO` *declares* them rather than
implementing them:

```jo
param stdout: String => Unit
```

`println` writes to standard output only because something outside the standard
library supplied that `stdout`. Code that is never given the capability cannot
reach standard output at all — not by convention, but because the compiler
rejects the attempt.

So every real side effect a Jo program performs originates in the trusted world,
where FFI is available, and crosses into confined code as a capability resolved
at link time. See
[Two-World Architecture](https://jo-lang.org/security/two-worlds) for how that
boundary is enforced.

## Documentation coverage

These pages are generated from doc comments in the library sources. Coverage is
not complete: some public APIs are not documented yet, and others describe what
an operation does without saying when you would reach for it. Both are being
filled in over coming releases.

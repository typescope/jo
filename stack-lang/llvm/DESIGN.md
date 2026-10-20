# LLVM Backend

A 64-bit native backend. It emits textual LLVM IR and calls `clang` to compile
and link it.

The 32-bit backends in `native/` stay as they are. They are the self-contained
backends and the base for a future RV32 port. This backend shares no compiler
code with them. It reuses the frontend and the generic lowering phases in
`phases/`, and shares the portable part of the runtime.

## Pipeline

```
FrontEnd (shared)
  |> LowerContextParams (shared)
  |> Erasure (shared)        Long joins the untagged types
  |> ElimCapture (shared)
  |> llvm.Boxing             own copy, also boxes Long
  |> llvm.EncodeClass        own copy
  |> llvm.CodeGen            SAST -> LLVM IR text (.ll)
  |> clang                   .ll -> executable
```

There is no `ExplicitAlloc` phase. `CodeGen` lowers records, field reads and
field writes directly to typed `getelementptr`, `load` and `store`.

## Values

| Jo type        | LLVM type |
|----------------|-----------|
| `Bool`         | `i1`      |
| `Byte`         | `i8`      |
| `Char`, `Int`  | `i32`     |
| `Long`         | `i64`     |
| `Float`        | `double`  |
| `Unit`         | `ptr` (always `null`) |
| everything else | `ptr`    |

Numeric values are boxed only when they flow into a union or `Any`, as in the
32-bit backends.

## Memory layout

Every heap object is a sequence of 8-byte slots.

```
slot 0     cid: i32 | gc: i32      header
slot 1..   fields, one slot each
```

- Class instances: `{ header, itable: ptr, fields... }`.
- Closures: `{ header, apply: ptr, underlying: ptr }`.
- Arrays: `{ header, size: i32, elements: ptr... }`.
- Bytes: `{ header, size: i32, bytes... }`.

The `gc` half of the header is reserved for the garbage collector. Uniform
slots keep the layout table for the GC trivial: a slot is either a pointer or
not.

## Runtime

The runtime is written in Jo in the namespace `jo.runtime.native`. It is split
by platform interface:

```
runtime/native/common/         portable: strings, params, regex, arrays, numeric ops
runtime/native/linux32/        raw Linux syscalls, brk allocator, LongRepr, start from stack
runtime/native/linux32/i386/   syscall numbers and the sigaction layout of i386
runtime/native/libc/           libc calls, chunk allocator, i64 Long, main(argc, argv)
runtime/native/libc/linux/     the sigaction layout of glibc
```

`libs/runtime-native` is `common` + `linux32` + `linux32/i386`. This backend
uses `libs/runtime-llvm`, which is `common` + `libc` + `libc/linux`.

- The common runtime does not assume a word size. Memory primitives are
  typed: `readInt`/`writeInt` (`i32`), `readAddr`/`writeAddr` (addresses),
  `readByte`/`writeByte` (`i8`). `wordSize`, `nullAddr`, `isNull` and
  `sameRef` replace casts between addresses and `Int`. The 32-bit backends
  rewrite these primitives to their 32-bit forms in `EncodeClass`.
- Each platform layer provides `intToLong`, `exitImpl`, `writeFd`,
  `floatToStr` and the entry `start`.
- The libc layer calls the C library instead of making raw syscalls. The
  section `jo.runtime.native.LibC` declares the C functions. The backend
  emits a `declare` for each one and calls it directly.
- The C `main` calls the Jo entry `start(argc, argv)`.

## Hooks for the garbage collector

The GC is a separate issue. The backend keeps these hooks so the GC can be
added without touching codegen:

1. Every allocation goes through `GC.alloc` in the runtime. Codegen never
   inlines pointer bumping.
2. The object header reserves 4 bytes for the GC.
3. The pointer layout of every class, closure and array is known to codegen and
   can be emitted as a table.
4. Field stores go through one codegen helper, which tells initialization
   stores from mutation stores. A write barrier goes there.
5. Foreign code never holds Jo pointers.
6. Locals live in `alloca` slots. LLVM promotes them to registers, and a
   shadow stack stays possible if a tracing GC ever needs stack roots.

## Tooling

- `bin/llvmc <file.jo>`: compile with the LLVM backend (dev script).
- `bin/jo compile --llvm <file.jo> -o <exe>`: compile with the built compiler.
- `--keep-ll`: keep the generated `.ll` file next to the executable.
- `--opt <level>`: clang optimization level, `2` by default.
- The backend needs clang 15 or newer, which reads opaque pointers by default.
  clang 14 crashes on them when optimizing, so with clang 14 only `--opt 0`
  works. The driver then passes `-mllvm -opaque-pointers`.
- `./ci` runs the `llvm` mode with `$CLANG`, which defaults to `clang`. Set
  `CLANG=clang-15` where the default clang is older.

## Milestones

Each milestone ends with verification and a commit.

1. **Hello world.** Pipeline, clang driver, runtime skeleton, core codegen:
   literals, locals, `if`, `while`, blocks, labeled returns, direct and
   indirect calls, records, class tests, closures, singleton objects, string
   literals. Verify: `tests/pos/hello.jo` and `tests/pos/fact.jo` print the
   expected output.
2. **Parity with `--reg`.** Interfaces, arrays, `Long` as `i64`, `Bytes`,
   regex, program arguments, stack overflow message. Verify: every test in
   `tests/pos` and `tests/stdlib` that passes with `--reg` passes with
   `--llvm`.
3. **Float.** `double` arithmetic, comparisons, conversions, boxing, and a
   `Float.toString` that matches the other backends. Verify: `tests/numeric`
   passes with `--llvm`.
4. **Integration.** `bin/jo compile --llvm`, the `build` script builds
   `libs/runtime-llvm`, the `ci` script runs an `llvm` mode, docs updated.
   Verify: `./ci` passes the `llvm` suites.

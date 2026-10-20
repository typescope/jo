# LLVM Backend

A 64-bit native backend. It emits textual LLVM IR and calls `clang` to compile
and link it.

The 32-bit backends in `native/` stay as they are. They are the self-contained
backends and the base for a future RV32 port. This backend shares no code with
them. It only reuses the frontend and the generic lowering phases in `phases/`.

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

The runtime is written in Jo in `runtime/llvm/` (namespace `jo.runtime.llvm`)
and precompiled to `libs/runtime-llvm`. It is a separate copy of the 32-bit
runtime, adapted to 64-bit words.

- It calls libc (`write`, `exit`, `malloc`, ...) instead of making raw
  syscalls. The section `jo.runtime.llvm.LibC` declares the C functions. The
  backend emits a `declare` for each one and calls it directly.
- Memory primitives are typed: `readInt`/`writeInt` (`i32`),
  `readAddr`/`writeAddr` (`ptr`), `readByte`/`writeByte` (`i8`). There is no
  cast between pointers and `Int`.
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
- clang 15 or newer reads opaque pointers by default. For clang 14 the driver
  passes `-mllvm -opaque-pointers`.

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

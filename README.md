# [Stk](./stack-lang): Programs as Proses

Stk is a type-safe and functional language designed around the programming
paradigm "programs as proses".

It has the following features:

- The most human-friendly language with prose-like syntax
- The best language for eDSLs (embedded domain-specific languages)
- The language that eliminates dependency hell

It is a perfect fit for the usage of programming languages as human
interface. It can be used in terminals, emails, instant messaging, etc to
perform tasks via a human-like language in a natural way. Combining with AI, it
gets even more powerful in scenarios like message-based programming, low-code
programming, etc.

## Build

```
./build
```

## Run

Run the interpreter:

``` shell
bin/stki tests/pos/fact.stk
```

Run the compiler targeting Linux/x86:

``` shell
bin/stkc -p linux-x86-fast tests/pos/fact.stk -o fact
./fact
```

Run the compiler targeting JavaScript:

``` shell
bin/stkc -p js-opt tests/pos/fact.stk -o fact.js
node fact.js
```

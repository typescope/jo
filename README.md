# Stk

Stk is a statically typed functional programming language with the following features:

- Flexible prefix, infix and postfix functions
- Dynamic parameters for contextual abstraction and parametric global state
- Inverted dependencies to avoid dependency hell
- Indented syntax to avoid semicolons, parentheses and braces

## Build

```
./build
```

## Run

Run the interpreter:

``` shell
bin/run.native tests/pos/fact.stk
```

Run the compiler targeting Linux/x86:

``` shell
bin/regc.native tests/pos/fact.stk -o fact
./fact
```

Run the compiler targeting JavaScript:

``` shell
bin/jsc.native tests/pos/fact.stk -o fact.js
node fact.js
```

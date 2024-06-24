# [stack lang](./stack-lang)

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

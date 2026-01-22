# Get Started

This guide shows you how to run and build Jo programs.

## Your First Program

Create a file `hello.jo`:

```jo
def main = println "Hello, Jo!"
```

## Running Programs

Jo includes an interpreter for quick development:

```bash
# Run directly (interpreter, stdlib loaded automatically)
bin/jo hello.jo
```

## Building Applications

Jo compiles to Ruby, Python, and JavaScript - similar to how Scala compiles to JavaScript via Scala.js.

**Build Ruby (default):**
```bash
bin/jo build hello.jo -o hello.rb
ruby hello.rb
```

**Build JavaScript:**
```bash
bin/jo build -js hello.jo -o hello.js
node hello.js
```

**Build Python:**
```bash
bin/jo build -python hello.jo -o hello.py
python hello.py
```

## Building Libraries

Create reusable libraries that can be shared across projects:

**Build a library (generates .sast files):**
```bash
bin/jo build-lib lib/MyLib.jo -d build/mylib
```

**Use a custom library:**
```bash
bin/jo build app.jo -lib build/mylib -o app.rb
ruby app.rb
```

**Use multiple libraries (colon-separated, in dependency order):**
```bash
bin/jo build app.jo -lib build/core:build/utils -o app.rb
```

## Command Reference

```text
jo <file.jo>                       Run program (default)
jo run [options] <file.jo>         Run with interpreter
jo build [options] <file.jo>       Build application
jo build-lib [options] <file.jo>   Build library (.sast files)
jo help                            Show help

Common options:
  -lib <dirs>              Use precompiled libraries (colon-separated)
  -no-stdlib               Disable automatic stdlib loading
  -explicit-return-type    Require explicit return types

Build options:
  -ruby                    Target Ruby (default)
  -js                      Target JavaScript
  -python                  Target Python
  -o <file>                Output file
  -d <dir>                 Output directory for libraries
```

## What's Next?

- [Language Reference](../language/syntax-overview.md) - Complete language specification
- [Security Demos](../demos/index.md) - See Jo's security features in action

# jo-mode.el - Emacs Major Mode for Jo Programming Language

An Emacs major mode for editing the Jo programming language files.

## Features

- **Syntax Highlighting** for:
  - Keywords (`def`, `val`, `var`, `if`, `match`, `case`, `data`, `type`, `namespace`, `section`, `param`, `receives`, etc.)
  - Built-in types (`Int`, `String`, `Bool`, `List`, `Option`, etc.)
  - Function definitions
  - Type annotations
  - String literals (including multi-line strings with `"""`)
  - Numbers
  - Comments (single-line `//` and multi-line `//[ ... //]`)
  - Operators

- **Indentation Support**
  - Automatic indentation for Jo code structures
  - Smart handling of `def`, `if`, `match`, `case`, `end`, etc.

- **Comment Handling**
  - Single-line comments with `//`
  - Multi-line comments with `//[ ... //]`

- **Electric Pairs**
  - Automatic pairing of `{}`, `[]`, `()`

## Installation

### Manual Installation

1. Download `jo-mode.el` to your Emacs configuration directory:

   ```bash
   # Copy to your .emacs.d directory
   cp jo-mode.el ~/.emacs.d/lisp/
   ```

2. Add the following to your Emacs configuration (`~/.emacs` or `~/.emacs.d/init.el`):

   ```elisp
   ;; Add the directory to load-path if needed
   (add-to-list 'load-path "~/.emacs.d/lisp/")

   ;; Load jo-mode
   (require 'jo-mode)
   ```

3. Restart Emacs or evaluate the configuration.

### Using use-package

If you use `use-package`, add this to your configuration:

```elisp
(use-package jo-mode
  :load-path "~/.emacs.d/lisp/"
  :mode "\\.jo\\'")
```

### Development Setup

If you're working on the Jo compiler itself and want to load the mode directly from the repository:

```elisp
(add-to-list 'load-path "/path/to/jo/tools/emacs/")
(require 'jo-mode)
```

## Usage

Once installed, `jo-mode` will automatically activate when you open `.jo` files.

### Key Bindings

- `RET` - Newline and indent

### Customization

You can customize indentation width:

```elisp
(setq jo-mode-hook
      (lambda ()
        (setq tab-width 2)
        (setq indent-tabs-mode nil)))
```

## Example

Here's how Jo code looks with syntax highlighting:

```jo
namespace MyApp

import jo.IO.stdout

data Result = Success(value: Int) | Error(msg: String)

param logger: Logger

def processData(items: List[Int]): Result receives logger =
  match items
    case Nil => Error("Empty list")
    case x :: xs =>
      val sum = items.fold(0, (acc, n) => acc + n)
      logger.log("Processed " + intToStr(sum))
      Success(sum)
  end

def main: Unit receives stdout =
  println "Hello, Jo!"
  val result = processData(List(1, 2, 3))
  println (show result)
```

## Supported Jo Language Features

Keywords (as defined in Scanner.scala):
- Control flow: `as`, `if`, `then`, `else`, `match`, `case`, `while`, `do`, `end`
- Declarations: `val`, `var`, `fun`, `def`, `type`, `data`, `alias`, `class`, `new`
- Modules: `import`, `namespace`, `section`
- Context parameters: `param`, `receives`, `allow`
- Patterns: `pattern`
- Special: `with`, `begin`, `auto`, `defer`
- Literals: `true`, `false`

Special operators:
- `=>` (arrow), `<:` (subtype), `:` (colon), `=` (equals)

Built-in types:
- `Int`, `String`, `Bool`, `Unit`, `Any`, `List`, `Option`, etc.

## Contributing

Feel free to submit issues or pull requests to improve the mode.

## License

Same as the Jo programming language.

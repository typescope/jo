# jo-mode.el - Emacs Major Mode for Jo Programming Language

An Emacs major mode for editing the Jo programming language files.

## Features

- **Syntax Highlighting** for:
  - Keywords
  - Built-in types
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

## Contributing

Feel free to submit issues or pull requests to improve the mode.

## License

Same as the Jo programming language.

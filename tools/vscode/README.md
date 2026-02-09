# Jo Language - VS Code Extension

Syntax highlighting for the [Jo programming language](../../docs/overview/language-tour.md).

## Installation

Symlink this directory into your VS Code extensions folder:

```bash
ln -s /path/to/jo/tools/vscode ~/.vscode/extensions/jo-language
```

Then restart VS Code. All `.jo` files will have syntax highlighting automatically.

## Features

- Keywords, control flow, and declaration highlighting
- Single-line (`//`) and block (`//[` ... `//]`) comments
- String literals (single-line and multi-line `"""`)
- String interpolation (`\{expr}`)
- Character literals
- Numeric literals (decimal, hex, float, with `_` separators)
- Type name highlighting (capitalized identifiers)
- Function and type declaration highlighting
- Operator highlighting

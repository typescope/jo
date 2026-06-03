# Jo Vim Support

Syntax highlighting for the [Jo programming language](https://jo-lang.org).

## Installation

### Manual

Copy the files into your Vim configuration directory:

```bash
cp tools/vim/syntax/jo.vim ~/.vim/syntax/
cp tools/vim/ftdetect/jo.vim ~/.vim/ftdetect/
```

### vim-plug / Vundle

Add the Jo repository as a plugin source, or copy the files manually as above.

### Neovim

Place the files under `~/.config/nvim/`:

```bash
cp tools/vim/syntax/jo.vim ~/.config/nvim/syntax/
cp tools/vim/ftdetect/jo.vim ~/.config/nvim/ftdetect/
```

## Features

- Keywords, control flow, and declaration highlighting
- Single-line (`//`) and block (`//[` ... `//]`) comments
- String literals (single-line and multiline `"""`) with interpolation (`\{expr}`)
- Character and numeric literals (decimal, hex, with `_` separators)
- Type name highlighting (capitalized identifiers)
- Operator highlighting

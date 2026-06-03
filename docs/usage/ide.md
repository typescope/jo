# Editor Support

Syntax highlighting is available for VS Code, Emacs, and Vim.

## VS Code

Copy the extension from the repository into your VS Code extensions directory:

```bash
cp -r tools/vscode ~/.vscode/extensions/jo-language
```

Restart VS Code. All `.jo` files will have syntax highlighting automatically.

## Emacs

Copy `jo-mode.el` to your Emacs load path and require it:

```bash
cp tools/emacs/jo-mode.el ~/.emacs.d/lisp/
```

```elisp
(add-to-list 'load-path "~/.emacs.d/lisp/")
(require 'jo-mode)
```

Or with `use-package`:

```elisp
(use-package jo-mode
  :load-path "~/.emacs.d/lisp/"
  :mode "\\.jo\\'")
```

## Vim / Neovim

Copy the syntax and filetype detection files:

```bash
# Vim
cp tools/vim/syntax/jo.vim  ~/.vim/syntax/
cp tools/vim/ftdetect/jo.vim ~/.vim/ftdetect/

# Neovim
cp tools/vim/syntax/jo.vim  ~/.config/nvim/syntax/
cp tools/vim/ftdetect/jo.vim ~/.config/nvim/ftdetect/
```

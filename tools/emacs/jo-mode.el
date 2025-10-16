;;; jo-mode.el --- Major mode for editing Jo programming language files -*- lexical-binding: t; -*-

;; Copyright (C) 2025

;; Author: Jo Language Team
;; Keywords: languages
;; Version: 0.1.0

;;; Commentary:

;; This package provides a major mode for editing Jo programming language files.
;; Jo is a secure programming language designed for securing LLM generated code.
;;
;; Features:
;; - Syntax highlighting for keywords, types, and operators
;; - Basic indentation support
;; - Comment handling

;;; Code:

(defvar jo-mode-syntax-table
  (let ((table (make-syntax-table)))
    ;; C-style comments // ...
    (modify-syntax-entry ?/ ". 12" table)
    (modify-syntax-entry ?\n ">" table)

    ;; Multi-line comments //[ ... //]
    ;; We'll handle these specially since they're non-standard

    ;; Strings
    (modify-syntax-entry ?\" "\"" table)

    ;; Operators
    (modify-syntax-entry ?+ "." table)
    (modify-syntax-entry ?- "." table)
    (modify-syntax-entry ?* "." table)
    (modify-syntax-entry ?% "." table)
    (modify-syntax-entry ?< "." table)
    (modify-syntax-entry ?> "." table)
    (modify-syntax-entry ?& "." table)
    (modify-syntax-entry ?| "." table)
    (modify-syntax-entry ?= "." table)
    (modify-syntax-entry ?! "." table)

    ;; Parentheses, brackets, braces
    (modify-syntax-entry ?\( "()" table)
    (modify-syntax-entry ?\) ")(" table)
    (modify-syntax-entry ?\[ "(]" table)
    (modify-syntax-entry ?\] ")[" table)
    (modify-syntax-entry ?\{ "(}" table)
    (modify-syntax-entry ?\} "){" table)

    table)
  "Syntax table for `jo-mode'.")

(defconst jo-keywords
  '("as" "if" "then" "else" "match" "case" "while" "do" "end"
    "val" "var" "fun" "type" "import" "namespace" "with"
    "param" "allow" "def" "receives" "pattern" "section"
    "data" "alias" "begin" "auto" "defer" "class" "new"
    "true" "false")
  "Jo language keywords from Scanner.scala (lines 92-123).")

(defconst jo-types
  '("Int" "String" "Bool" "Unit" "Any" "Nothing" "Bottom"
    "List" "Option" "Some" "None"
    "Array" "Map" "Set")
  "Jo built-in types.")

(defconst jo-constants
  '("true" "false")
  "Jo constants.")

(defvar jo-font-lock-keywords
  (list
   ;; Multi-line comments //[ ... //]
   '("//\\[\\(?:[^/]\\|/[^/]\\)*//\\]" . font-lock-comment-face)

   ;; Keywords
   `(,(regexp-opt jo-keywords 'words) . font-lock-keyword-face)

   ;; Types (capitalized identifiers)
   `(,(regexp-opt jo-types 'words) . font-lock-type-face)

   ;; Type annotations after colons
   '(":\\s-*\\([A-Z][a-zA-Z0-9_]*\\)" 1 font-lock-type-face)

   ;; Function definitions
   '("\\<def\\>\\s-+\\([a-zA-Z_][a-zA-Z0-9_]*\\)" 1 font-lock-function-name-face)

   ;; Data type definitions
   '("\\<data\\>\\s-+\\([A-Z][a-zA-Z0-9_]*\\)" 1 font-lock-type-face)

   ;; Type definitions
   '("\\<type\\>\\s-+\\([A-Z][a-zA-Z0-9_]*\\)" 1 font-lock-type-face)

   ;; Namespace declarations
   '("\\<namespace\\>\\s-+\\([A-Z][a-zA-Z0-9_]*\\)" 1 font-lock-constant-face)

   ;; Section declarations
   '("\\<section\\>\\s-+\\([A-Z][a-zA-Z0-9_]*\\)" 1 font-lock-constant-face)

   ;; Parameter declarations
   '("\\<param\\>\\s-+\\([a-zA-Z_][a-zA-Z0-9_]*\\)" 1 font-lock-variable-name-face)

   ;; Constants (true, false)
   `(,(regexp-opt jo-constants 'words) . font-lock-constant-face)

   ;; String literals
   '("\"[^\"\\\\]*\\(?:\\\\.[^\"\\\\]*\\)*\"" . font-lock-string-face)

   ;; Multi-line strings """..."""
   '("\"\"\"\\(?:[^\"]\\|\"[^\"]\\|\"\"[^\"]\\)*\"\"\"" . font-lock-string-face)

   ;; Numbers
   '("\\<[0-9]+\\(?:\\.[0-9]+\\)?\\>" . font-lock-constant-face)

   ;; Operators (Scanner.scala lines 128-133)
   '("\\(=>\\|<:\\|::\\|->\\|<-\\|++\\|&&\\|||\\|==\\|!=\\|<=\\|>=\\)" . font-lock-builtin-face))
  "Keyword highlighting for `jo-mode'.")

(defun jo-indent-line ()
  "Indent current line for Jo code."
  (interactive)
  (let ((indent-col 0)
        (current-indent (current-indentation)))
    (save-excursion
      (beginning-of-line)
      ;; Don't indent if we're at the beginning of the buffer
      (when (not (bobp))
        (forward-line -1)
        (while (and (not (bobp))
                    (looking-at "^\\s-*$"))
          (forward-line -1))
        (setq indent-col (current-indentation))

        ;; Increase indent after certain keywords
        (when (looking-at ".*\\(def\\|if\\|then\\|else\\|match\\|case\\|while\\|do\\|section\\|with\\|namespace\\|data\\)\\s-*.*$")
          (setq indent-col (+ indent-col 2)))

        ;; Increase indent after opening braces/parens
        (when (looking-at ".*[[({]\\s-*$")
          (setq indent-col (+ indent-col 2)))

        ;; Back to start of current line
        (forward-line 1)

        ;; Decrease indent for 'end' keyword
        (when (looking-at "^\\s-*end\\>")
          (setq indent-col (max 0 (- indent-col 2))))

        ;; Decrease indent for closing braces/parens at start of line
        (when (looking-at "^\\s-*[])}]")
          (setq indent-col (max 0 (- indent-col 2))))

        ;; Decrease indent for 'case' at same level as 'match'
        (when (looking-at "^\\s-*case\\>")
          (setq indent-col (max 0 (- indent-col 2))))

        ;; Decrease indent for 'else' at same level as 'if'
        (when (looking-at "^\\s-*else\\>")
          (setq indent-col (max 0 (- indent-col 2))))))

    ;; Apply the indentation
    (if (<= (current-column) (current-indentation))
        (indent-line-to indent-col)
      (save-excursion (indent-line-to indent-col)))))

(defvar jo-mode-map
  (let ((map (make-sparse-keymap)))
    (define-key map (kbd "RET") 'newline-and-indent)
    map)
  "Keymap for `jo-mode'.")

;;;###autoload
(define-derived-mode jo-mode prog-mode "Jo"
  "Major mode for editing Jo programming language files.

\\{jo-mode-map}"
  :syntax-table jo-mode-syntax-table

  ;; Comment syntax
  (setq-local comment-start "// ")
  (setq-local comment-end "")
  (setq-local comment-start-skip "//+\\s-*")

  ;; Font lock
  (setq-local font-lock-defaults '(jo-font-lock-keywords))

  ;; Indentation
  (setq-local indent-line-function 'jo-indent-line)
  (setq-local tab-width 2)
  (setq-local indent-tabs-mode nil)

  ;; Electric pairs
  (setq-local electric-pair-pairs '((?\{ . ?\}) (?\[ . ?\]) (?\( . ?\))))
  (setq-local electric-pair-text-pairs electric-pair-pairs))

;;;###autoload
(add-to-list 'auto-mode-alist '("\\.jo\\'" . jo-mode))
(add-to-list 'auto-mode-alist '("\\.stk\\'" . jo-mode))

(provide 'jo-mode)

;;; jo-mode.el ends here

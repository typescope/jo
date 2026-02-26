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
    ;; Don't set up comment syntax here - we'll handle it with syntax-propertize
    ;; to properly distinguish between // and //[

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
    "union" "begin" "auto" "defer" "class" "new" "return"
    "true" "false" "private" "interface" "view" "like" "is"
    "for" "in" "object" "this" "pass" "extension" "extend" "override")
  "Jo language keywords from Scanner.scala (lines 92-127).")

(defconst jo-types
  '("Int" "String" "Bool" "Unit" "Any" "Nothing" "Bottom"
    "List" "Option" "Some" "None"
    "Array" "Map" "Set")
  "Jo built-in types.")

(defconst jo-constants
  '("true" "false")
  "Jo constants.")

(defun jo-syntax-propertize-extend-region (start end)
  "Extend the propertization region to include complete block comments.
This ensures that when editing a block comment delimiter, the entire
comment gets re-propertized."
  nil)  ;; We handle region extension in jo-after-change-function instead

(defun jo-syntax-propertize (start end)
  "Apply syntax properties to comments in the region from START to END."
  (save-excursion
    ;; Remove old syntax properties in the entire buffer
    ;; This is necessary because block comments can span large regions
    (remove-text-properties (point-min) (point-max) '(syntax-table nil jo-block-comment nil))

    ;; First pass: handle block comments from start of buffer
    (goto-char (point-min))
    (while (re-search-forward "\\(/\\{2,\\}\\)\\[" nil t)
      (let* ((slash-start (match-beginning 1))
             (slash-count (- (match-end 1) (match-beginning 1)))
             (close-regex (concat (regexp-quote (make-string slash-count ?/)) "\\]")))
        ;; Mark opening as comment start
        (put-text-property slash-start (1+ slash-start)
                           'syntax-table (string-to-syntax "!"))
        ;; Search for matching closer
        (when (re-search-forward close-regex nil t)
          ;; Mark closing as comment end
          (put-text-property (1- (match-end 0)) (match-end 0)
                             'syntax-table (string-to-syntax "!"))
          ;; Mark entire block comment region
          (put-text-property slash-start (match-end 0)
                             'jo-block-comment t))))

    ;; Second pass: handle single-line comments (but skip block comment regions)
    (goto-char (point-min))
    (while (re-search-forward "\\(/\\{2,\\}\\)\\([^[]\\|$\\)" nil t)
      (let ((slash-start (match-beginning 1)))
        ;; Only apply single-line comment syntax if not inside a block comment
        (unless (get-text-property slash-start 'jo-block-comment)
          (put-text-property slash-start (1+ slash-start)
                             'syntax-table (string-to-syntax "<")))))))

(defun jo-after-change-function (beg end old-len)
  "Force re-propertization when comment delimiters are edited."
  (save-excursion
    (save-match-data
      (goto-char beg)
      (beginning-of-line)
      (let ((line-text (buffer-substring-no-properties
                        (line-beginning-position)
                        (line-end-position))))
        ;; Check if line contains comment delimiters
        (when (string-match-p "//\|\[\|\]" line-text)
          (message "JO-MODE: Re-propertizing buffer due to edit on line with: %s" line-text)
          ;; Clear all syntax properties in the buffer
          (with-silent-modifications
            (remove-text-properties (point-min) (point-max)
                                   '(syntax-table nil jo-block-comment nil)))
          ;; Force syntax-propertize to run on entire buffer
          (syntax-ppss-flush-cache (point-min))
          ;; Re-propertize from the beginning of buffer to the end
          (syntax-propertize (point-max))
          ;; Force font-lock to refontify the entire buffer
          (font-lock-flush (point-min) (point-max))
          (font-lock-ensure (point-min) (point-max)))))))

(defvar jo-font-lock-keywords
  (list
   ;; Keywords
   `(,(regexp-opt jo-keywords 'words) . font-lock-keyword-face)

   ;; Qualified private: private[ContainerName]
   '("\\<private\\>\\[\\([A-Z][a-zA-Z0-9_]*\\)\\]" 1 font-lock-constant-face)

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
   '("\\(=\\|=>\\|<:\\|::\\|->\\|<-\\|++\\|&&\\|||\\|==\\|!=\\|<=\\|>=\\)" . font-lock-builtin-face))
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

  ;; Syntax propertize for handling comments
  (setq-local syntax-propertize-function #'jo-syntax-propertize)
  (add-hook 'syntax-propertize-extend-region-functions
            #'jo-syntax-propertize-extend-region nil t)

  ;; Add after-change hook to force re-propertization when delimiters change
  (add-hook 'after-change-functions #'jo-after-change-function nil t)

  ;; Newline ends single-line comments (needed for < syntax in syntax-propertize)
  (modify-syntax-entry ?\n ">" jo-mode-syntax-table)

  ;; Font lock
  (setq-local font-lock-defaults '(jo-font-lock-keywords nil nil nil nil
                                    (font-lock-multiline . t)))

  ;; Indentation
  (setq-local indent-line-function 'jo-indent-line)
  (setq-local tab-width 2)
  (setq-local indent-tabs-mode nil)

  ;; Electric pairs
  (setq-local electric-pair-pairs '((?\{ . ?\}) (?\[ . ?\]) (?\( . ?\))))
  (setq-local electric-pair-text-pairs electric-pair-pairs))

;;;###autoload
(add-to-list 'auto-mode-alist '("\\.jo\\'" . jo-mode))

(provide 'jo-mode)

;;; jo-mode.el ends here

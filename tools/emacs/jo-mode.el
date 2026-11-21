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
    "union" "begin" "auto" "defer" "class" "new" "return" "break" "continue"
    "true" "false" "private" "interface" "view" "is"
    "for" "in" "object" "this" "pass" "extension" "annotation" "rescue")
  "Jo language keywords from Scanner.scala (lines 92-127).")

(defconst jo-types
  '("Int" "String" "Bool" "Unit" "Any" "Nothing" "Bottom"
    "List" "Option" "Some" "None"
    "Array" "Map" "Set")
  "Jo built-in types.")

(defconst jo-constants
  '("true" "false")
  "Jo constants.")

(defconst jo-character-literal-regexp
  (rx "'" (or (seq "\\" (any "bfnrt'\\"))
              (not (any "'\\\r\n"))) "'")
  "A complete Jo character literal; Unicode escapes are only valid in strings.")

(defconst jo-regex-literal-regexp
  (rx "`" (group (optional (seq "(?" (one-or-more (any "ims")) ")")))
      (zero-or-more (or (not (any "`" "\\" "\n"))
                        (seq "\\" (not (any "\n"))))) "`")
  "A Jo regex literal with optional flags.")

(defun jo-syntax-propertize-extend-region (start end)
  "Include complete literals when re-propertizing START through END."
  (unless (and (= start (point-min)) (= end (point-max)))
    (cons (point-min) (point-max))))

(defun jo--find-delimiter (regexp delimiter)
  "Search whole REGEXP matches for DELIMITER and return its start.
Consuming each full run prevents a longer delimiter from matching as a suffix."
  (catch 'found
    (while (re-search-forward regexp nil t)
      (when (equal (match-string-no-properties 0) delimiter)
        (throw 'found (match-beginning 0))))
    (goto-char (point-max))
    nil))

(defun jo--mark-string (start opening-end closing-start end raw)
  "Mark a string spanning START to END with generic string fences.
OPENING-END and CLOSING-START delimit its contents.  RAW disables escapes."
  (put-text-property start opening-end 'syntax-table (string-to-syntax "."))
  (put-text-property start (1+ start) 'syntax-table (string-to-syntax "|"))
  (when closing-start
    (put-text-property closing-start end 'syntax-table (string-to-syntax "."))
    (put-text-property closing-start (1+ closing-start)
                       'syntax-table (string-to-syntax "|")))
  (when raw
    (save-excursion
      (goto-char opening-end)
      (while (search-forward "\\" (or closing-start end) t)
        (put-text-property (1- (point)) (point)
                           'syntax-table (string-to-syntax ".")))))
  (put-text-property start end 'syntax-multiline t))

(defun jo--scan-string (start delimiter)
  "Scan a quoted string or regex from START, ending at DELIMITER or newline."
  (let ((closing nil)
        (regexp (concat "\\\\.\\|[" delimiter "\n]")))
    (while (and (not closing) (re-search-forward regexp nil t))
      (unless (eq (char-after (match-beginning 0)) ?\\)
        (setq closing (match-beginning 0))))
    (unless closing (goto-char (point-max)))
    (jo--mark-string start (1+ start) closing (point) (equal delimiter "`"))))

(defun jo-syntax-propertize (_start _end)
  "Apply literal and comment syntax properties in lexical order.
Scanning complete tokens avoids interpreting comment markers inside literals."
  (save-excursion
    (remove-text-properties (point-min) (point-max)
                            '(syntax-table nil syntax-multiline nil jo-block-comment nil))
    (goto-char (point-min))
    (while (re-search-forward "//+\\|\"+\\|'\\|`" nil t)
      (let* ((start (match-beginning 0))
             (token (match-string-no-properties 0))
             (count (length token)))
        (cond
         ((eq (aref token 0) ?/)
          (if (eq (char-after) ?\[)
              (progn
                (forward-char 1)
                (put-text-property start (1+ start)
                                   'syntax-table (string-to-syntax "!"))
                (let ((closing (jo--find-delimiter "/\\{2,\\}\\]" (concat token "]"))))
                  (when closing
                    (put-text-property (1- (point)) (point)
                                       'syntax-table (string-to-syntax "!")))
                  (put-text-property start (point) 'syntax-multiline t)))
            (put-text-property start (1+ start)
                               'syntax-table (string-to-syntax "<"))
            (end-of-line)))
         ((eq (aref token 0) ?\")
          (cond
           ((>= count 3)
            (let* ((opening-end (point))
                   (closing (jo--find-delimiter "\"\\{3,\\}" token)))
              (jo--mark-string start opening-end closing (point) t)))
           ((= count 2)
            (jo--mark-string start (1+ start) (1+ start) (point) nil))
           (t (jo--scan-string start "\""))))
         ((eq (aref token 0) ?\')
          (goto-char start)
          (if (looking-at jo-character-literal-regexp)
              (let ((end (match-end 0)))
                (goto-char end)
                (jo--mark-string start (1+ start) (1- end) end t))
            (forward-char 1)))
         (t (jo--scan-string start "`")))))))

(defun jo-match-regex-literal (limit)
  "Match a regex literal outside other strings and comments before LIMIT."
  (catch 'found
    (while (re-search-forward jo-regex-literal-regexp limit t)
      (unless (save-match-data
                (let ((state (save-excursion (syntax-ppss (match-beginning 0)))))
                  (or (nth 3 state) (nth 4 state))))
        (throw 'found t)))))

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

   ;; Regex literals: `pattern` and `(?flags)pattern`
   '(jo-match-regex-literal
     (0 font-lock-string-face t)
     (1 font-lock-builtin-face t t))

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

# Syntax Summary

This section provides the complete formal grammar specification for the Jo programming language.

## Lexical Grammar

```
letter = "A" | "B" | ... | "Z" | "a" | "b" | ... | "z"
digit  = "0" | "1" | "2" | "3" | ... | "7" | "8" | "9"
opchar = "+" | "-" | "*" | "/" | "%" | "|" | "&" | "^" |
         ">" | "<" | "=" | ":" | "!" | "?" | "@" | "~"

name     = (letter | "_") {letter | digit | "_"}
operator = opchar {opchar}
ident    = name | operator

integer  = ["-"] (decimal | hexadecimal)
decimal  = digit {digit | "_" digit}
hexadecimal = "0" ("x" | "X") hex_digit {hex_digit | "_" hex_digit}

float    = ["-"] (decimal "." digit {digit | "_" digit} [exponent]  |
                  decimal exponent)
exponent = ("e" | "E") ["+" | "-"] digit {digit | "_" digit}

boolean  = "true" | "false"
char     = single character in single quotes

string_part = any character and escape, except end quote and interpolation

regex_flags = "i" | "m" | "s"
regex_name_start = letter | "_"
regex_name_tail = letter | digit | "_"
regex_name = regex_name_start {regex_name_tail}

regex_literal = "`" [ "(" "?" regex_flags {regex_flags} ")" ] {regex_part} "`"
regex_part = any character and escape, except closing backtick (use "\`" for literal backtick)

escape_sequence = "\\" ("n" | "r" | "t" | "b" | "f" | "\\" | "\"" | "'" |
                  "u" "{" hex_digit {hex_digit} "}")

hex_digit = "0" | "1" | "2" | "3" | "4" | "5" | "6" | "7" | "8" | "9" |
            "a" | "b" | "c" | "d" | "e" | "f" |
            "A" | "B" | "C" | "D" | "E" | "F"

comment = line_comment | block_comment
line_comment = "//" {any character} newline
block_comment = "/" "/" {"/"} "[" {any character} "/" "/" {"/"} "]"
```

**Note:** In block_comment, the closing delimiter must have the exact same number of slashes as the opening delimiter (minimum 2).

**Example:** `//[ ... //]`, `///[ ... ///]`, `////[ ... ////]`

### Number Literals

Underscores (`_`) can be used in number literals to improve readability. They are allowed between digits but have the following restrictions:

- Cannot appear at the beginning or end of the number (after prefix for hex)
- Cannot appear consecutively (`__`)
- For decimal/float literals only:
    - Cannot appear immediately before or after the decimal point (`.`)
    - Cannot appear immediately before or after the exponent marker (`e`, `E`)
    - Cannot appear immediately after the exponent sign (`+`, `-`)

**Valid examples:**

- `1_000_000` (one million)
- `0xFF_FF_FF` (hex with underscores)
- `3.14_159_265` (pi with underscores)
- `6.022_140_76e23` (Avogadro's number)

**Invalid examples:**

- `_123` (leading underscore)
- `123_` (trailing underscore)
- `1__000` (consecutive underscores)
- `123_.45` (before decimal point)
- `123._45` (after decimal point)
- `123_e5` (before exponent)
- `123e_5` (after exponent)

### String Literals

**Single-line strings** use double quotes (`"..."`) and must not span multiple lines. They support:

- Escape sequences: `\n`, `\r`, `\t`, `\b`, `\f`, `\\`, `\"`, `\'`
- Unicode escapes: `\u{XXXX}` where X is a hex digit
- String interpolation: `\{expr}` where expr is any expression

**Multi-line strings** use triple quotes (`"""..."""`) and:

- Must start with a newline after the opening quotes
- Support string interpolation: `\{expr}`
- Only support Unicode escapes (`\u{...}`), not other escape sequences
- Automatically strip base indentation from all lines
- The closing quotes determine the base indentation level
- Interpolation expressions must be single-line

**String interpolation requirements:**

- For non-String types, an adapter must be available (default adapters: `boolToStr`, `.toString`)
- Interpolation expressions cannot span multiple lines
- Use `\\{` to escape and include literal `\{` in the string

### Regex Literals

Regex literals use backtick syntax:

- `` `pattern` ``
- `` `(?flags)pattern` ``

where `flags` is one or more of `i`, `m`, `s` (no duplicates), and `pattern`
is parsed as raw regex source (not a normal interpolated string).

Notes:

- A literal backtick must be written as `` \` ``.
- String interpolation (`\{...}`) is not supported in regex literals.
- Regex literals must fit on a single line.
- Named group syntax is `(?<name>...)` where `name` must match
  `[A-Za-z_][A-Za-z0-9_]*`.

## Keywords

The following words are reserved and cannot be used as identifiers:

```
allow       annotation  auto        as          case
break       class       continue    def         defer
do          else
end         extension   false       for
if          import      in          interface   is
like        match       namespace   new         object
param       pattern     private     receives    return
section     then        true        type        union
val         var         view        while       with
```

Additionally,

- `:`, `@` and `=` cannot be used as operator names alone, but can be part of longer operators such as `::`, `:=` and `@@`.
- A single `.` is reserved for member access, but two or more consecutive dots (e.g., `..`, `...`) are treated as operators.

## Abstract Syntax

**Indentation meta-notation (extension to BNF):**

- `⟨LIMIT⟩` means establish an indentation limit for nested elements.
- `⟨DEDENT⟩` means the parser must reach a dedent boundary relative to the current `⟨LIMIT⟩`.
- `NL` abbreviates newlines.
- `SP` abbreviates white spaces.
- `NS` abbreviates that one token immediately follows the other with no spaces between them.

```ebnf
(*============================ top-level structure ===========================*)

namespace = ["namespace" qualid] {import} {toplevel_def} EOF

string = single_line_string | multi_line_string
regex  = regex_literal

single_line_string = "\"" {string_part | interpolation} "\""
multi_line_string  = "\"\"\"" NL {string_part | interpolation} NL "\"\"\""

interpolation = "\\{" expr "}"

qualifier = {annot} {modifier}

toplevel_def = qualifier (
    type_def  | fun_def    | param_def     | pat_def       |
    class_def | object_def | interface_def | extension_def |
    section   | annot_def  | union_def
)

section   = "section" name {toplevel_def} ["end"]

annot_def    = "annotation" name ["(" annot_param {"," annot_param} ")"]
annot_param  = name ":" type

annot        = "@" qualid ["(" annot_arg {"," annot_arg} ")"]
annot_arg    = integer | boolean | string

qualid = ident | qualid NS "." NS ident

import = "import" qualid ["as" ident]


(*================================== terms ===================================*)

(* invariant: no space between an atom and any immediate suffix *)
atom = integer
     | boolean
     | char
     | float
     | string
     | regex
     | "this"
     | ident
     | "(" expr ")"                                   -- fence
     | "new" qualid [targs] [args]                    -- new_expr
     | "[" [expr {"," expr}] "]"                      -- list_literal
     | atom NS "." NS ident                           -- select
     | atom NS "(" [call_arg {"," call_arg}] ")"      -- apply
     | atom NS "[" expr {"," expr} "]"                -- bracket_apply

(* invariant: no need for external delimiters *)
word = atom
     | atom "is" simple_pattern                    -- is_expr
     | SP operator NS atom                         -- prefix_apply

(* delimited words, used between keywords, call arguments, bindings *)
(* invariant: no comma, no keyword, no "=", no colon *)
words = word {word}

(* delimited/closed expressions, used for call arguments and inline bindings *)
(* invariant: no comma, no "=", no colon *)
expr = words
     | (lambda_param_section | name) "=>" block             -- lambda
     | "if" words "then" block "else" block ["end"]

(* open expressions, used for indented colon call arguments, phrases and indented bindings *)
(* invariant: words end by new line *)
open_expr  = words NL
              | (lambda_param_section | name) "=>" block    -- lambda
              | colon_call
              | dot_chain
              | "if" words "then" block ["else" block] ["end"]
              | "match" words {"case" pattern "=>" block} ["end"]
              | "allow" qualid {"," qualid} "in" block
              | "with" qualid "=" expr {"," qualid "=" expr} "in" block
              | "with" NL qualid "=" open_expr {NL qualid "=" open_expr} "in" block
              | atom "rescue" simple_pattern "=>" block         -- rescue_expr

(* invariant: words end by new line *)
phrase = open_expr
       | (name | select | bracket_apply) "=" block          -- assign
       | "return" [block]
       | "break"
       | "continue"
       | "while" words "do" block ["end"]
       | "for" expr_pattern "in" words ["if" words] "do" block ["end"]
       | ("val" | "var") name [":" type] "=" block
       | "val" expr_pattern "=" block                        -- pat_val_def
       | "auto" name ":" type "=" block                      -- auto_def
       | fun_def
       | pat_def

(* invariant: vertically aligned *)
block = ⟨LIMIT⟩ phrase {phrase} ⟨DEDENT⟩

args = "(" [call_arg {"," call_arg}] ")"
call_arg = [name "="] expr

(* invariant: (1) all commas on same line for inline syntax; (2) vertial align for indented syntax *)
colon_call = atom NS ":" colon_args
colon_args = inline_colon_args | indented_colon_args

inline_colon_args = call_arg {"," call_arg}
indented_colon_args = NL ⟨LIMIT⟩ indented_call_arg {NL indented_call_arg} ⟨DEDENT⟩
indented_call_arg = [name "="] open_expr

bracket_args = "[" expr {"," expr} "]"

(* invariant: vertical alignment of dots  *)
dot_chain = atom NL "." NS ident {NS dot_chain_suffix} [":" colon_args]
          | dot_chain NL "." NS ident {NS dot_chain_suffix} [":" colon_args]

dot_chain_suffix = "." NS ident
                 | "(" [call_arg {"," call_arg}] ")"      -- apply
                 | "[" expr {"," expr} "]"                -- bracket_apply

(*================================== patterns ================================*)

pattern = expr_pattern [guard_pattern] [assign_pattern]

guard_pattern = "if" words
assign_pattern = "then" assignment {"," assignment}
assignment = name "=" words

expr_pattern = simple_pattern {simple_pattern}

atom_pattern = integer
               | boolean
               | char
               | string
               | regex_literal
               | qualid
               | qualid NS "(" [pattern {"," pattern}] ")"      -- apply_pattern
               | "[" [sequence_item {"," sequence_item}] "]"    -- sequence_pattern
               | "(" pattern ")"

simple_pattern = atom_pattern
               | name ":" type                                  -- type_pattern
               | name "@" simple_pattern                        -- bind_pattern
               | SP operator NS atom_pattern                    -- apply_pattern


sequence_item = pattern
              | ".." [name] ["while" pattern]                 -- repeat_pattern

(*================================ types =====================================*)

type = simple_type {"|" simple_type}                        -- union_type
     | simple_type ":-" "[" adapter_list "]"                -- duck_type
     | simple_type ":+" "[" method_ref_list "]"             -- extension_type
     | simple_type {simple_type}                            -- expr_type
     | param_types "=>" type [receive_params]               -- lambda_type

simple_type = atom_type
            | SP operator NS atom_type                      -- prefix_applied_type
            | simple_type annot                             -- annotation_type

atom_type = qualid
          | qualid "[" type {"," type} "]"                  -- applied_type
          | "(" type ")"

adapter_list = adapter {"," adapter}
adapter = qualid | member_adapter
member_adapter = "." ident

method_ref_list = method_ref {"," method_ref}
method_ref = qualid ["!"]                                   -- "!" marks intentional shadowing

param_types = simple_type | "()" | "(" type {"," type} ")"
receive_params = "receives" qualid {"," qualid}

(*================================ definitions ===============================*)

modifier = "defer" | private_modifier

private_modifier = "private" ["[" name "]"]

fun_def = "def" [pre_param_section] ident [tparams] [post_param_section]
          [auto_section] [":" type] [receive_params] ["=" block] ["end"]

class_def = "class" name [tparams] [param_section] {class_member} ["end"]
class_member = qualifier class_member_body | view_decl
class_member_body = def_def | val_decl

object_def = "object" name {object_member} ["end"]
object_member = qualifier def_def | view_decl

def_def = "def" ident [tparams] [post_param_section] [":" type] [receive_params] "=" block ["end"]

pat_def = "pattern" ident [tparams] [param_section] [":" type] "=" cases ["end"]

cases = "case" pattern {"case" pattern}

interface_def = "interface" name [tparams] {qualifier method_decl} ["end"]
method_decl = "def" ident [tparams] [post_param_section] [":" type] [receive_params]
              ["=" block] ["end"]

view_decl = "view" type ["=" block]
val_decl = ("val" | "var") name ":" type ["=" block]

union_def = "union" name [tparams] "=" branch {"|" branch} {qualifier def_def} ["end"]
branch = name [param_section]

extension_def = "extension" name [tparams] "for" type {qualifier def_def} ["end"]

param_def = "param" param ["=" block]

type_def = "type" [tparams] ident [tparams] ["=" type]

tparams = "[" tparam {"," tparam} "]"
tparam = name

pre_param_section  = "(" [simple_params] ")"
post_param_section = "(" [post_params] ")"
param_section      = "(" [simple_params] ")"

simple_params = simple_param {"," simple_param}
simple_param  = name ":" type

lambda_param_section = "(" [lambda_params] ")"
lambda_params = lambda_param {"," lambda_param}
lambda_param  = name [":" type]

post_params = post_param {"," post_param}
post_param  = name ":" type ["=" default_value]

default_value = integer | boolean | char | float | string | qualid

auto_section = "(" "auto" auto_params ")"
auto_params = auto_param {"," auto_param}
auto_param = name ":" type ["with" "[" candidate_list "]"]
candidate_list = candidate {"," candidate}
candidate = qualid | member_candidate
member_candidate = "[" type "]" "." ident

```

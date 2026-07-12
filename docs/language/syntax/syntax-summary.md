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

Underscores (`_`) may appear between digits for readability (`1_000_000`, `0xFF_FF`).
They cannot appear at the start or end, consecutively, or adjacent to `.` or the
exponent marker.

### String Literals

See [String Literals](../expressions/string.md).

### Regex Literals

See [Regular Expressions](../expressions/regular-expressions.md).

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
     | "[" [simple_expr {"," simple_expr}] "]"        -- list_literal
     | atom NS "." NS ident                           -- select
     | atom NS "(" [call_arg {"," call_arg}] ")"      -- apply
     | atom NS "[" simple_expr {"," simple_expr} "]"  -- bracket_apply

(* invariant: no need for external delimiters *)
word = atom
     | atom "is" simple_pattern                    -- is_expr
     | atom "as" simple_type                       -- type_ascribe
     | SP operator NS atom                         -- prefix_apply

(* delimited words, used between keywords, call arguments, bindings *)
(* invariant: no comma, no keyword, no "=", no colon *)
words = word {word}

(* simple_expr: every expression form except the inline colon call.          *)
(* admissible in any position, including a comma-separated list.              *)
simple_expr = words
            | (lambda_param_section | name) "=>" block            -- lambda
            | "if" expr "then" block ["else" block] ["end"]
            | "match" expr {"case" pattern "=>" block} ["end"]
            | indented_colon_call
            | dot_chain
            | "allow" qualid {"," qualid} "in" block
            | "with" qualid "=" simple_expr {"," qualid "=" simple_expr} "in" block
            | atom "rescue" simple_pattern "=>" block             -- rescue_expr

(* expr: simple_expr plus the inline colon call.                              *)
(* admissible everywhere except directly inside a comma-separated list.       *)
expr = simple_expr | inline_colon_call

(* phrase: an expr, or a phrase-only statement. a block is a run of phrases   *)
(* only a definition "=", "=>", and the keywords then/else/do/in open a block.  *)
(* an assignment and a return take a single expr, never a multi-phrase block.    *)
phrase = expr
       | (name | select | bracket_apply) "=" expr           -- assign
       | "return" [expr]
       | "break"
       | "continue"
       | "while" expr "do" block ["end"]
       | "for" expr_pattern "in" expr ["if" expr] "do" block ["end"]
       | ("val" | "var") name [":" type] "=" block
       | "val" expr_pattern "=" block                        -- pat_val_def
       | "auto" name ":" type "=" block                      -- auto_def
       | fun_def
       | pat_def

(* invariant: vertically aligned *)
block = ⟨LIMIT⟩ phrase {phrase} ⟨DEDENT⟩

args = "(" [call_arg {"," call_arg}] ")"
(* invariant: a comma position takes simple_expr, never the inline colon call *)
call_arg = [name "="] simple_expr

(* colon call. inline keeps all commas on the ":" line. indented separates    *)
(* its arguments by newline, vertically aligned and bounded by a dedent.       *)
inline_colon_call   = atom NS ":" inline_colon_args
indented_colon_call = atom NS ":" indented_colon_args

inline_colon_args   = call_arg {"," call_arg}
indented_colon_args = NL ⟨LIMIT⟩ indented_call_arg {NL indented_call_arg} ⟨DEDENT⟩
indented_call_arg   = [name "="] expr
colon_args          = inline_colon_args | indented_colon_args

bracket_args = "[" simple_expr {"," simple_expr} "]"

(* invariant: each continuation dot begins its own line, vertically aligned.   *)
(* invariant: a segment's colon may be inline only where the chain is not a     *)
(*            comma-list element; in a comma position every colon must be        *)
(*            indented (an inline colon's commas would collide with the list's). *)
dot_chain = atom NL "." NS ident {NS dot_chain_suffix} [":" colon_args]
          | dot_chain NL "." NS ident {NS dot_chain_suffix} [":" colon_args]

dot_chain_suffix = "." NS ident
                 | "(" [call_arg {"," call_arg}] ")"      -- apply
                 | "[" simple_expr {"," simple_expr} "]"  -- bracket_apply

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

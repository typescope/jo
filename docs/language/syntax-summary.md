# Syntax Summary

This section provides the complete formal grammar specification for the Jo programming language.

## Lexical Grammar

```
letter = "A" | "B" | ... | "Z" | "a" | "b" | ... | "z"
digit  = "0" | "1" | "2" | "3" | ... | "7" | "8" | "9"
opchar = "+" | "-" | "*" | "/" | "%" | "|" | "&" | "^" |
         ">" | "<" | "=" | "!" | "?"

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

regex_literal = "#r" [ "[" regex_flags {regex_flags} "]" ] "\"" {regex_part} "\""
regex_part = any character and escape, except end quote

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

Regex literals use tagged-literal syntax:

- `#r"pattern"`
- `#r[flags]"pattern"`

where `flags` is one or more of `i`, `m`, `s` (no duplicates), and `pattern`
is parsed as raw regex source (not a normal interpolated string).

Notes:

- A bare `"` is not allowed inside the payload; write `\"`.
- String interpolation (`\{...}`) is not supported in regex literals.
- Named group syntax is `(?<name>...)` where `name` must match
  `[A-Za-z_][A-Za-z0-9_]*`.

## Keywords

The following words are reserved and cannot be used as identifiers:

```
allow       auto        as          begin       case
class       def         defer       do          else
end         extend      extension   false       for
if          import      in          interface   is
like        match       namespace   new         object
param       pattern     private     receives    return
section     then        true        type        union
val         var         view        while       with
```

Additionally,

- `:` and `=` cannot be used as operator names alone, but can be part of longer operators such as `::` and `:=`.
- A single `.` is reserved for member access, but two or more consecutive dots (e.g., `..`, `...`) are treated as operators.

## Abstract Syntax

**Indentation meta-notation (extension to BNF):**

- `⟨LIMIT⟩` means establish an indentation limit for nested elements.
- `⟨DEDENT⟩` means the parser must reach a dedent boundary relative to the current `⟨LIMIT⟩`.

```
namespace = ["namespace" qualid] {import} {toplevel_def} EOF

string = single_line_string | multi_line_string
regex  = regex_literal

single_line_string = "\"" {string_part | interpolation} "\""
multi_line_string  = "\"\"\"" newline {string_part | interpolation} indent "\"\"\""

interpolation = "\\" "{" expr "}"

section = {modifier} "section" ident {toplevel_def} ["end"]

toplevel_def = type_def | fun_def | param_def | pat_def | union_def |
               class_def | object_def | interface_def | extension_def | section

qualid = ident | qualid "." ident

import = "import" qualid ["as" ident]

expr = delimited_expr | if_expr | lambda

if_expr = "if" simple_expr "then" expr "else" expr
simple_expr = word {word}

word = integer | boolean | char | float | string | regex | ident | fence |
       apply | select | collection | new_expr |
       begin_block | type_apply | bracket_apply | is_expr

phrase = indented_expr | lambda | assign | val_def | pat_val_def | fun_def | pat_def | type_def |
         while | for | if | match | allow_clause | return

return = "return" [expr]

block = ⟨LIMIT⟩ {phrase} ⟨DEDENT⟩

begin_block = "begin" block "end"

select = word "." ident

is_expr = word "is" ["prefix_op"] simple_pattern

apply = word args
args = "(" [call_arg {"," call_arg}] ")"
call_arg = expr | named_call_arg
named_call_arg = ident "=" expr

bracket_apply = word "[" expr {"," expr} "]"
type_apply = word targs

new_expr = "new" qualid [targs] [args]

delimited_expr = simple_expr [modifier_clause]
indented_expr = ⟨LIMIT⟩ word {word} [modifier_clause] ⟨DEDENT⟩

modifier_clause = with_clause | as_clause | do_clause

with_clause = "with" with_bindings
with_bindings = with_binding {"," with_binding}
with_binding = qualid "=" block

as_clause = "as" simple_type
do_clause = "do" lambda ["end"]

allow_clause = "allow" qualid {"," qualid} "in" block

fence = "(" expr ")"
assign = (ident | select | bracket_apply) "=" block
if = "if" simple_expr "then" block ["else" block] ["end"]
while = "while" simple_expr "do" block ["end"]
for = "for" expr_pattern "in" simple_expr ["if" simple_expr] "do" block ["end"]

collection = "{" [collection_elem {"," collection_elem}] "}" |
             "[" [list_elem {"," list_elem}] "]"

collection_elem = map_pair | expr
map_pair = expr ":" expr

list_elem = splice_elem | expr
splice_elem = ".." expr

lambda = (param_section | ident) "=>" block

match = "match" expr {case} ["end"]
case = "case" pattern "=>" block

pat_val_def = "val" expr_pattern "=" block

pattern = expr_pattern [guard_pattern] [assign_pattern]

guard_pattern = "if" expr
assign_pattern = "then" assignment {"," assignment}
assignment = ident "=" block

expr_pattern = simple_pattern {simple_pattern}

simple_pattern = literal_pattern | qualid | type_pattern |
                 bind_pattern | apply_pattern | regex_pattern |
                 "(" pattern ")" |
                 sequence_pattern

literal_pattern = integer | boolean | char | string
regex_pattern = regex_literal | ident regex_literal
type_pattern = ident ":" type
bind_pattern = ident "@" simple_pattern
apply_pattern = qualid "(" [pattern {"," pattern}] ")"

sequence_pattern = "[" [sequence_items] "]"

sequence_items = sequence_item {"," sequence_item}

sequence_item = atom_pattern
              | repeat_pattern

atom_pattern = pattern

repeat_pattern = ".." [ident] ["while" pattern]

modifier = "defer" | private_modifier

private_modifier = "private" ["[" ident "]"]

val_def = {modifier} ("val" | "var") ident [":" type] "=" block

fun_def = {modifier} "def" [pre_param_section] ident [tparams] [post_param_section]
          [auto_section] [":" type] [receive_params] ["=" block] ["end"]

class_def = {modifier} "class" ident [tparams] [param_section] {class_member} ["end"]
class_member = view_decl | extension_ref | def_def | val_decl
extension_ref = "extension" qualid

object_def = {modifier} "object" ident {object_member} ["end"]
object_member = view_decl | extension_ref | def_def

def_def = "def" ident [tparams] [post_param_section] [":" type] [receive_params]
          "=" block ["end"]

pat_def = {modifier} "pattern" ident [tparams] [param_section] [":" type] "=" cases ["end"]
cases = case {"case" pattern}

interface_def = {modifier} "interface" ident [tparams] {method_decl} ["end"]

view_decl = "view" type ["=" block]
val_decl = ("val" | "var") ident ":" type

union_def = "union" ident [tparams] "=" branch {"|" branch}
branch = ident [param_section]

extension_def = {modifier} "extension" ident [tparams] "(" ident ":" type ")" {def_def} ["end"]

param_def = {modifier} "param" param ["=" block]

type_def = {modifier} "type" [tparams] ident [tparams] ["=" type | "<:" type]
tparams = "[" tparam {"," tparam} "]"
tparam = ident

applied_type = ident targs
targs = "[" type {"," type} "]"

type = union_type | expr_type | fun_type

union_type = simple_type {"|" simple_type}

expr_type = simple_type {simple_type}

extension_type = "extend" type "with" qualid

simple_type = qualid | applied_type | fun_type | duck_type | extension_type | "(" type ")"

duck_type = "like" type "with" "[" adapter_list "]"
adapter_list = adapter {"," adapter}
adapter = qualid | member_adapter
member_adapter = "." ident

fun_type = param_types "=>" type [receive_params]
param_types = simple_type | "()" | "(" type {"," type} ")"

receive_params = "receives" qualid {"," qualid}

pre_param_section  = "(" [simple_params] ")"
post_param_section = "(" [post_params] ")"
param_section      = "(" [simple_params] ")"

simple_params = simple_param {"," simple_param}
simple_param  = ident ":" type

post_params = post_param {"," post_param}
post_param  = ident ":" type ["=" default_value]

default_value = integer | boolean | char | float | string | qualid

auto_section = "(" "auto" auto_params ")"
auto_params = auto_param {"," auto_param}
auto_param = ident ":" type ["with" "[" candidate_list "]"]
candidate_list = candidate {"," candidate}
candidate = qualid | member_candidate
member_candidate = "[" type "]" "." ident
```

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
integer  = ["-"] digit {digit}
boolean  = "true" | "false"
char     = single character in single quotes

string_part = any character and escape, except end quote and interpolation

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

- For non-String types, an adapter must be available (default adapters: `boolToStr`, `byteToStr`, `charToStr`, `intToStr`, `.toString`)
- Interpolation expressions cannot span multiple lines
- Use `\\{` to escape and include literal `\{` in the string

## Abstract Syntax

```
namespace = ["namespace" qualid] {import} {toplevel_def} EOF

string = single_line_string | multi_line_string

single_line_string = "\"" {string_part | interpolation} "\""
multi_line_string  = "\"\"\"" newline {string_part | interpolation} indent "\"\"\""

interpolation = "\\" "{" expr "}"

section = {modifier} "section" ident {toplevel_def} ["end"]

toplevel_def = type_def | fun_def | param_def | alias_def | pat_def | union_def |
               class_def | interface_def | section

qualid = ident | qualid "." ident

import = "import" qualid

expr = word {word}

word = integer | boolean | char | string | ident | fence | record |
       apply | select | lambda | object | list | new_expr |
       begin_block | type_apply | bracket_apply | view_access | is_expr

phrase = simple_phrase | assign | val_def | fun_def | pat_def | type_def |
         while | for | if | match | case_def

block = {phrase}

begin_block = "begin" block "end"

select = word "." ident

view_access = word "." "view" "[" type "]"

is_expr = word "is" simple_pattern

apply = word args [having_bindings]
args = "(" [expr {"," expr}] ")"

having_bindings = "having" having_binding {"," having_binding}
having_binding = type "=" expr

bracket_apply = word "[" expr {"," expr} "]"
type_apply = word targs

new_expr = "new" qualid [targs] [args]

simple_phrase = expr | type_ascribe | with_clause | allow_clause

with_clause = simple_phrase "with" with_bindings
with_bindings = with_binding {"," with_binding}
with_binding = qualid "=" expr

allow_clause = simple_phrase "allow" qualid {"," qualid}

fence = "(" phrase ")"
assign = (ident | select | bracket_apply) "=" block
if = "if" expr "then" block ["else" block] ["end"]
while = "while" expr "do" block ["end"]
for = "for" expr_pattern "in" expr ["if" expr] "do" block ["end"]

list = "[" [expr {"," expr}] "]"

record = "{" [named_args] "}"
named_args = named_arg {"," named_arg}
named_arg = ident ":" expr

type_ascribe = simple_phrase "as" simple_type

object = "{" [members] "}"
members = member {[","] member}
member = val_def | def_def

lambda = (param_section | ident) "=>" block

match = "match" expr {case} ["end"]
case = "case" pattern "=>" block

case_def = "case" expr_pattern "=" block

pattern = expr_pattern [guard_pattern] [assign_pattern]

guard_pattern = "if" expr
assign_pattern = "then" assignment {"," assignment}
assignment = ident "=" expr

expr_pattern = simple_pattern {simple_pattern}

simple_pattern = literal_pattern | qualid | type_pattern |
                 bind_pattern | apply_pattern | "(" pattern ")" |
                 sequence_pattern

literal_pattern = integer | boolean | char | string
type_pattern = ident ":" type
bind_pattern = ident "@" simple_pattern
apply_pattern = qualid "(" [pattern {"," pattern}] ")"

sequence_pattern = "[" [expr_pattern {"," expr_pattern}] "]"

modifier = "defer" | private_modifier

private_modifier = "private" ["[" ident "]"]

val_def = {modifier} ("val" | "var") ident [":" type] "=" block

fun_def = {modifier} "def" [param_section] ident [tparams] [param_section]
          [auto_section] [":" type] [receive_params] ["=" block] ["end"]

class_def = {modifier} "class" ident [tparams] [param_section] {class_member} ["end"]
class_member = view_decl | def_def | val_decl

def_def = "def" ident [tparams] [param_section] [":" type] [receive_params]
          "=" block ["end"]

pat_def = {modifier} "pattern" ident [tparams] [param_section] [":" type] "=" cases ["end"]
cases = case {"case" pattern}

interface_def = {modifier} "interface" ident [tparams] {method_decl} ["end"]

view_decl = "view" type ["=" expr]

union_def = "union" ident [tparams] "=" branch {"|" branch}
branch = ident [param_section]

param_def = {modifier} "param" param ["=" block]

alias_def = {modifier} "alias" ("def" | "pattern" | "param") ident "=" qualid

type_def = {modifier} "type" [tparams] ident [tparams] ["=" type | "<:" type]
tparams = "[" tparam {"," tparam} "]"
tparam = ident

applied_type = ident targs
targs = "[" type {"," type} "]"

type = union_type | expr_type | fun_type

union_type = simple_type {"|" simple_type}

expr_type = simple_type {simple_type}

simple_type = qualid | record_type | applied_type | fun_type |
              object_type | duck_type | view_type | "(" type ")"

duck_type = "like" type "with" "[" adapter_list "]"
adapter_list = adapter {"," adapter}
adapter = qualid | member_adapter
member_adapter = "." ident

view_type = "view" type "as" view_spec_list
view_spec_list = view_spec {"," view_spec}
view_spec = type ["with" qualid]

record_type = "{" [fields] "}"
fields = field {[","] field}
field = ident ":" type

fun_type = param_types "=>" type [receive_params]
param_types = simple_type | "()" | "(" type {"," type} ")"

receive_params = "receives" qualid {"," qualid}

object_type = "{" [member_decls] "}"
member_decls = member_decl {[","] member_decl}
member_decl = method_decl | val_decl
method_decl = "def" ident [tparams] [param_section] [":" type] [receive_params] ["=" block]
val_decl = ("val" | "var") ident ":" type

param_section = "(" [params] ")"
params = param {"," param}
param = ident ":" type

auto_section = "(" "auto" auto_params ")"
auto_params = auto_param {"," auto_param}
auto_param = ident ":" type ["with" "[" candidate_list "]"]
candidate_list = candidate {"," candidate}
candidate = qualid | member_candidate
member_candidate = "[" type "]" "." ident
```

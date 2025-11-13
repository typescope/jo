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

- For non-String types, an auto `Show[T]` instance must be available
- Interpolation expressions cannot span multiple lines
- Use `\\{` to escape and include literal `\{` in the string

## Abstract Syntax

```
namespace = ["namespace" qualid] {import} {toplevel_def} EOF

string = single_line_string | multi_line_string

single_line_string = "\"" {string_part | interpolation} "\""
multi_line_string  = "\"\"\"" newline {string_part | interpolation} indent "\"\"\""

interpolation = "\\" "{" expr "}"

section = "section" ident {toplevel_def} ["end"]

toplevel_def = typedef | fundef | paramdef | aliasdef | patdef | datadef | 
               classdef | section

qualid = ident | qualid "." ident

import = "import" qualid

expr = word {word}

word = integer | boolean | char | string | ident | fence | record |
       apply | select | tag | lambda | object | list | new_expr |
       begin_block | type_apply | bracket_apply

phrase = simple_phrase | assign | valdef | fundef | patdef | typedef | 
         while | if | match

block = {phrase}

begin_block = "begin" block ["end"]

select = word "." ident

apply = word args
args = "(" [expr {"," expr}] ")"

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

list = "[" [expr {"," expr}] "]"

record = "{" [named_args] "}"
named_args = named_arg {"," named_arg}
named_arg = ident ":" expr

tag = "#" ident

type_ascribe = simple_phrase "as" simple_type

object = "{" [members] "}"
members = member {[","] member}
member = valdef | defdef

lambda = (param_section | ident) "=>" block

match = "match" expr {case} ["end"]
case = "case" pattern "=>" block

pattern = expr_pattern ["if" expr] ["then" pattern_bindings]

pattern_bindings = pattern_binding {"," pattern_binding}
pattern_binding = ident "=" expr

expr_pattern = simple_pattern {simple_pattern}

simple_pattern = literal_pattern | qualid | tag | type_pattern | 
                 ascribe_pattern | apply_pattern | "(" pattern ")" | 
                 sequence_pattern

literal_pattern = integer | boolean | char | string
type_pattern = ident ":" type
ascribe_pattern = ident "@" simple_pattern
apply_pattern = (tag | qualid) "(" [pattern {"," pattern}] ")"

sequence_pattern = "[" [expr_pattern {"," expr_pattern}] "]"

modifier = "auto" | "defer"

valdef = {modifier} ("val" | "var") ident [":" type] "=" block

fundef = {modifier} "def" [param_section] ident [tparams] [param_section] 
         [auto_section] [":" type] [receive_params] ["=" block] ["end"]

classdef = {modifier} "class" ident [tparams] {defdef | val_decl} ["end"]

defdef = "def" ident [tparams] [param_section] [":" type] [receive_params] 
         "=" block ["end"]

patdef = "pattern" ident [tparams] [param_section] [":" type] "=" cases ["end"]
cases = case {"case" pattern}

datadef = "data" ident [tparams] ([param_section] | "=" branch {"|" branch})
branch = ident [param_section]

paramdef = "param" param ["=" block]

aliasdef = {modifier} "alias" ("def" | "pattern" | "param") ident "=" qualid

typedef = "type" [tparams] ident [tparams] ["=" type | "<:" type]
tparams = "[" tparam {"," tparam} "]"
tparam = ident

applied_type = ident targs
targs = "[" type {"," type} "]"

type = union_type | expr_type | fun_type

union_type = simple_type {"|" simple_type}

expr_type = simple_type {simple_type}

simple_type = qualid | record_type | tag_type | applied_type | fun_type | 
              object_type | "(" type ")"

record_type = "{" [fields] "}"
fields = field {[","] field}
field = ident ":" type

tag_type = "#" ident ["(" tag_param {"," tag_param} ")"]
tag_param = [ident ":"] type

fun_type = param_types "=>" type [receive_params]
param_types = simple_type | "()" | "(" type {"," type} ")"

receive_params = "receives" qualid {"," qualid}

object_type = "{" [member_decls] "}"
member_decls = member_decl {[","] member_decl}
member_decl = method_decl | val_decl
method_decl = "def" ident param_section ":" type [receive_params]
val_decl = ("val" | "var") ident ":" type

param_section = "(" [params] ")"
params = param {"," param}
param = ident ":" type ["with" "[" adapter_list "]"]
adapter_list = adapter {"," adapter}
adapter = qualid | member_adapter
member_adapter = "." ident

auto_section = "(" "auto" params ")"
```
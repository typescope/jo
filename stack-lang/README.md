# Stk

Implementation of the language Stk.


## Abstract Syntax

Lexical Grammar

~~~
    letter = "A" | "B" | ... | "Z" | "a" | "b" | ... | "z".
    digit  = "0" | "1" | "2" | "3" | ... | "7" | "8" | "9".
    opchar = "+" | "-" | "*" | "/" | "%" | "|" | "&" | "^" |
             ">" | "<" | "=" | "!" | "?" .
    NLINE  = "\n".
    USCORE = "_".

    COLON    = ":".
    VAL      = "val".
    VAR      = "var".
    FUN      = "fun".
    TYPE     = "type".
    EQL      = "=".
    RARROW   = "=>".
    TAG      = "#".
    SUBTYPE  = "<:".
    COMMA    = ",".
    DOT      = ".".
    LPAREN   = "(".
    RPAREN   = ")".
    LBRACKET = "[".
    RBRACKET = "]".
    LBRACE   = "{".
    RBRACE   = "}".
    AS       = "as".
    IF       = "if".
    THEN     = "then".
    ELSE     = "else".
    WHILE    = "while".
    DO       = "do".
    MATCH    = "match".
    CASE     = "case".
    END      = "end".
    WITH     = "with".
    ALLOW    = "allow".
    PARAM    = "param".
    NSPACE   = "namespace".
    IMPORT   = "import".
    DEF      = "def".
    OBJECT   = "object".
    OBJECT   = "begin".
    RECEIVES = "receives".
    PATTERN  = "pattern".
    AUTO     = "auto".
    name     = (letter | USCORE) {letter | digit | USCORE}.
    operator = opchar { opchar }.
    ident    = name | operator.
    integer  = ["-"] digit {digit}.
    boolean  = "true" | "false".
    char     = '_'
    string   = "..."

    comment = "//" {any character} NLINE.
~~~

Abstract Syntax

~~~
    namespace = [NSPACE qualid] {import} {toplevel_def} EOF.

    section = SECTION ident {toplevel_def} [END].

    toplevel_def = typedef | fundef | paramdef | aliasdef | patdef | datadef | classdef | section.

    qualid = ident | qualid DOT ident.

    import = IMPORT qualid.

    expr    = word {word}.

    word    = integer  | boolean | char  | string | ident  | fence | record   |
              apply    | select  | tag   | lambda | object | list  | new_expr |
              begin_block |  type_apply | bracket_apply.

    phrase  = simple_phrase | assign | valdef | fundef | patdef | typedef | while | if | match.

    block   = { phrase }.

    begin_block = BEGIN block [END].

    select  = word DOT ident.

    apply  = word args.
    args   = LPAREN [expr {COMMA expr}] RPAREN.

    bracket_apply = word LBRACKET expr {COMMA, expr} RBRACKET.
    type_apply    = word targs.

    new_expr = NEW qualid [targs] [args].

    simple_phrase = expr | type_ascribe | with_clause | allow_clause.

    with_clause = simple_phrase WITH with_bindings
    with_bindings = with_binding {COMMA with_binding}.
    with_binding = qualid EQL expr.

    allow_clause = simple_phrase ALLOW qualid {COMMA qualid}.

    fence   = LPAREN phrase RPAREN.
    assign  = (ident | select | bracket_apply) EQL block.
    if      = IF expr THEN block [ELSE block] [END].
    while   = WHILE expr DO block [END].

    list    = LBRACKET [expr {COMMA expr}] RBRACKET.

    record     = LBRACE [named_args] RBRACE.
    named_args = named_arg { COMMA named_arg }.
    named_arg  = ident COLON expr.

    tag = TAG ident.

    type_ascribe = simple_phrase AS simple_type.

    object     = LBRACE [members] RBRACE.
    members    = member { [COMMA] member }
    member     = valdef | defdef.

    lambda  = (param_section | ident) RARROW block.

    match   = MATCH expr {case} [END].
    case    = CASE pattern RARROW block.

    pattern = expr_pattern [IF expr] [THEN pattern_bindings]

    pattern_bindings = pattern_binding {COMMA pattern_binding}.
    pattern_binding = ident EQL expr.

    expr_pattern = simple_pattern {simple_pattern}.

    simple_pattern = literal_pattern | qualid | tag | type_pattern | ascribe_pattern | apply_pattern | LPAREN pattern RPAREN | sequence_pattern.

    literal_pattern = integer | boolean | char | string.
    type_pattern  = ident COLON type.
    ascribe_pattern = ident '@' simple_pattern.
    apply_pattern = (tag | qualid) LPAREN [pattern {COMMA pattern}] RPAREN.

    sequence_pattern = LBRACK [expr_pattern {, expr_pattern}] RBRACK.

    modifier = AUTO.

    valdef  = {modifier} (VAL | VAR) ident [COLON type] EQL block.

    fundef  = {modifier} DEF [param_section] ident [tparams] [param_section] [auto_section] [COLON type] [receive_params] EQL block [END].

    classdef = {modifier} class ident [tparams] {defdef | val_decl} [END].

    defdef  = DEF ident [tparams] [param_section] [COLON type] [receive_params] EQL block [END].

    patdef  = PATTERN ident [tparams] [param_section] [COLON type] EQL cases [END].
    cases   = case Pattern {CASE pattern}.

    datadef = DATA ident [tparams] ([param_section] | EQL branch { "|" branch }).
    branch  = ident [param_section].

    paramdef = PARAM param [EQL block].

    aliasdef = {modifier} ALIAS (def | pattern | param) ident EQL qualid.

    typedef = TYPE [tparams] ident [tparams] [EQL type | SUBTYPE type].
    tparams = LBRACKET tparam {COMMA tparam} RBRACKET.
    tparam  = ident.

    applied_type = ident targs.
    targs        = LBRACKET type { COMMA type } RBRACKET.

    type = union_typ | expr_typ | fun_type.

    union_type  = simple_type {"|" simple_type}.

    expr_type = simple_type { simple_type }.

    simple_type = qualid | record_type | tag_type | applied_type | fun_type | object_type | LPAREN type RPAREN.

    record_type = LBRACE [fields]  RBRACE.
    fields      = field { [COMMA] field }.
    field       = ident COLON type.

    tag_type   = TAG ident [LPAREN tag_param { COMMA tag_param } RPAREN].
    tag_param  = [ident COLON] type

    fun_type    = param_types RARROW type [receive_params].
    param_types = simple_type | LPAREN RPAREN | LPAREN type {COMMA type} RPAREN.

    receive_params = RECEIVES qualid {COMMA qualid}.

    object_type  = LBRACE [member_decls] RBRACE.
    member_decls = member_decl {[COMMA] member_decl}.
    member_decl  = method_decl | val_decl.
    method_decl  = DEF ident param_section COLON type [receive_params].
    val_decl     = (VAL | VAR) ident COLON type.

    param_section = LPAREN [params] RPAREN.
    params        = param {COMMA param}.
    param         = ident COLON type.

    auto_section = LPAREN AUTO params RPAREN.
~~~

## Namespaces

Each source file defines a _namespace_. Each file has the following structure:

```
namespace io.net

import system

type List[T] = #Nil | #Cons(head: T, tail: List[T])

fun foo(...) { ... }
```

The name of the namespace should be a valid `qualid`. If the namespace is not
specified, the compiler uses the name part of the file as its default namespace.

Namespaces form a tree-like structure. We call the leaves of the tree _leaf
namespaces_, and internal nodes of the tree _branch namespaces_.

Branch namespaces only have namespaces as members. Leaf namespaces only have
functions and type definitions as members.

The naming of a namespace must follow the following rules:

- The same name cannot be used twice.
- If a `qualid` is used as a prefix of another namespace, it cannot be used as
  name of a leaf namespace; vice versa.

Import statements make other namespaces usable in the current namespace.
Namespaces must be imported before they can be used. In another word,
referencing to another namespace directly with `qualid` is disallowed.  This
rule is intended to make dependencies between namespaces explicit in source
code.

There are two exceptions:

- The name of the current namespace is always implicitly imported.
- The language defines a list of predefined names, which are always imported.

An import statement may either import a leaf namespace or one of its term
member.  It is disallowed to import a branch namespace. Namespaces can mutually
import each other, i.e., they may have cyclic dependencies.

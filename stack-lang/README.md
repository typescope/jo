# Stk

Implementation of the stack-oriented language Stk.


## Syntax

Lexical Grammar

~~~
    letter = "A" | "B" | ... | "Z" | "a" | "b" | ... | "z".
    digit  = "0" | "1" | "2" | "3" | ... | "7" | "8" | "9".
    opchar = "+" | "-" | "*" | "/" | "%" | "|" | "&" | "^" |
             ">" | "<" | "=" | "!".
    NLINE  = "\n".
    USCORE = "_".

    COLON    = ":".
    VAL      = "val".
    VAR      = "var".
    FUN      = "fun".
    TYPE     = "type".
    EQL      = "=".
    RARROW   = "=>".
    ASSIGN   = "<-".
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
    PARAM    = "param".
    DEFAULT  = "default".
    NSPACE   = "namespace".
    IMPORT   = "import".
    DEF      = "def".
    THIS     = "this".
    OBJECT   = "object".
    name     = (letter | USCORE) {letter | digit | USCORE}.
    operator = opchar { opchar }.
    ident    = name | operator.
    integer  = ["-"] digit {digit}.
    boolean  = "true" | "false".
    string   = "..."

    comment = "//" {any character} NLINE.
~~~

Syntactical Grammar


~~~
    namespace = [NSPACE qualid] {import} {typedef | fundef | paramdef} EOF.

    qualid = ident | qualid DOT ident.

    import = IMPORT qualid.

    expr    = word {word}.

    word    = integer | boolean | string | ident | fence | record | tapply | select | variant | lambda | object | this.

    phrase  = expr | with_clause | default_param | assign | valdef | fundef | typedef | while | if | match.

    block   = { phrase }.

    select  = (ident | record | fence | select | this) DOT ident.

    with_clause = expr WITH (([ONLY] with_bindings) | NONE)

    default_param = qualid DEFAULT expr.

    with_bindings = with_binding {COMMA with_binding}.
    with_binding = qualid EQL expr.

    fence   = LPAREN phrase RPAREN.
    assign  = ident ASSIGN block.
    if      = IF expr THEN block [ELSE block] [END].
    while   = WHILE expr DO block [END].

    record     = LBRACE [named_args] RBRACE.
    named_args = named_arg { COMMA named_arg }.
    named_arg  = ident EQL expr.

    variant = TAG ident [args] [AS type].
    args    = LPAREN phrase {COMMA expr} RPAREN.

    object     = OBJECT LBRACE {member} RBRACE.
    member     = valdef | defdef.

    tapply  = word targs.
    lambda  = param_section RARROW block.

    match   = MATCH expr {case} [END].
    case    = CASE pat RARROW block.
    pat     = product_pat | USCORE.

    product_pat = TAG ident [product_bindings]
    product_bindings = LPAREN ident {COMMA ident} RPAREN

    valdef  = (VAL | VAR) ident [COLON type] EQL block.
    fundef  = FUN [param_section] ident [tparams] [param_section] EQL block [END].
    defdef  = DEF ident [tparams] [param_section] EQL block [END].

    paramdef = PARAM param

    typedef = TYPE ident[tparams] [EQL type].
    tparams = LBRACKET tparam {COMMA tparam} RBRACKET.
    tparam  = ident [SUBTYPE type].

    applied_type = ident targs.
    targs        = LBRACKET type { COMMA type } RBRACKET.

    type    = qualid | record_typ | union_typ | applied_type | fun_type | object_type | LPAREN type RPAREN.

    record_typ = LBRACE [fields]  RBRACE.
    fields     = field { COMMA field }.
    field      = ident COLON type.

    union_typ  = '<' [branches] '>'.
    branches   = branch { COMMA branch }.
    branch     = ident [param_section].

    fun_type   = [types] RARROW type.

    object_type = OBJECT LBRACE {method_decl} RBRACE.
    method_decl =  DEF ident param_section COLON type.

    param_section = LPAREN [params] RPAREN
    params        = param {COMMA param}.
    param         = ident COLON type.


~~~

## Namespaces

Each source file defines a _namespace_. Each file has the following structure:

```
namespace io.net

import system

type List[T] = <Nil, Cons(head: T, tail: List[T])>

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

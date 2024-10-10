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
    name     = (letter | USCORE) {letter | digit | USCORE}.
    operator = opchar { opchar }.
    ident    = name | operator.
    integer  = ["-"] digit {digit}.
    boolean  = "true" | "false".

    comment = "//" {any character} NLINE.
~~~

Syntactical Grammar


~~~
    program = block.

    expr    = word {word}.

    word    = integer | boolean | ident | fence | record | tapply | select | variant | lambda.

    phrase  = expr | assign | valdef | fundef | typedef | while | if | match.

    block   = { phrase }.

    select  = (ident | record | fence | select) DOT ident.

    fence   = LPAREN expr RPAREN.
    assign  = ident EQL block.
    if      = IF expr THEN block [ELSE block] [END].
    while   = WHILE expr DO block [END].

    record     = LBRACE [named_args] RBRACE.
    named_args = named_arg { COMMA named_arg }.
    named_arg  = ident EQL expr.

    variant = TAG ident [args] [AS type].
    args    = LPAREN phrase {COMMA expr} RPAREN.

    tapply  = ident targs.
    lambda  = param_section RARROW block.

    match   = MATCH block {case} [END].
    case    = CASE pat RARROW block.
    pat     = product_pat | USCORE.

    product_pat = TAG ident [product_bindings]
    product_bindings = LPAREN ident {COMMA ident} RPAREN

    valdef  = (VAL | VAR) ident [COLON type] EQL block.
    fundef  = FUN [param_section] ident [tparams] [param_section] EQL block [END].

    typedef = TYPE [tparams] ident EQL type.
    tparams = LBRACKET tparam {COMMA tparam} RBRACKET.
    tparam  = ident [SUBTYPE type].

    applied_type = ident targs.
    targs        = LBRACKET type { COMMA type } RBRACKET.

    type    = ident | record_typ | union_typ | applied_type | fun_type | LPAREN type RPAREN.

    record_typ = LBRACE [fields]  RBRACE.
    fields     = field { COMMA field }.
    field      = ident COLON type.

    union_typ  = '<' [branches] '>'.
    branches   = branch { COMMA branch }.
    branch     = ident [param_section].

    fun_type   = [types] RARROW type.

    param_section = LPAREN [params] RPAREN
    params        = param {COMMA param}.
    param         = ident COLON type.
~~~

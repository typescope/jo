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
    OF       = "of".
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
    expr    = word {word}.

    word    = integer | boolean | ident | select | fence | record | variant | tapply | lambda.

    phrase  = expr | assign | valdef | fundef | typedef | while | if | match.

    block   = { phrase }.

    select  = word DOT ident.

    fence   = LPAREN block RPAREN.
    assign  = ident EQL block.
    if      = IF block THEN block [ELSE block] [END].
    while   = WHILE block DO block [END].

    record     = LBRACE [named_args] RBRACE.
    named_args = named_arg { COMMA named_arg }.
    named_arg  = ident EQL block.

    variant = TAG ident {word} OF type.

    tapply  = ident targs.
    lambda  = param_section RARROW block.

    match   = MATCH block {case} [END].
    case    = CASE pat RARROW block.
    pat     = TAG ident {ident} | USCORE.

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
    branch     = ident [types].
    types      = type [ '*' type ].

    fun_type   = [types] RARROW type.

    program = {valdef | fundef | typedef} block.

    param_section = LPAREN [params] RPAREN
    params        = param {COMMA param}.
    param         = ident COLON type.
~~~

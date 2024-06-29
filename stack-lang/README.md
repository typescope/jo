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
    SEMICOL  = ";".
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
    word    = integer | boolean | ident | if | fence | assign | valdef | while | record | select | variant | match | tapply.
    fence   = LPAREN phrase RPAREN.
    assign  = ident EQL phrase SEMICOL.
    if      = IF phrase THEN phrase [ELSE phrase] END.
    while   = WHILE phrase DO phrase END.
    record  = LBRACE [named_args] RBRACE.
    select  = (ident | select) DOT ident.
    variant = TAG ident {word} OF type.
    tapply  = ident targs.

    match   = MATCH phrase {case} END.
    case    = CASE pat RARROW phrase.
    pat     = TAG ident {ident} | USCORE.

    phrase  = { typedef } word [phrase].

    valdef  = (VAL | VAR) ident [COLON type] EQL phrase SEMICOL.
    fundef  = FUN ident [tparams] LPAREN [params] RPAREN EQL phrase SEMICOL.

    typedef = TYPE [tparams] ident EQL type SEMICOL.
    tparams = LBRACKET tparam {COMMA tparam} RBRACKET.
    tparam  = ident [SUBTYPE type].

    applied_type = ident targs.
    targs        = LBRACKET type { COMMA type } RBRACKET.

    type    = ident | record_typ | union_typ | applied_type.

    named_args = named_arg { COMMA named_arg }.
    named_arg  = ident EQL phrase.
    record_typ = LBRACE [fields]  RBRACE.
    fields     = field { COMMA field }.
    field      = ident COLON type.

    union_typ  = '<' [branches] '>'.
    branches   = branch { COMMA branch }.
    branch     = ident {types}.
    types      = type [ '*' type ].

    program = {valdef | fundef | typedef} phrase.

    params  = param {COMMA param}.
    param   = ident COLON type.
~~~

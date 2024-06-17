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
    COMMA    = ",".
    DOT      = ".".
    LPAREN   = "(".
    RPAREN   = ")".
    LBRACKET = "[".
    RBRACKET = "]".
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
    word    = integer | boolean | ident | if | fence | assign | valdef | while | record | select | variant | match.
    fence   = LPAREN phrase RPAREN.
    assign  = ident EQL phrase SEMICOL.
    if      = IF phrase THEN phrase [ELSE phrase] END.
    while   = WHILE phrase DO phrase END.
    record  = LBRACKET [named_args] RBRACKET.
    select  = (ident | select) DOT ident.
    variant = TAG ident [phrase] OF type.

    match   = MATCH phrase {case} END.
    case    = CASE pat RARROW phrase.
    pat     = TAG ident [ident] | USCORE.

    phrase  = { typedef } word [phrase].

    valdef  = (VAL | VAR) ident COLON type EQL phrase SEMICOL.
    fundef  = FUN ident LPAREN [params] RPAREN EQL phrase SEMICOL.

    typedef = TYPE ident EQL type SEMICOL.
    type    = ident | record_typ | union_typ.

    named_args = named_arg { COMMA named_arg }.
    named_arg  = ident EQL phrase.
    record_typ = LBRACKET [fields]  RBRACKET.
    fields     = field { COMMA field }.
    field      = ident COLON type.

    union_typ  = '<' [branches] '>'.
    branches   = branch { COMMA branch }.
    branch     = ident [type].

    program = {valdef | fundef | typedef} phrase.

    params  = param {COMMA param}.
    param   = ident COLON type.
~~~

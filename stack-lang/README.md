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
    COMMA    = ",".
    DOT      = ".".
    LPAREN   = "(".
    RPAREN   = ")".
    LBRACKET = "[".
    RBRACKET = "]".
    IF       = "if".
    THEN     = "then".
    ELSE     = "else".
    WHILE    = "while".
    DO       = "do".
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
    word    = integer | boolean | ident | if | fence | assign | valdef | while | typedef | record | select.
    fence   = LPAREN phrase RPAREN.
    assign  = ident EQL phrase SEMICOL.
    if      = IF phrase THEN phrase [ELSE phrase] END.
    while   = WHILE phrase DO phrase END.
    record  = LBRACKET tagargs RBRACKET.
    select  = (ident | select) DOT ident.

    phrase  = word [phrase].

    valdef  = (VAL | VAR) type COLON ident EQL phrase SEMICOL.
    fundef  = FUN ident LPAREN [params] RPAREN EQL phrase SEMICOL.

    typedef    = TYPE ident EQL type.
    type       = ident | recordtyp.

    tagargs    = tagarg { COMMA tagarg }.
    tagarg     = ident EQL phrase.
    recordtyp  = LBRACKET tagfields  RBRACKET.
    tagfields  = tagfield { COMMA tagfield }.
    tagfield   = ident COLON type.

    program = {valdef | fundef} phrase.

    params  = param {COMMA param}
    param   = ident COLON type
~~~

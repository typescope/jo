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

    SEMICOL  = ";".
    VAL      = "val".
    VAR      = "var".
    FUN      = "fun".
    EQL      = "=".
    COMMA    = ",".
    LPAREN   = "(".
    RPAREN   = ")".
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
    word    = integer | boolean | ident | if | fence | assign | valdef | while.
    fence   = LPAREN phrase RPAREN.
    assign  = ident EQL phrase SEMICOL.
    if      = IF phrase THEN phrase [ELSE phrase] END.
    while   = WHILE phrase DO phrase END.
    phrase  = word [phrase].

    valdef  = (VAL | VAR) ident EQL phrase SEMICOL.
    fundef  = FUN ident LPAREN [params] RPAREN EQL phrase SEMICOL.
    program = {valdef | fundef} phrase.

    params  = ident {COMMA ident}
~~~

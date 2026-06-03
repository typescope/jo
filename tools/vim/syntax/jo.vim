" Vim syntax file for the Jo programming language
" Language: Jo
" Maintainer: TypeScope

if exists("b:current_syntax")
  finish
endif

" Keywords
syntax keyword joKeyword
  \ def defer val var type union class object interface extension
  \ section namespace import as pattern param
  \ if then else match case end
  \ while for do in
  \ return break continue
  \ allow with receives none
  \ new this
  \ private view

" Literals
syntax keyword joBoolean true false
syntax keyword joSpecial pass

" Operators — defined first so comments/strings (defined later) take priority
syntax match joOperator "[-+*/%<>=!&|^~?@:.]"

" Strings — multiline must come before single-line so """ is matched first
syntax region joMultiStr   start='"""' end='"""' contains=joInterp
syntax region joString     start='"'  end='"'  skip='\\"' contains=joInterp
syntax match  joInterp     "\\{[^}]*}" contained

" Characters
syntax match joChar "'[^'\\]'\|'\\.'"

" Numbers
syntax match joNumber "\<[0-9][0-9_]*\(\.[0-9][0-9_]*\([eE][+-]\?[0-9][0-9_]*\)\?\)\?\>"
syntax match joNumber "\<0[xX][0-9a-fA-F][0-9a-fA-F_]*\>"

" Types (capitalized identifiers)
syntax match joType "\<[A-Z][a-zA-Z0-9_]*\>"

" Comments — defined last so they override the / operator at //
syntax region joBlockComment start="//\[" end="//\]" contains=joBlockComment
syntax match  joLineComment  "//[^\[].*$"
syntax match  joLineComment  "//$"

" Highlight linking
highlight default link joKeyword     Keyword
highlight default link joBoolean     Boolean
highlight default link joSpecial     Special
highlight default link joLineComment Comment
highlight default link joBlockComment Comment
highlight default link joString      String
highlight default link joMultiStr    String
highlight default link joInterp      Special
highlight default link joChar        Character
highlight default link joNumber      Number
highlight default link joType        Type
highlight default link joOperator    Operator

let b:current_syntax = "jo"

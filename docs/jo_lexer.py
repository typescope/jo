"""
Simple Pygments lexer for the Jo programming language.
"""

from pygments.lexer import RegexLexer, bygroups, words
from pygments.token import (
    Comment, Keyword, Name, Number, Operator, Punctuation, String, Text, Whitespace
)


class JoLexer(RegexLexer):
    name = 'Jo'
    aliases = ['jo']
    filenames = ['*.jo']

    keywords = (
        # Definitions
        'def', 'val', 'var', 'type', 'class', 'union', 'param', 'pattern',
        'interface', 'object', 'section', 'alias', 'view', 'namespace', 'import',
        # Control flow
        'if', 'then', 'else', 'match', 'case', 'while', 'do', 'for', 'in',
        # Blocks
        'begin', 'end',
        # Expressions
        'new', 'is', 'as', 'with', 'receives', 'allow', 'having',
        # Modifiers
        'auto', 'defer', 'private', 'like',
        # Literals
        'true', 'false',
        # Special
        'this',
    )

    builtins = (
        # Types
        'Int', 'String', 'Bool', 'Char', 'Unit', 'Bottom', 'Float', 'Double',
        'Option', 'Some', 'None', 'List', 'Array', 'Map', 'Set', 'Partial',
        # IO
        'IO', 'println', 'print', 'abort', 'assert', 'pass'
    )

    tokens = {
        'root': [
            # Whitespace
            (r'\s+', Whitespace),

            # Comments - single line (important for annotations!)
            (r'//(?!\[).*$', Comment.Single),

            # Comments - multiline //[ ... //]
            (r'//+\[', Comment.Multiline, 'multiline-comment'),

            # Triple-quoted strings
            (r'"""', String.Double, 'triple-string'),

            # Single-line strings
            (r'"', String.Double, 'string'),

            # Character literals
            (r"'[^'\\]'", String.Char),
            (r"'\\[nrtbf\\\"']'", String.Char),
            (r"'\\u\{[0-9a-fA-F]+\}'", String.Char),

            # Numbers
            (r'0[xX][0-9a-fA-F][0-9a-fA-F_]*', Number.Hex),
            (r'\d[\d_]*\.\d[\d_]*([eE][+-]?\d[\d_]*)?', Number.Float),
            (r'\d[\d_]*[eE][+-]?\d[\d_]*', Number.Float),
            (r'\d[\d_]*', Number.Integer),

            # Keywords
            (words(keywords, prefix=r'\b', suffix=r'\b'), Keyword),

            # Builtins
            (words(builtins, prefix=r'\b', suffix=r'\b'), Name.Builtin),

            # Operators
            (r'=>|->|<-|<=|>=|==|!=|&&|\|\||::', Operator),
            (r'\.\.', Operator),  # spread operator
            (r'[+\-*/%<>=!&|^~?@]', Operator),

            # Punctuation
            (r'[{}()\[\],.;:]', Punctuation),

            # Type names (capitalized)
            (r'\b[A-Z][a-zA-Z0-9_]*\b', Name.Class),

            # Identifiers
            (r'\b[a-z_][a-zA-Z0-9_]*\b', Name),

            # Catch-all
            (r'.', Text),
        ],

        'string': [
            (r'\\[nrtbf"\'\\]', String.Escape),
            (r'\\u\{[0-9a-fA-F]+\}', String.Escape),
            (r'\\{', String.Interpol, 'interpolation'),
            (r'[^"\\]+', String.Double),
            (r'"', String.Double, '#pop'),
        ],

        'triple-string': [
            (r'\\u\{[0-9a-fA-F]+\}', String.Escape),
            (r'\\{', String.Interpol, 'interpolation'),
            (r'"""', String.Double, '#pop'),
            (r'[^"\\]+', String.Double),
            (r'"(?!"")', String.Double),
            (r'\\', String.Double),
        ],

        'interpolation': [
            (r'\}', String.Interpol, '#pop'),
            (r'[^}]+', String.Interpol),
        ],

        'multiline-comment': [
            (r'//+\]', Comment.Multiline, '#pop'),
            (r'//+\[', Comment.Multiline, '#push'),  # nested
            (r'[^/]+', Comment.Multiline),
            (r'/(?!/)', Comment.Multiline),
        ],
    }

// Jo Programming Language definition for highlight.js
hljs.registerLanguage('jo', function(hljs) {
  return {
    name: 'Jo',
    aliases: ['jo'],
    case_insensitive: false,
    keywords: {
      keyword: [
        'def', 'data', 'param', 'type', 'val', 'var', 'match', 'case', 'if', 'then', 'else',
        'with', 'end', 'import', 'section', 'receives', 'abort', 'return', 'extends'
      ].join(' '),
      built_in: [
        'String', 'Int', 'Bool', 'Unit', 'Any', 'Option', 'List', 'Array'
      ].join(' '),
      literal: [
        'true', 'false', 'None', 'Some', 'Empty'
      ].join(' ')
    },
    contains: [
      // Line comments
      hljs.COMMENT('//', '$'),

      // Block comments
      hljs.COMMENT('/\\*', '\\*/'),

      // String literals
      {
        className: 'string',
        begin: '"',
        end: '"',
        contains: [hljs.BACKSLASH_ESCAPE]
      },

      // Numbers
      hljs.NUMBER_MODE,

      // Type annotations with colon
      {
        begin: ':\\s*[A-Z][a-zA-Z0-9_]*',
        returnBegin: true,
        contains: [
          {
            begin: ':',
            className: 'operator'
          },
          {
            begin: '[A-Z][a-zA-Z0-9_]*',
            className: 'type'
          }
        ]
      },

      // Pattern matching arrow
      {
        begin: '=>',
        className: 'operator'
      },

      // Pipe for algebraic data types
      {
        begin: '\\|',
        className: 'operator'
      },

      // Function definitions
      {
        begin: '\\bdef\\s+[a-zA-Z_][a-zA-Z0-9_]*',
        returnBegin: true,
        contains: [
          {
            begin: 'def',
            className: 'keyword'
          },
          {
            begin: '[a-zA-Z_][a-zA-Z0-9_]*',
            className: 'title function_'
          }
        ]
      },

      // Data type definitions
      {
        begin: '\\bdata\\s+[A-Z][a-zA-Z0-9_]*',
        returnBegin: true,
        contains: [
          {
            begin: 'data',
            className: 'keyword'
          },
          {
            begin: '[A-Z][a-zA-Z0-9_]*',
            className: 'title class_'
          }
        ]
      },

      // Type definitions
      {
        begin: '\\btype\\s+[A-Z][a-zA-Z0-9_]*',
        returnBegin: true,
        contains: [
          {
            begin: 'type',
            className: 'keyword'
          },
          {
            begin: '[A-Z][a-zA-Z0-9_]*',
            className: 'title class_'
          }
        ]
      },

      // Constructor patterns in match cases
      {
        begin: '\\bcase\\s+[A-Z][a-zA-Z0-9_]*',
        returnBegin: true,
        contains: [
          {
            begin: 'case',
            className: 'keyword'
          },
          {
            begin: '[A-Z][a-zA-Z0-9_]*',
            className: 'title class_'
          }
        ]
      },

      // Context parameters with "receives"
      {
        begin: '\\breceives\\s+[a-zA-Z_][a-zA-Z0-9_,\\s]*',
        returnBegin: true,
        contains: [
          {
            begin: 'receives',
            className: 'keyword'
          },
          {
            begin: '[a-zA-Z_][a-zA-Z0-9_]*',
            className: 'variable'
          }
        ]
      },

      // Import statements
      {
        begin: '\\bimport\\s+[a-zA-Z0-9_.\\*]+',
        returnBegin: true,
        contains: [
          {
            begin: 'import',
            className: 'keyword'
          },
          {
            begin: '[a-zA-Z0-9_.\\*]+',
            className: 'string'
          }
        ]
      },

      // Variables and identifiers
      {
        begin: '\\b[a-z_][a-zA-Z0-9_]*\\b',
        className: 'variable'
      },

      // Types and constructors (capitalized)
      {
        begin: '\\b[A-Z][a-zA-Z0-9_]*\\b',
        className: 'type'
      }
    ]
  };
});

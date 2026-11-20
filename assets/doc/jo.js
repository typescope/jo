// Jo Programming Language definition for highlight.js
hljs.registerLanguage('jo', function(hljs) {
  return {
    name: 'Jo',
    aliases: ['jo'],
    case_insensitive: false,
    disableAutodetect: true,
    keywords: {
      keyword: 'annotation def union param type val var fun match case if then else with end import namespace section receives pattern allow while do begin auto defer class new as private view interface is for in object this pass extension return break continue rescue',
      built_in: 'String Int Bool Unit Any Option List Array',
      literal: 'true false None Some Empty'
    },
    contains: [
      // Block comments take priority over // and match their opening slash count.
      hljs.END_SAME_AS_BEGIN(hljs.COMMENT(/(\/{2,})\[/, /(?<!\/)(\/{2,})\]/)),

      // Line comments
      hljs.COMMENT('//', '$'),

      // Regex literals: #r"..." and #r[flags]"..."
      {
        className: 'regexp',
        begin: '#r(?:\\[[A-Za-z]+\\])?"',
        end: '"',
        contains: [
          {
            begin: '\\\\.',
            className: 'escape'
          }
        ]
      },

      // Raw multiline strings use matching delimiters of three or more quotes.
      hljs.END_SAME_AS_BEGIN({
        className: 'string',
        begin: /("{3,})/,
        end: /(?<!")("{3,})(?!")/
      }),

      // Single-line strings
      hljs.QUOTE_STRING_MODE,

      // A character is one code point or escape, not an open-ended quoted string.
      {
        className: 'string',
        match: /'(?:\\(?:[bfnrt"'\\]|u\{[0-9a-fA-F]{1,6}\})|[\uD800-\uDBFF][\uDC00-\uDFFF]|[^'\\\r\n])'/
      },

      // Numbers
      hljs.C_NUMBER_MODE,

      // Tags: #Nil, #Cons, etc.
      {
        className: 'symbol',
        begin: '#[A-Z][a-zA-Z0-9_]*'
      },

      // Type annotations: ": Type"
      {
        begin: ':\\s*[A-Z][a-zA-Z0-9_]*',
        returnBegin: true,
        contains: [
          {
            begin: ':',
            className: 'operator'
          },
          {
            begin: '\\s*[A-Z][a-zA-Z0-9_]*',
            className: 'type'
          }
        ]
      },

      // Operators
      {
        className: 'operator',
        begin: '=>|<:|[=:+\\-*/%<>!&|^~]+'
      }
    ]
  };
});

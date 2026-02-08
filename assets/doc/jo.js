// Jo Programming Language definition for highlight.js
hljs.registerLanguage('jo', function(hljs) {
  return {
    name: 'Jo',
    aliases: ['jo'],
    case_insensitive: false,
    disableAutodetect: true,
    keywords: {
      keyword: 'def union param type val var fun match case if then else with end import namespace section receives pattern allow while do begin auto defer class new as private view interface like is for in object this pass extend extension',
      built_in: 'String Int Bool Unit Any Option List Array',
      literal: 'true false None Some Empty'
    },
    contains: [
      // Line comments
      hljs.COMMENT('//', '$'),

      // Simple block comments for now - just //[ ... //]
      hljs.COMMENT('//\\[', '//\\]'),

      // Strings
      hljs.QUOTE_STRING_MODE,
      hljs.APOS_STRING_MODE,

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

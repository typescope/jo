import { defineConfig } from 'vitepress'
import { readFileSync } from 'fs'
import { resolve, dirname } from 'path'
import { fileURLToPath } from 'url'

const __dirname = dirname(fileURLToPath(import.meta.url))
const joGrammar = JSON.parse(readFileSync(resolve(__dirname, '../../tools/vscode/syntaxes/jo.tmLanguage.json'), 'utf-8'))

export default defineConfig({
  title: 'Jo Programming Language',
  description: 'Secure programming for the AI era',
  srcDir: '.',
  srcExclude: ['**/.vitepress/**', '**/img/**', '**/jo_lexer.py*', '**/setup.py', '**/__pycache__/**'],

  vite: {
    resolve: { preserveSymlinks: true }
  },

  markdown: {
    languages: [
      {
        ...joGrammar,
        name: 'jo',
      }
    ]
  },

  themeConfig: {
    siteTitle: 'Jo Language',

    nav: [
      { text: 'Overview', link: '/overview/index' },
      { text: 'AI Security', link: '/security/security-problem' },
      { text: 'Build Tool', link: '/usage/getting-started' },
      { text: 'Language Reference', link: '/language/design-principles' },
      { text: 'Blog', link: '/blog/' },
    ],

    sidebar: {
      '/': [
        {
          text: 'Overview',
          items: [
            { text: 'What is Jo?', link: '/overview/index' },
            { text: 'Language Tour', link: '/overview/language-tour' },
            { text: 'Capability-Oriented Programming', link: '/overview/capabilities' },
            { text: 'Cheat Sheet', link: '/overview/cheat-sheet' },
            { text: 'Get Started', link: '/usage/getting-started' },
          ]
        },
        {
          text: 'Guidelines',
          items: [
            { text: 'Algebraic Data Types', link: '/guides/adt' },
            { text: 'Pattern-Oriented Programming', link: '/guides/patterns' },
            { text: 'String Literals', link: '/guides/string' },
            { text: 'Error Handling', link: '/guides/error-model' },
            { text: 'Classes and Views', link: '/guides/interface-views' },
            { text: 'Regular Expressions', link: '/guides/regular-expression' },
            { text: 'Context Parameters', link: '/guides/context-parameters' },
            { text: 'Deferred Functions', link: '/guides/deferred-functions' },
            { text: 'Python Interoperability', link: '/guides/python-interoperability' },
            { text: 'Ruby Interoperability', link: '/guides/ruby-interoperability' },
            { text: 'JavaScript Interoperability', link: '/guides/javascript-interoperability' },
          ]
        }
      ],

      '/security/': [
        {
          text: 'AI Security',
          items: [
            { text: 'The Security Problem', link: '/security/security-problem' },
            { text: 'Two-World Architecture', link: '/security/two-worlds' },
            { text: 'Language Design', link: '/security/language-design' },
          ]
        },
        {
          text: 'Security Examples',
          items: [
            { text: 'Overview', link: '/security/examples/index' },
            { text: 'Process Monitor', link: '/security/examples/process-monitor' },
            { text: 'Data Query Agent', link: '/security/examples/data-query-agent' },
            { text: 'Sandbox Agent', link: '/security/examples/sandbox-agent' },
          ]
        }
      ],

      '/security/examples/': [
        {
          text: 'AI Security',
          items: [
            { text: 'The Security Problem', link: '/security/security-problem' },
            { text: 'Two-World Architecture', link: '/security/two-worlds' },
            { text: 'Language Design', link: '/security/language-design' },
          ]
        },
        {
          text: 'Security Examples',
          items: [
            { text: 'Overview', link: '/security/examples/index' },
            { text: 'Process Monitor', link: '/security/examples/process-monitor' },
            { text: 'Data Query Agent', link: '/security/examples/data-query-agent' },
            { text: 'Sandbox Agent', link: '/security/examples/sandbox-agent' },
          ]
        }
      ],

      '/usage/': [
        {
          text: 'Getting Started',
          items: [
            { text: 'Hello, World!', link: '/usage/getting-started' },
            { text: 'Install', link: '/usage/install' },
          ]
        },
        {
          text: 'Concepts',
          items: [
            { text: 'Projects', link: '/usage/concepts/projects' },
            { text: 'Packages', link: '/usage/concepts/packages' },
            { text: 'Pure Packages', link: '/usage/concepts/pure-packages' },
            { text: 'Dependency Depth', link: '/usage/concepts/dependency-depth' },
            { text: 'Lock File', link: '/usage/concepts/lock-file' },
            { text: 'Pinning', link: '/usage/concepts/pinning' },
            { text: 'Pre-Releases', link: '/usage/concepts/prereleases' },
            { text: 'Versioning', link: '/usage/concepts/versioning' },
          ]
        },
        {
          text: 'Guides',
          items: [
            { text: 'Creating a Project', link: '/usage/guides/creating-a-project' },
            { text: 'Managing Dependencies', link: '/usage/guides/dependencies' },
            { text: 'Publishing a Package', link: '/usage/guides/publishing' },
            { text: 'Writing FFI Packages', link: '/usage/guides/ffi' },
          ]
        },
        {
          text: 'Reference',
          items: [
            { text: 'Build Spec', link: '/usage/reference/build-spec' },
            { text: 'Library Metadata', link: '/usage/reference/library-metadata' },
            { text: 'Dependency Resolution', link: '/usage/reference/dependency-resolution' },
            { text: 'Registry', link: '/usage/reference/registry' },
          ]
        },
        {
          text: 'Commands',
          items: [
            { text: 'Overview', link: '/usage/commands/index' },
            { text: 'jo new', link: '/usage/commands/new' },
            { text: 'jo build', link: '/usage/commands/build' },
            { text: 'jo run', link: '/usage/commands/run' },
            { text: 'jo test', link: '/usage/commands/test' },
            { text: 'jo check', link: '/usage/commands/check' },
            { text: 'jo doc', link: '/usage/commands/doc' },
            { text: 'jo package', link: '/usage/commands/package' },
            { text: 'jo info', link: '/usage/commands/info' },
            { text: 'jo deps', link: '/usage/commands/deps' },
            { text: 'jo lock', link: '/usage/commands/lock' },
            { text: 'jo clean', link: '/usage/commands/clean' },
            { text: 'jo compile', link: '/usage/commands/compile' },
          ]
        }
      ],

      '/blog/': [
        {
          text: 'Blog',
          items: [
            { text: 'All Posts', link: '/blog/' },
          ]
        }
      ],

      '/language/': [
        {
          text: 'Foundations',
          items: [
            { text: 'Design Principles', link: '/language/design-principles' },
            { text: 'Name Universes', link: '/language/names' },
            { text: 'Visibility and Coherence', link: '/language/visibility-coherence' },
          ]
        },
        {
          text: 'Syntax',
          items: [
            { text: 'Structure & Convention', link: '/language/syntax/structure-and-convention' },
            { text: 'Expression Syntax', link: '/language/syntax/expression-syntax' },
            { text: 'Block Comments', link: '/language/syntax/block-comment' },
            { text: 'Syntax Summary', link: '/language/syntax/syntax-summary' },
          ]
        },
        {
          text: 'Types',
          items: [
            { text: 'Overview', link: '/language/types/overview' },
            { text: 'Basic Types', link: '/language/types/basic-types' },
            { text: 'Lambda Types', link: '/language/types/lambda-types' },
            { text: 'Named Types', link: '/language/types/named-types' },
            { text: 'Union Types', link: '/language/types/union-types' },
            { text: 'Duck Types', link: '/language/types/duck-types' },
            { text: 'Extension Types', link: '/language/types/extension-types' },
            { text: 'Annotation Types', link: '/language/types/annotation-types' },
            { text: 'Type Inference', link: '/language/types/type-inference' },
            { text: 'Type Adaptation', link: '/language/types/type-adaptation' },
          ]
        },
        {
          text: 'Expressions',
          items: [
            { text: 'Overview', link: '/language/expressions/overview' },
            { text: 'Expression Forms', link: '/language/expressions/expression-forms' },
            { text: 'Applications', link: '/language/expressions/applications' },
            { text: 'Phrases', link: '/language/expressions/phrases' },
            { text: 'Blocks', link: '/language/expressions/blocks' },
            { text: 'Control Flow', link: '/language/expressions/control-flow' },
            { text: 'Rescue Expression', link: '/language/expressions/rescue-expression' },
            { text: 'Lambdas', link: '/language/expressions/lambdas' },
            { text: 'Literals', link: '/language/expressions/literals' },
            { text: 'Is Expression', link: '/language/expressions/is-expression' },
            { text: 'Regular Expressions', link: '/language/expressions/regular-expressions' },
            { text: 'String Literals', link: '/language/expressions/string' },
          ]
        },
        {
          text: 'Patterns',
          items: [
            { text: 'Overview', link: '/language/patterns/overview' },
            { text: 'Pattern Forms', link: '/language/patterns/pattern-forms' },
            { text: 'Regex Patterns', link: '/language/patterns/regex-patterns' },
            { text: 'Sequence Patterns', link: '/language/patterns/sequence-patterns' },
            { text: 'Semantics', link: '/language/patterns/semantics' },
          ]
        },
        {
          text: 'Definitions',
          items: [
            { text: 'Overview', link: '/language/definitions/overview' },
            { text: 'Function Definitions', link: '/language/definitions/function-definitions' },
            { text: 'Value Definitions', link: '/language/definitions/value-definitions' },
            { text: 'Pattern Value Definitions', link: '/language/definitions/pattern-value-definitions' },
            { text: 'Pattern Definitions', link: '/language/definitions/pattern-definitions' },
            { text: 'Context Parameters', link: '/language/definitions/context-parameters' },
            { text: 'Varargs', link: '/language/definitions/varargs' },
            { text: 'Autos', link: '/language/definitions/autos' },
            { text: 'Deferred Functions', link: '/language/definitions/deferred-functions' },
            { text: 'Section Definitions', link: '/language/definitions/section-definitions' },
            { text: 'Interface Definitions', link: '/language/definitions/interface-definitions' },
            { text: 'Extension Definitions', link: '/language/definitions/extension-definitions' },
            { text: 'Type Definitions', link: '/language/definitions/type-definitions' },
            { text: 'Class Definitions', link: '/language/definitions/class-definitions' },
            { text: 'Object Definitions', link: '/language/definitions/object-definitions' },
            { text: 'Algebraic Data Types', link: '/language/definitions/union-definition' },
            { text: 'Annotations', link: '/language/definitions/annotations' },
          ]
        }
      ]
    },

    socialLinks: [
      { icon: 'github', link: 'https://github.com/liufengyun/jo' }
    ],

    search: {
      provider: 'local'
    },

    outline: {
      level: [2, 3]
    }
  }
})

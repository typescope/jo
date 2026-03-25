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
      { text: 'Usage', link: '/usage/getting-started' },
      { text: 'Language Reference', link: '/language/design-principles' },
    ],

    sidebar: {
      '/': [
        {
          text: 'Overview',
          items: [
            { text: 'What is Jo?', link: '/overview/index' },
            { text: 'Language Tour', link: '/overview/language-tour' },
            { text: 'Capability-Oriented Programming', link: '/overview/capabilities' },
            { text: 'Pattern-Oriented Programming', link: '/overview/patterns' },
            { text: 'Type-Safe Dependency Injection', link: '/overview/dependency-injection' },
            { text: 'Cheat Sheet', link: '/overview/cheat-sheet' },
            { text: 'Get Started', link: '/usage/getting-started' },
          ]
        }
      ],

      '/security/': [
        {
          text: 'AI Security',
          items: [
            { text: 'The Security Problem', link: '/security/security-problem' },
            { text: "Jo's Solution", link: '/security/solution' },
            { text: 'Comparison', link: '/security/comparison' },
            { text: 'Technical Questions', link: '/security/technical-questions' },
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
            { text: "Jo's Solution", link: '/security/solution' },
            { text: 'Comparison', link: '/security/comparison' },
            { text: 'Technical Questions', link: '/security/technical-questions' },
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
            { text: 'Dependency Depth', link: '/usage/concepts/dependency-depth' },
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
            { text: 'Lock File', link: '/usage/reference/lock-file' },
            { text: 'Library Metadata', link: '/usage/reference/library-metadata' },
            { text: 'Versioning', link: '/usage/reference/versioning' },
            { text: 'Dependency Resolution', link: '/usage/reference/dependency-resolution' },
            { text: 'Registry', link: '/usage/reference/registry' },
            { text: 'Compiler Versions', link: '/usage/reference/versions' },
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
            { text: 'jo fmt', link: '/usage/commands/fmt' },
            { text: 'jo doc', link: '/usage/commands/doc' },
            { text: 'jo package', link: '/usage/commands/package' },
            { text: 'jo publish', link: '/usage/commands/publish' },
            { text: 'jo register', link: '/usage/commands/register' },
            { text: 'jo search', link: '/usage/commands/search' },
            { text: 'jo info', link: '/usage/commands/info' },
            { text: 'jo deps', link: '/usage/commands/deps' },
            { text: 'jo lock', link: '/usage/commands/lock' },
            { text: 'jo clean', link: '/usage/commands/clean' },
            { text: 'jo compile', link: '/usage/commands/compile' },
            { text: 'jo versions', link: '/usage/commands/versions' },
          ]
        }
      ],

      '/language/': [
        {
          text: 'Language Reference',
          items: [
            { text: 'Design Principles', link: '/language/design-principles' },
            { text: 'Structure & Convention', link: '/language/structure-and-convention' },
            { text: 'Visibility and Coherence', link: '/language/visibility-coherence' },
            { text: 'Block Comments', link: '/language/block-comment' },
            { text: 'Syntax Summary', link: '/language/syntax-summary' },
          ]
        },
        {
          text: 'Concepts & Foundation',
          items: [
            { text: 'Name Universes', link: '/language/concepts/names' },
            { text: 'Context Parameters', link: '/language/concepts/context-parameters' },
            { text: 'Classes and Views', link: '/language/concepts/interface-views' },
            { text: 'Expression Syntax', link: '/language/concepts/expression-syntax' },
          ]
        },
        {
          text: 'Types',
          items: [
            { text: 'Overview', link: '/language/types/overview' },
            { text: 'Basic Types', link: '/language/types/basic-types' },
            { text: 'Lambda Types', link: '/language/types/lambda-types' },
            { text: 'Class Types', link: '/language/types/class-types' },
            { text: 'Union Types', link: '/language/types/union-types' },
            { text: 'Type Aliases', link: '/language/types/type-aliases' },
            { text: 'Duck Types', link: '/language/types/duck-types' },
            { text: 'Extension Types', link: '/language/types/extension-types' },
            { text: 'Type Inference', link: '/language/types/type-inference' },
          ]
        },
        {
          text: 'Expressions',
          items: [
            { text: 'Overview', link: '/language/expressions/overview' },
            { text: 'Words', link: '/language/expressions/words' },
            { text: 'Literals', link: '/language/expressions/literals' },
            { text: 'Regular Expressions', link: '/language/expressions/regular-expressions' },
            { text: 'Applications', link: '/language/expressions/applications' },
            { text: 'Lambdas', link: '/language/expressions/lambdas' },
            { text: 'Is Expression', link: '/language/expressions/is-expression' },
            { text: 'Multiline Strings', link: '/language/expressions/multiline-string' },
            { text: 'String Interpolation', link: '/language/expressions/string-interpolation' },
            { text: 'Isolated Terms', link: '/language/expressions/isolated-terms' },
            { text: 'Block Terms', link: '/language/expressions/block-terms' },
            { text: 'Phrases', link: '/language/expressions/phrases' },
            { text: 'Control Flow', link: '/language/expressions/control-flow' },
            { text: 'Blocks', link: '/language/expressions/blocks' },
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
            { text: 'Pattern Definitions', link: '/language/patterns/pattern-definitions' },
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
            { text: 'Algebraic Data Types', link: '/language/definitions/adt' },
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

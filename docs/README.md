# Jo Documentation Structure

This directory serves dual purposes:
1. **Standalone docs** - Individual markdown files for language features
2. **MkDocs website** - Organized structure for the documentation website

## Current Structure

```
docs/
├── index.md                    # Website homepage
├── README.md                   # This file - structure guide
│
├── Website Content:
├── overview/
│   └── what-is-jo.md          # Business/investor content
├── security/                   # (to be created)
│   ├── ai-confinement.md
│   ├── capabilities.md
│   └── access-control.md
├── demos/
│   └── index.md               # Demo overview
├── language/
│   ├── tour.md                # Language introduction
│   ├── block-comment.md       # Advanced Features
│   ├── deferred-functions.md  # Advanced Features
│   ├── multiline-string.md    # Advanced Features
│   └── varargs.md             # Advanced Features
│
└── Assets:
    ├── css/
    │   ├── center-layout.css      # For bin/md2html
    │   ├── highlight-modern.css   # For bin/md2html  
    │   └── mkdocs-extra.css       # For MkDocs site
    └── js/
        ├── highlight.min.js       # For bin/md2html
        └── jo.js                  # Jo syntax highlighting
```

## Usage

### For Individual Documentation
Each `.md` file can be read standalone and used with `bin/md2html`:
```bash
bin/md2html docs/language/multiline-string.md
```

### For MkDocs Website
Run the documentation website:
```bash
mkdocs serve    # Local development
mkdocs build    # Generate static site
```

## Adding Content

### New Language Features
Add `.md` files to `docs/language/` and reference them in `mkdocs.yml` navigation.

### New Website Sections  
Create subdirectories under `docs/` and update `mkdocs.yml` navigation.

### Demo Integration
Adapt content from `demos/*/README.md` into `docs/demos/` pages.

This structure eliminates duplication while supporting both individual doc usage and the comprehensive website.
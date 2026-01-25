// Jo Documentation App

const app = {
  meta: null,
  nav: null,
  search: null,
  cache: new Map(),

  async init() {
    // Load meta and nav data
    this.meta = await this.fetchJson('data/meta.json');
    this.nav = await this.fetchJson('data/nav.json');
    this.search = await this.fetchJson('data/search.json');

    // Set title
    document.getElementById('project-title').textContent = this.meta.title;
    document.title = this.meta.title;

    // Render navigation
    this.renderNav();

    // Setup search
    this.setupSearch();

    // Handle routing
    window.addEventListener('hashchange', () => this.route());
    this.route();
  },

  async fetchJson(path) {
    if (this.cache.has(path)) return this.cache.get(path);
    const res = await fetch(path);
    const data = await res.json();
    this.cache.set(path, data);
    return data;
  },

  renderNav() {
    const container = document.getElementById('nav-tree');
    container.innerHTML = this.nav.children.map(ns => this.renderNavItem(ns)).join('');
  },

  renderNavItem(item) {
    const hasMembers = item.members && item.members.length > 0;
    const hasChildren = item.children && item.children.length > 0;
    const hasNested = hasMembers || hasChildren;
    const itemId = item.fullName.replace(/\./g, '-');

    let html = `<div class="nav-item">`;
    html += `<div class="nav-row">`;

    if (hasNested) {
      html += `<span class="nav-toggle" onclick="app.toggleNav('${itemId}')" data-target="${itemId}">▶</span>`;
    } else {
      html += `<span class="nav-toggle-spacer"></span>`;
    }

    html += `<a href="#/${item.fullName}" class="nav-link">${item.name}</a>`;
    html += `</div>`;

    if (hasMembers) {
      html += `<div class="nav-children" id="nav-${itemId}" style="display: none;">`;
      for (const m of item.members) {
        const memberId = m.fullName.replace(/\./g, '-');
        html += `<div class="nav-item">`;
        html += `<div class="nav-row">`;
        html += `<span class="nav-toggle-spacer"></span>`;
        // Show kind badges for multi-kind entries
        const kinds = m.kinds || [m.kind || 'unknown'];
        const badges = kinds.map(k => `<span class="nav-kind-badge kind-${k}" title="${k}">${k[0]}</span>`).join('');
        html += `<a href="#/${m.fullName}" class="nav-link nav-member">${m.name}</a>`;
        if (kinds.length > 0) html += `<span class="nav-kinds">${badges}</span>`;
        html += `</div>`;
        html += `</div>`;
      }
      html += `</div>`;
    }

    if (hasChildren) {
      const childrenId = hasMembers ? `nav-${itemId}-children` : `nav-${itemId}`;
      html += `<div class="nav-children" id="${childrenId}" style="display: none;">`;
      html += item.children.map(c => this.renderNavItem(c)).join('');
      html += `</div>`;
    }

    html += `</div>`;
    return html;
  },

  toggleNav(itemId) {
    const target = document.getElementById('nav-' + itemId);
    const toggle = document.querySelector(`[data-target="${itemId}"]`);

    if (target) {
      const isHidden = target.style.display === 'none';
      target.style.display = isHidden ? 'block' : 'none';
      if (toggle) toggle.textContent = isHidden ? '▼' : '▶';
    }

    // Also toggle children container if it exists
    const childrenTarget = document.getElementById('nav-' + itemId + '-children');
    if (childrenTarget) {
      childrenTarget.style.display = target.style.display;
    }
  },

  expandNavTo(fullName) {
    // Expand all parent nodes to show the target
    const parts = fullName.split('.');
    for (let i = 1; i <= parts.length; i++) {
      const parentPath = parts.slice(0, i).join('.');
      const itemId = parentPath.replace(/\./g, '-');
      const target = document.getElementById('nav-' + itemId);
      const toggle = document.querySelector(`[data-target="${itemId}"]`);

      if (target && target.style.display === 'none') {
        target.style.display = 'block';
        if (toggle) toggle.textContent = '▼';
      }
    }
  },

  setupSearch() {
    const input = document.getElementById('search-input');
    let timeout;

    input.addEventListener('input', (e) => {
      clearTimeout(timeout);
      timeout = setTimeout(() => this.handleSearch(e.target.value), 200);
    });
  },

  handleSearch(query) {
    if (!query.trim()) {
      this.clearSearchResults();
      return;
    }

    const results = this.search
      .filter(item => item.name.toLowerCase().includes(query.toLowerCase()) ||
                      item.fullName.toLowerCase().includes(query.toLowerCase()))
      .slice(0, 20);

    this.renderSearchResults(results);
  },

  renderSearchResults(results) {
    const existing = document.querySelector('.search-results');
    if (existing) existing.remove();

    if (results.length === 0) return;

    const container = document.createElement('div');
    container.className = 'search-results';
    container.innerHTML = results.map(r =>
      `<div class="search-result-item" onclick="app.goTo('${r.fullName}')">
        <span class="kind-badge kind-${r.kind}">${r.kind}</span>
        <strong>${r.name}</strong>
        <small style="color: var(--text-secondary); margin-left: 0.5rem">${r.fullName}</small>
      </div>`
    ).join('');

    document.querySelector('.sidebar-header').appendChild(container);
  },

  clearSearchResults() {
    const existing = document.querySelector('.search-results');
    if (existing) existing.remove();
  },

  goTo(path) {
    window.location.hash = '#/' + path;
    this.clearSearchResults();
    document.getElementById('search-input').value = '';
  },

  async route() {
    const hash = window.location.hash.slice(2) || '';
    const parts = hash.split('/');
    const path = parts[0];

    // Expand navigation tree to show current path
    if (path) {
      this.expandNavTo(path);
    }

    // Update active nav link
    document.querySelectorAll('.nav-link').forEach(link => {
      link.classList.toggle('active', link.getAttribute('href') === '#/' + path);
    });

    if (!path) {
      this.renderHome();
      return;
    }

    // Find the namespace that contains this path
    const nsPath = this.findNamespacePath(path);
    if (nsPath) {
      await this.renderNamespace(nsPath, path);
    } else {
      this.renderNotFound(path);
    }
  },

  findNamespacePath(fullName) {
    // Try exact match first
    for (const ns of this.nav.children) {
      if (ns.fullName === fullName) return ns.fullName;
      // Check if it's a member of this namespace
      if (ns.members) {
        for (const m of ns.members) {
          if (m.fullName === fullName) return ns.fullName;
        }
      }
    }
    return null;
  },

  renderHome() {
    const content = document.getElementById('main-content');
    const breadcrumb = document.getElementById('breadcrumb');

    breadcrumb.innerHTML = '';
    content.innerHTML = `
      <h1>${this.meta.title}</h1>
      <p>Generated at ${new Date(this.meta.generatedAt).toLocaleString()}</p>
      <h2>Namespaces</h2>
      <div class="members-list">
        ${this.nav.children.map(ns => `
          <div class="member-item">
            <a href="#/${ns.fullName}" class="type-link">${ns.fullName}</a>
          </div>
        `).join('')}
      </div>
    `;
  },

  async renderNamespace(nsPath, targetPath) {
    const content = document.getElementById('main-content');
    const breadcrumb = document.getElementById('breadcrumb');

    // Load namespace data
    const data = await this.fetchJson(`data/symbols/${nsPath}.json`);

    // Render breadcrumb
    const parts = nsPath.split('.');
    breadcrumb.innerHTML = parts.map((p, i) => {
      const path = parts.slice(0, i + 1).join('.');
      return `<a href="#/${path}">${p}</a>`;
    }).join(' &gt; ');

    // Check if we're viewing a specific member
    if (targetPath !== nsPath) {
      this.renderMember(data, targetPath);
      return;
    }

    // Render namespace overview
    content.innerHTML = `
      <h1>${data.name}</h1>
      ${data.doc ? `<div class="doc">${this.renderDoc(data.doc)}</div>` : ''}
      ${this.renderDefinitions(data)}
    `;
  },

  renderDefinitions(data) {
    let html = '';

    // Group definitions by name
    const groups = this.groupByName(data);

    for (const [name, items] of groups) {
      html += `<div class="definition-group">`;

      for (const item of items) {
        html += this.renderDefinition(item);
      }

      html += `</div>`;
    }

    // Render sections
    if (data.sections && data.sections.length > 0) {
      html += `<h2>Sections</h2>`;
      for (const sec of data.sections) {
        html += `
          <div class="definition">
            <div class="definition-header">
              <span class="kind-badge kind-section">section</span>
              <span class="definition-name">${sec.name}</span>
            </div>
            ${sec.doc ? `<div class="doc">${this.renderDoc(sec.doc)}</div>` : ''}
            ${this.renderDefinitions(sec)}
          </div>
        `;
      }
    }

    return html;
  },

  groupByName(data) {
    const groups = new Map();

    const addItem = (item, kind) => {
      const key = item.name;
      if (!groups.has(key)) groups.set(key, []);
      groups.get(key).push({ ...item, _kind: kind });
    };

    if (data.types) data.types.forEach(t => addItem(t, t.kind));
    if (data.functions) data.functions.forEach(f => addItem(f, 'function'));
    if (data.patterns) data.patterns.forEach(p => addItem(p, 'pattern'));
    if (data.objects) data.objects.forEach(o => addItem(o, 'object'));
    if (data.contexts) data.contexts.forEach(c => addItem(c, 'context'));

    return new Map([...groups.entries()].sort((a, b) => a[0].localeCompare(b[0])));
  },

  renderDefinition(item) {
    const kind = item._kind || item.kind || 'unknown';

    let html = `<div class="definition" id="${item.fullName}">`;
    html += `<div class="definition-header">`;
    html += `<span class="kind-badge kind-${kind}">${kind}</span>`;
    html += `<span class="definition-name">${item.name}</span>`;

    if (item.source) {
      html += `<span class="source-link">${item.source.file}:${item.source.line}</span>`;
    }

    html += `</div>`;

    // Signature
    html += `<div class="signature">${this.renderSignature(item)}</div>`;

    // Doc
    if (item.doc) {
      html += `<div class="doc">${this.renderDoc(item.doc)}</div>`;
    }

    // Methods for classes/interfaces
    if (item.methods && item.methods.length > 0) {
      html += `<h3>Methods</h3>`;
      html += `<div class="members-list">`;
      for (const m of item.methods) {
        html += `
          <div class="member-item">
            <span class="kind-badge kind-method">method</span>
            <code>${m.name}${this.renderParams(m)}: ${this.renderType(m.returnType)}</code>
            ${m.doc ? `<div class="doc">${this.renderDoc(m.doc)}</div>` : ''}
          </div>
        `;
      }
      html += `</div>`;
    }

    // Views
    if (item.views && item.views.length > 0) {
      html += `<h3>Views</h3>`;
      html += `<p>${item.views.map(v => this.renderType(v)).join(', ')}</p>`;
    }

    html += `</div>`;
    return html;
  },

  renderSignature(item) {
    let sig = '';

    // Type params
    if (item.typeParams && item.typeParams.length > 0) {
      sig += `[${item.typeParams.join(', ')}]`;
    }

    // Constructor/params - use hasOwnProperty to avoid JS built-in constructor
    const ctor = Object.prototype.hasOwnProperty.call(item, 'constructor') ? item.constructor : null;
    if (ctor && ctor.params) {
      sig += `(${ctor.params.map(p => `${p.name}: ${this.renderType(p.type)}`).join(', ')})`;
    } else if (item.params) {
      sig += this.renderParams(item);
    }

    // Return type
    if (item.returnType) {
      sig += `: ${this.renderType(item.returnType)}`;
    }

    // Alias
    if (item.aliasOf) {
      sig = ` = ${this.renderType(item.aliasOf)}`;
    }

    // Cases for unions
    if (item.cases) {
      sig = ` = ${item.cases.map(c =>
        c.fields.length === 0 ? c.name : `${c.name}(${c.fields.map(f => `${f.name}: ${this.renderType(f.type)}`).join(', ')})`
      ).join(' | ')}`;
    }

    // Context parameter type
    if (item._kind === 'context' && item.type) {
      sig = `: ${this.renderType(item.type)}`;
    }

    return sig;
  },

  renderParams(item) {
    if (!item.params || item.params.length === 0) return '';
    return `(${item.params.map(p => {
      let s = `${p.name}: ${this.renderType(p.type)}`;
      if (p.modifier === 'auto') s = `auto ${s}`;
      return s;
    }).join(', ')})`;
  },

  renderType(type) {
    if (!type) return '?';

    switch (type.kind) {
      case 'ref':
        return `<a href="#/${type.name}" class="type-link">${this.shortName(type.name)}</a>`;

      case 'applied':
        const args = type.args.map(a => this.renderType(a)).join(', ');
        return `<a href="#/${type.name}" class="type-link">${this.shortName(type.name)}</a>[${args}]`;

      case 'fun':
        const params = type.params.map(p => this.renderType(p)).join(', ');
        return `(${params}) -&gt; ${this.renderType(type.result)}`;

      case 'tuple':
        return `(${type.elements.map(e => this.renderType(e)).join(', ')})`;

      case 'union':
        return type.branches.map(b => this.renderType(b)).join(' | ');

      case 'literal':
        return String(type.value);

      default:
        return type.repr || '?';
    }
  },

  shortName(fullName) {
    const parts = fullName.split('.');
    return parts[parts.length - 1];
  },

  renderDoc(doc) {
    if (!doc) return '';
    // Simple markdown-like rendering
    return doc
      .replace(/\n\n/g, '</p><p>')
      .replace(/\n/g, '<br>')
      .replace(/`([^`]+)`/g, '<code>$1</code>');
  },

  renderMember(data, targetPath) {
    const content = document.getElementById('main-content');

    // Find ALL members with this fullName (may have multiple kinds)
    const results = this.findAllMembers(data, targetPath);

    if (results.length === 0) {
      this.renderNotFound(targetPath);
      return;
    }

    // Render all definitions with this name
    const name = this.shortName(targetPath);
    let html = `<h1>${name}</h1>`;
    html += `<div class="definition-group">`;
    for (const result of results) {
      html += this.renderDefinition({ ...result.member, _kind: result.kind });
    }
    html += `</div>`;
    content.innerHTML = html;
  },

  findAllMembers(data, fullName) {
    const results = [];

    // Search in types
    if (data.types) {
      for (const item of data.types) {
        if (item.fullName === fullName) {
          results.push({ member: item, kind: item.kind || 'type' });
        }
      }
    }

    // Search in functions
    if (data.functions) {
      for (const item of data.functions) {
        if (item.fullName === fullName) {
          results.push({ member: item, kind: 'function' });
        }
      }
    }

    // Search in patterns
    if (data.patterns) {
      for (const item of data.patterns) {
        if (item.fullName === fullName) {
          results.push({ member: item, kind: 'pattern' });
        }
      }
    }

    // Search in objects
    if (data.objects) {
      for (const item of data.objects) {
        if (item.fullName === fullName) {
          results.push({ member: item, kind: 'object' });
        }
      }
    }

    // Search in contexts
    if (data.contexts) {
      for (const item of data.contexts) {
        if (item.fullName === fullName) {
          results.push({ member: item, kind: 'context' });
        }
      }
    }

    // Search recursively in sections
    if (data.sections) {
      for (const sec of data.sections) {
        const found = this.findAllMembers(sec, fullName);
        results.push(...found);
      }
    }

    return results;
  },

  renderNotFound(path) {
    const content = document.getElementById('main-content');
    content.innerHTML = `<h1>Not Found</h1><p>The path "${path}" was not found.</p>`;
  }
};

// Initialize app
document.addEventListener('DOMContentLoaded', () => app.init());

// Jo Documentation App

const app = {
  meta: null,
  nav: null,
  search: null,
  cache: new Map(),
  kindBadge: {
    'class': 'C',
    'interface': 'I',
    'function': 'F',
    'pattern': 'P',
    'object': 'O',
    'type': 'T',
    'abstract': 'T',
    'section': 'S',
    'context': 'X',
    'method': 'M'
  },

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
        const badges = kinds.map(k => `<span class="nav-kind-badge kind-${k}" title="${k}">${this.kindBadge[k] || k[0].toUpperCase()}</span>`).join('');
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

  // Format name with type parameters (e.g., "Option[T]" or "[S] ~ [T]" for infix)
  formatNameWithTypeParams(item) {
    // Handle new structure with preTypeParams/postTypeParams
    const preParams = item.preTypeParams || [];
    const postParams = item.postTypeParams || [];

    // Infix style: [preParams] name [postParams]
    if (preParams.length > 0) {
      let result = `[${preParams.join(', ')}] ${item.name}`;
      if (postParams.length > 0) {
        result += ` [${postParams.join(', ')}]`;
      }
      return result;
    }

    // Regular style: name[params]
    // Fallback to old typeParams field
    const allParams = item.typeParams || [...preParams, ...postParams];
    if (allParams.length > 0) {
      return `${item.name}[${allParams.join(', ')}]`;
    }
    return item.name;
  },


  setupSearch() {
    const input = document.getElementById('search-input');
    const resultsContainer = document.getElementById('search-results');
    let timeout;

    input.addEventListener('input', (e) => {
      clearTimeout(timeout);
      timeout = setTimeout(() => this.handleSearch(e.target.value), 150);
    });

    input.addEventListener('focus', () => {
      if (input.value.trim()) {
        this.handleSearch(input.value);
      }
    });

    // Close dropdown when clicking outside
    document.addEventListener('click', (e) => {
      if (!e.target.closest('#search-container')) {
        this.clearSearchResults();
      }
    });
  },

  handleSearch(query) {
    if (!query.trim()) {
      this.clearSearchResults();
      return;
    }

    const queryLower = query.toLowerCase();
    const results = this.search
      .filter(item => item.name.toLowerCase().includes(queryLower) ||
                      item.fullName.toLowerCase().includes(queryLower))
      .slice(0, 10);

    this.renderSearchResults(results);
  },

  renderSearchResults(results) {
    const container = document.getElementById('search-results');

    if (results.length === 0) {
      container.classList.remove('active');
      container.innerHTML = '';
      return;
    }

    container.innerHTML = results.map(r => {
      // For methods, navigate to parent type (methods are displayed on their parent's page)
      const linkPath = r.kind === 'method' ? this.getParentPath(r.fullName) : r.fullName;
      return `<div class="search-result-item" onclick="app.goTo('${linkPath}')">
        <span class="kind-badge kind-${r.kind}">${this.kindBadge[r.kind] || r.kind[0].toUpperCase()}</span>
        <span class="search-result-name">${r.name}</span>
        <span class="search-result-path">${r.fullName}</span>
      </div>`;
    }).join('');

    container.classList.add('active');
  },

  getParentPath(fullName) {
    const lastDot = fullName.lastIndexOf('.');
    return lastDot > 0 ? fullName.substring(0, lastDot) : fullName;
  },

  clearSearchResults() {
    const container = document.getElementById('search-results');
    container.classList.remove('active');
    container.innerHTML = '';
  },

  goTo(path) {
    window.location.hash = '#/' + path;
    this.clearSearchResults();
    document.getElementById('search-input').value = '';
  },

  async route() {
    // Don't split on '/' since it can be part of symbol names like "jo.Predef./"
    // Use try-catch for decodeURIComponent in case of malformed sequences (e.g., literal '%')
    let path;
    try {
      path = decodeURIComponent(window.location.hash.slice(2) || '');
    } catch (e) {
      path = window.location.hash.slice(2) || '';
    }

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
      // Check if this is a prefix of multiple namespaces
      const childNamespaces = this.findChildNamespaces(path);
      if (childNamespaces.length > 0) {
        this.renderNamespacePrefix(path, childNamespaces);
      } else {
        this.renderNotFound(path);
      }
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

    // Fallback: check if fullName starts with a namespace (for nested types not in nav)
    for (const ns of this.nav.children) {
      if (fullName.startsWith(ns.fullName + '.')) {
        return ns.fullName;
      }
    }

    return null;
  },

  findChildNamespaces(prefix) {
    // Find all namespaces that start with this prefix
    const results = [];
    for (const ns of this.nav.children) {
      if (ns.fullName.startsWith(prefix + '.') || ns.fullName === prefix) {
        results.push(ns);
      }
    }
    return results;
  },

  renderNamespacePrefix(prefix, namespaces) {
    const content = document.getElementById('main-content');
    const breadcrumb = document.getElementById('breadcrumb');

    // Render breadcrumb
    const parts = prefix.split('.');
    breadcrumb.innerHTML = parts.map((p, i) => {
      const path = parts.slice(0, i + 1).join('.');
      return `<a href="#/${path}">${p}</a>`;
    }).join(' &gt; ');

    content.innerHTML = `
      <h1>${prefix}</h1>
      <h2>Namespaces</h2>
      <div class="members-list">
        ${namespaces.map(ns => `
          <div class="member-item">
            <a href="#/${ns.fullName}" class="type-link">${ns.fullName}</a>
          </div>
        `).join('')}
      </div>
    `;
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
    const definitionsHtml = await this.renderDefinitions(data);
    content.innerHTML = `
      <h1>${data.name}</h1>
      ${data.doc ? `<div class="doc">${this.renderDoc(data.doc)}</div>` : ''}
      ${definitionsHtml}
    `;
    this.highlightCode();
  },

  async renderDefinitions(data) {
    let html = '';

    // Group definitions by name
    const groups = this.groupByName(data);

    for (const [name, items] of groups) {
      html += `<div class="definition-group">`;

      for (const item of items) {
        html += await this.renderDefinition(item);
      }

      html += `</div>`;
    }

    // Render sections with content folded by default
    if (data.sections && data.sections.length > 0) {
      for (const sec of data.sections) {
        // Fetch full section data
        let sectionData = sec;
        try {
          sectionData = await this.fetchJson(`data/symbols/${sec.fullName}.json`);
        } catch (e) {
          // Use reference if fetch fails
        }

        const foldId = this.foldId++;
        const sectionContent = await this.renderDefinitions(sectionData);

        html += `
          <div class="definition section-definition" id="${sec.fullName}">
            <div class="definition-header foldable-header" onclick="app.toggleFold(${foldId})">
              <span class="fold-toggle" id="fold-toggle-${foldId}">▶</span>
              <span class="kind-badge kind-section">section</span>
              <span class="definition-name">${sec.name}</span>
              ${sectionData.source ? `<span class="source-link">${sectionData.source.file}:${sectionData.source.line}</span>` : ''}
            </div>
            <div class="fold-content" id="fold-content-${foldId}" style="display: none;">
              ${sectionData.doc ? `<div class="doc">${this.renderDoc(sectionData.doc)}</div>` : ''}
              ${sectionContent}
            </div>
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

  foldId: 0,

  toggleFold(id) {
    const content = document.getElementById('fold-content-' + id);
    const toggle = document.getElementById('fold-toggle-' + id);
    if (content && toggle) {
      const isHidden = content.style.display === 'none';
      content.style.display = isHidden ? 'block' : 'none';
      toggle.textContent = isHidden ? '▼' : '▶';
    }
  },

  async renderDefinition(item) {
    const kind = item._kind || item.kind || 'unknown';

    // Section with full data - render with nested content (foldable)
    if (kind === 'section' && item.source) {
      const foldId = this.foldId++;
      let html = `<div class="definition section-definition" id="${item.fullName}">`;
      html += `<div class="definition-header foldable-header" onclick="app.toggleFold(${foldId})">`;
      html += `<span class="fold-toggle" id="fold-toggle-${foldId}">▼</span>`;
      html += `<span class="kind-badge kind-${kind}">${kind}</span>`;
      html += `<span class="definition-name">${item.name}</span>`;
      html += `<span class="source-link">${item.source.file}:${item.source.line}</span>`;
      html += `</div>`;

      html += `<div class="fold-content" id="fold-content-${foldId}">`;
      if (item.doc) {
        html += `<div class="doc">${this.renderDoc(item.doc)}</div>`;
      }

      // Render section contents
      html += await this.renderDefinitions(item);
      html += `</div>`;
      html += `</div>`;
      return html;
    }

    // Section references only have name/fullName - render as a link (fallback)
    if (kind === 'section' && !item.source) {
      let html = `<div class="definition" id="${item.fullName}">`;
      html += `<div class="definition-header">`;
      html += `<span class="kind-badge kind-${kind}">${kind}</span>`;
      html += `<a href="#/${item.fullName}" class="definition-name type-link">${item.name}</a>`;
      html += `</div>`;
      html += `<div class="doc"><em>Click to view section contents</em></div>`;
      html += `</div>`;
      return html;
    }

    // Check if this is a class/interface/object with methods (foldable)
    const hasMembers = (item.methods && item.methods.length > 0) || (item.views && item.views.length > 0);
    const isClassLike = kind === 'class' || kind === 'interface' || kind === 'object';

    // Check if this is an infix function/pattern (has pre-params) - name shown in signature
    // Note: infix types show name in header with formatNameWithTypeParams, not hidden
    const isInfixFunc = item.params && item.params.some(p => p.position === 'prefix');

    let html = `<div class="definition" id="${item.fullName}">`;

    // Show type params for class, interface, object, type, abstract
    const showTypeParams = isClassLike || kind === 'type' || kind === 'abstract';
    const displayName = isInfixFunc ? '' : (showTypeParams ? this.formatNameWithTypeParams(item) : item.name);

    if (isClassLike && hasMembers) {
      const foldId = this.foldId++;
      html += `<div class="definition-header foldable-header" onclick="app.toggleFold(${foldId})">`;
      html += `<span class="fold-toggle" id="fold-toggle-${foldId}">▼</span>`;
      html += `<span class="kind-badge kind-${kind}">${kind}</span>`;
      if (displayName) html += `<span class="definition-name">${displayName}</span>`;
      if (item.source) {
        html += `<span class="source-link">${item.source.file}:${item.source.line}</span>`;
      }
      html += `</div>`;

      html += `<div class="fold-content" id="fold-content-${foldId}">`;
      // Signature
      html += `<div class="signature">${this.renderSignature(item, kind)}</div>`;

      // Doc
      if (item.doc) {
        html += `<div class="doc">${this.renderDoc(item.doc)}</div>`;
      }

      // Methods
      if (item.methods && item.methods.length > 0) {
        html += `<h3>Methods</h3>`;
        html += `<div class="members-list">`;
        for (const m of item.methods) {
          html += `
            <div class="member-item">
              <span class="kind-badge kind-method">method</span>
              <code>${m.name}${this.renderParams(m)}: ${this.renderType(m.returnType)}${this.renderReceives(m.receives)}</code>
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
    } else {
      // Non-foldable definition
      html += `<div class="definition-header">`;
      html += `<span class="kind-badge kind-${kind}">${kind}</span>`;
      if (displayName) html += `<span class="definition-name">${displayName}</span>`;
      if (item.source) {
        html += `<span class="source-link">${item.source.file}:${item.source.line}</span>`;
      }
      html += `</div>`;

      // Signature
      html += `<div class="signature">${this.renderSignature(item, kind)}</div>`;

      // Doc
      if (item.doc) {
        html += `<div class="doc">${this.renderDoc(item.doc)}</div>`;
      }

      // Methods for classes/interfaces without foldable (edge case)
      if (item.methods && item.methods.length > 0) {
        html += `<h3>Methods</h3>`;
        html += `<div class="members-list">`;
        for (const m of item.methods) {
          html += `
            <div class="member-item">
              <span class="kind-badge kind-method">method</span>
              <code>${m.name}${this.renderParams(m)}: ${this.renderType(m.returnType)}${this.renderReceives(m.receives)}</code>
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
    }

    html += `</div>`;
    return html;
  },

  renderSignature(item, kind) {
    let sig = '';

    // Type params for functions, methods, and patterns (not classes/types - those show in name)
    if ((kind === 'function' || kind === 'method' || kind === 'pattern') && item.typeParams && item.typeParams.length > 0) {
      sig += `[${item.typeParams.join(', ')}]`;
    }

    // Constructor/params - use hasOwnProperty to avoid JS built-in constructor
    const ctor = Object.prototype.hasOwnProperty.call(item, 'constructor') ? item.constructor : null;
    if (ctor && ctor.params && ctor.params.length > 0) {
      sig += `(${ctor.params.map(p => `${p.name}: ${this.renderType(p.type)}`).join(', ')})`;
      // Constructor auto params
      if (ctor.autoParams && ctor.autoParams.length > 0) {
        sig += `(<span class="keyword-auto">auto</span> ${ctor.autoParams.map(p => this.renderAutoParam(p)).join(', ')})`;
      }
      // Constructor receives
      sig += this.renderReceives(ctor.receives);
    } else if (ctor && ctor.autoParams && ctor.autoParams.length > 0) {
      // Constructor with only auto params (no regular params)
      sig += `(<span class="keyword-auto">auto</span> ${ctor.autoParams.map(p => this.renderAutoParam(p)).join(', ')})`;
      // Constructor receives
      sig += this.renderReceives(ctor.receives);
    } else if (ctor && ctor.receives && ctor.receives.length > 0) {
      // Constructor with only receives (no params or auto params)
      sig += this.renderReceives(ctor.receives);
    } else if (item.params || item.autoParams) {
      // Check for infix style (has pre-parameters)
      const preParams = (item.params || []).filter(p => p.position === 'prefix');
      const postParams = (item.params || []).filter(p => p.position !== 'prefix');

      if (preParams.length > 0) {
        // Infix style: (pre-params) name (post-params)(auto params)
        sig += `(${preParams.map(p => `${p.name}: ${this.renderType(p.type)}`).join(', ')})`;
        sig += ` <strong>${item.name}</strong>`;
        if (postParams.length > 0) {
          sig += ` (${postParams.map(p => `${p.name}: ${this.renderType(p.type)}`).join(', ')})`;
        }
        if (item.autoParams && item.autoParams.length > 0) {
          sig += `(<span class="keyword-auto">auto</span> ${item.autoParams.map(p => this.renderAutoParam(p)).join(', ')})`;
        }
      } else {
        sig += this.renderParams(item);
      }
    }

    // Return type
    if (item.returnType) {
      sig += `: ${this.renderType(item.returnType)}`;
    }

    // Receives (context parameters) - only for functions and methods
    if (kind === 'function' || kind === 'method') {
      sig += this.renderReceives(item.receives);
    }

    // Alias - name with type params shown in header, signature just shows = aliasOf
    if (item.aliasOf) {
      sig = ` = ${this.renderType(item.aliasOf)}`;
    }

    // Cases for unions - name with type params shown in header, signature just shows = cases
    if (item.cases) {
      const casesStr = item.cases.map(c => {
        const nameLink = `<a href="#/${c.fullName}" class="type-link">${c.name}</a>`;
        return c.fields.length === 0 ? nameLink : `${nameLink}(${c.fields.map(f => `${f.name}: ${this.renderType(f.type)}`).join(', ')})`;
      }).join(' | ');
      sig = ` = ${casesStr}`;
    }

    // Context parameter type
    if (item._kind === 'context' && item.type) {
      sig = `: ${this.renderType(item.type)}`;
    }

    return sig;
  },

  renderParams(item) {
    let result = '';

    // Regular params block
    if (item.params && item.params.length > 0) {
      result += `(${item.params.map(p => `${p.name}: ${this.renderType(p.type)}`).join(', ')})`;
    }

    // Auto params block with 'auto' keyword at the beginning
    if (item.autoParams && item.autoParams.length > 0) {
      result += `(<span class="keyword-auto">auto</span> ${item.autoParams.map(p => this.renderAutoParam(p)).join(', ')})`;
    }

    return result;
  },

  renderAutoParam(p) {
    let result = `${p.name}: ${this.renderType(p.type)}`;
    if (p.candidates && p.candidates.length > 0) {
      const candsStr = p.candidates.map(c => {
        if (c.kind === 'symbol') {
          return `<a href="#/${c.fullName}" class="type-link">${c.name}</a>`;
        } else if (c.kind === 'member') {
          return `[${this.renderType(c.type)}].${c.name}`;
        }
        return '?';
      }).join(', ');
      result += ` <span class="keyword-like">with</span> [${candsStr}]`;
    }
    return result;
  },

  renderReceives(receives) {
    if (receives.length === 0) {
      return ` <span class="keyword-receives">receives</span> <span class="keyword-receives">none</span>`;
    }
    const receivesStr = receives.map(r =>
      `<a href="#/${r.fullName}" class="type-link">${r.name}</a>`
    ).join(', ');
    return ` <span class="keyword-receives">receives</span> ${receivesStr}`;
  },

  renderType(type) {
    if (!type) return '?';

    switch (type.kind) {
      case 'ref':
        return `<a href="#/${type.name}" class="type-link">${this.shortName(type.name)}</a>`;

      case 'tparam':
        // Type parameters are not clickable
        return type.name;

      case 'applied':
        if (type.preCount && type.preCount > 0) {
          // Infix type: preArgs Name postArgs -> preArg Name postArg
          const preArgs = type.args.slice(0, type.preCount).map(a => this.renderType(a)).join(', ');
          const postArgs = type.args.slice(type.preCount).map(a => this.renderType(a)).join(', ');
          const name = `<a href="#/${type.name}" class="type-link">${this.shortName(type.name)}</a>`;
          return `${preArgs} ${name} ${postArgs}`;
        } else {
          const args = type.args.map(a => this.renderType(a)).join(', ');
          return `<a href="#/${type.name}" class="type-link">${this.shortName(type.name)}</a>[${args}]`;
        }

      case 'fun':
        const params = type.params.map(p => this.renderType(p));
        const paramStr = params.length === 1 ? params[0] : `(${params.join(', ')})`;
        return `${paramStr} =&gt; ${this.renderType(type.result)}`;

      case 'tuple':
        return `(${type.elements.map(e => this.renderType(e)).join(', ')})`;

      case 'union':
        return type.branches.map(b => this.renderType(b)).join(' | ');

      case 'literal':
        return String(type.value);

      case 'vararg':
        return `..${this.renderType(type.element)}`;

      case 'duck':
        const base = this.renderType(type.base);
        const adapters = type.adapters.map(a => {
          if (a.kind === 'function') {
            return `<a href="#/${a.fullName}" class="type-link">${a.name}</a>`;
          } else {
            return a.name;
          }
        }).join(', ');
        return `<span class="keyword-like">like</span> ${base} <span class="keyword-like">with</span> [${adapters}]`;

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
    return marked.parse(doc);
  },

  // Apply syntax highlighting to code blocks
  highlightCode() {
    document.querySelectorAll('pre code').forEach((block) => {
      // Check if this looks like Jo code and set language
      const content = block.textContent;
      if (!block.className.includes('language-') &&
          (content.includes('def ') || content.includes('data ') || content.includes('class '))) {
        block.classList.add('language-jo');
      }
      hljs.highlightElement(block);
    });
  },

  async renderMember(data, targetPath) {
    const content = document.getElementById('main-content');

    // Find ALL members with this fullName (may have multiple kinds)
    let results = this.findAllMembers(data, targetPath);

    // If not found at top level, search inside sections (nested paths)
    if (results.length === 0) {
      const nestedResults = await this.findNestedMember(data, targetPath);
      if (nestedResults) {
        results = nestedResults;
      }
    }

    if (results.length === 0) {
      this.renderNotFound(targetPath);
      return;
    }

    // For section references, fetch the full section data
    for (let i = 0; i < results.length; i++) {
      const result = results[i];
      if (result.kind === 'section' && !result.member.source) {
        // This is a section reference, fetch full data
        try {
          const sectionData = await this.fetchJson(`data/symbols/${result.member.fullName}.json`);
          results[i] = { member: sectionData, kind: 'section' };
        } catch (e) {
          // Keep the reference if fetch fails
        }
      }
    }

    // Render all definitions with this name
    const name = this.shortName(targetPath);
    let html = `<h1>${name}</h1>`;
    html += `<div class="definition-group">`;
    for (const result of results) {
      html += await this.renderDefinition({ ...result.member, _kind: result.kind });
    }
    html += `</div>`;
    content.innerHTML = html;
    this.highlightCode();
  },

  findAllMembers(data, fullName) {
    const results = [];

    // Helper to search in a list
    const searchList = (list, kind) => {
      if (!list) return;
      for (const item of list) {
        if (item.fullName === fullName) {
          results.push({ member: item, kind: item.kind || kind });
        }
      }
    };

    // Search in top-level lists
    searchList(data.types, 'type');
    searchList(data.functions, 'function');
    searchList(data.patterns, 'pattern');
    searchList(data.objects, 'object');
    searchList(data.contexts, 'context');

    // Search in sections (now just references with name/fullName)
    if (data.sections) {
      for (const sec of data.sections) {
        if (sec.fullName === fullName) {
          results.push({ member: sec, kind: 'section' });
        }
      }
    }

    return results;
  },

  // Find members inside a section (for nested paths like jo.List.ListImpl.Repr)
  async findNestedMember(nsData, fullName) {
    // Check if fullName could be inside a section
    if (!nsData.sections) return null;

    for (const sec of nsData.sections) {
      if (fullName.startsWith(sec.fullName + '.')) {
        // This path is inside this section, fetch section data
        try {
          const sectionData = await this.fetchJson(`data/symbols/${sec.fullName}.json`);
          // Search recursively in section
          const results = this.findAllMembers(sectionData, fullName);
          if (results.length > 0) return results;
          // Also check nested sections
          const nested = await this.findNestedMember(sectionData, fullName);
          if (nested) return nested;
        } catch (e) {
          // Section data not available
        }
      }
    }
    return null;
  },

  renderNotFound(path) {
    const content = document.getElementById('main-content');
    content.innerHTML = `<h1>Not Found</h1><p>The path "${path}" was not found.</p>`;
  }
};

// Initialize app
document.addEventListener('DOMContentLoaded', () => app.init());

# Jo Standard Library

Jo ships **four libraries**, and this bundle documents all of them. They are not
peers: one is confined, and three are not. Which is which decides what a program
built on them is able to do. Everything under `jo` except `jo.py`, `jo.rb` and
`jo.js` is the standard library.

<svg viewBox="0 0 760 360" role="img" width="100%"
     aria-label="A wall separates two worlds. On the green confined side sits the jo standard library, which has no FFI, no ambient authority and cannot originate an effect; confinement is transitive, so confined code may depend only on confined code. On the orange trusted side, the jo.py, jo.rb and jo.js runtime libraries reach the host platform: filesystem, network, processes and any module. The only opening in the wall is a gate through which capabilities pass into confined code at link time. A direct route from confined code to the host is blocked."
     style="max-width:760px;color:currentColor">
  <defs>
    <marker id="cap-arrow" viewBox="0 0 10 10" refX="9" refY="5"
            markerWidth="7" markerHeight="7" orient="auto-start-reverse">
      <path d="M 0 0 L 10 5 L 0 10 z" fill="currentColor"/>
    </marker>
    <marker id="dep-arrow" viewBox="0 0 10 10" refX="9" refY="5"
            markerWidth="6" markerHeight="6" orient="auto-start-reverse">
      <path d="M 0 0 L 10 5 L 0 10 z" fill="currentColor" fill-opacity="0.6"/>
    </marker>
  </defs>
  <rect x="8" y="44" width="292" height="272" rx="10"
        fill="#16a34a" fill-opacity="0.10" stroke="#16a34a" stroke-opacity="0.55"/>
  <text x="26" y="70" font-size="12" font-weight="700" letter-spacing="1.4"
        fill="#16a34a">CONFINED</text>
  <rect x="28" y="88" width="252" height="196" rx="9"
        fill="#16a34a" fill-opacity="0.18" stroke="#16a34a" stroke-opacity="0.7"/>
  <text x="46" y="144" font-size="34" font-weight="700" fill="currentColor">jo</text>
  <text x="46" y="170" font-size="14" fill="currentColor" fill-opacity="0.8">the standard library</text>
  <line x1="46" y1="188" x2="262" y2="188" stroke="#16a34a" stroke-opacity="0.45" stroke-width="1"/>
  <text x="46" y="214" font-size="12" fill="currentColor" fill-opacity="0.75">no FFI</text>
  <text x="46" y="236" font-size="12" fill="currentColor" fill-opacity="0.75">no ambient authority</text>
  <text x="46" y="258" font-size="12" fill="currentColor" fill-opacity="0.75">cannot originate an effect</text>
  <text x="28" y="306" font-size="10" fill="#16a34a" fill-opacity="0.95">transitive: confined code may depend only on confined code</text>
  <rect x="356" y="36" width="8" height="134" rx="2" fill="currentColor" fill-opacity="0.38"/>
  <rect x="356" y="214" width="8" height="112" rx="2" fill="currentColor" fill-opacity="0.38"/>
  <line x1="416" y1="192" x2="284" y2="192" stroke="currentColor" stroke-opacity="0.8"
        stroke-width="1.8" marker-end="url(#cap-arrow)"/>
  <text x="360" y="180" font-size="10" text-anchor="middle" font-weight="700"
        fill="currentColor" fill-opacity="0.85">capabilities</text>
  <text x="360" y="209" font-size="10" text-anchor="middle"
        fill="currentColor" fill-opacity="0.6">at link time</text>
  <line x1="302" y1="264" x2="346" y2="264" stroke="#dc2626" stroke-opacity="0.5"
        stroke-width="1.4" stroke-dasharray="4 4"/>
  <line x1="353" y1="257" x2="367" y2="271" stroke="#dc2626" stroke-width="2.6" stroke-linecap="round"/>
  <line x1="367" y1="257" x2="353" y2="271" stroke="#dc2626" stroke-width="2.6" stroke-linecap="round"/>
  <text x="360" y="292" font-size="10" text-anchor="middle" fill="#dc2626">no direct route</text>
  <rect x="420" y="44" width="332" height="272" rx="10"
        fill="#ea580c" fill-opacity="0.10" stroke="#ea580c" stroke-opacity="0.55" stroke-dasharray="5 4"/>
  <text x="438" y="70" font-size="12" font-weight="700" letter-spacing="1.4"
        fill="#ea580c">TRUSTED</text>
  <rect x="440" y="86" width="292" height="36" rx="7"
        fill="#ea580c" fill-opacity="0.16" stroke="#ea580c" stroke-opacity="0.6"/>
  <text x="456" y="109" font-size="13" fill="currentColor"><tspan font-weight="700">jo.py</tspan> — Python interop</text>
  <rect x="440" y="128" width="292" height="36" rx="7"
        fill="#ea580c" fill-opacity="0.16" stroke="#ea580c" stroke-opacity="0.6"/>
  <text x="456" y="151" font-size="13" fill="currentColor"><tspan font-weight="700">jo.rb</tspan> — Ruby interop</text>
  <rect x="440" y="170" width="292" height="36" rx="7"
        fill="#ea580c" fill-opacity="0.16" stroke="#ea580c" stroke-opacity="0.6"/>
  <text x="456" y="193" font-size="13" fill="currentColor"><tspan font-weight="700">jo.js</tspan> — JavaScript interop</text>
  <line x1="586" y1="206" x2="586" y2="230" stroke="currentColor" stroke-opacity="0.55"
        stroke-width="1.4" marker-end="url(#dep-arrow)"/>
  <rect x="440" y="234" width="292" height="60" rx="8"
        fill="#ea580c" fill-opacity="0.07" stroke="#ea580c" stroke-opacity="0.45" stroke-dasharray="4 3"/>
  <text x="456" y="258" font-size="13" font-weight="700" fill="currentColor" fill-opacity="0.9">the host platform</text>
  <text x="456" y="278" font-size="11" fill="currentColor" fill-opacity="0.7">filesystem · network · processes · any module</text>
  <text x="380" y="342" font-size="11" text-anchor="middle"
        fill="currentColor" fill-opacity="0.7">a runtime library is reachable only under --use-runtime-api</text>
</svg>

## The four libraries

The standard library spans five namespaces; each runtime API is a single one.

| Library | Namespaces | Status |
| --- | --- | --- |
| Standard library | [`jo`](#/jo) · [`jo.mutable`](#/jo.mutable) · [`jo.regex`](#/jo.regex) · [`jo.resource`](#/jo.resource) · [`jo.compile`](#/jo.compile) | Stabilizing |
| Python interop | [`jo.py`](#/jo.py) | Stabilizing |
| Ruby interop | [`jo.rb`](#/jo.rb) | Experimental |
| JavaScript interop | [`jo.js`](#/jo.js) | Experimental |

*Stabilizing* means the shape is settling: breaking changes are not intended, but
expect some to slip through. *Experimental* means the API may change at any time.

## The standard library is confined

The standard library contains no FFI, and it cannot originate a side effect.
Everything defined here is computation over values.

Effects reach it as capabilities. `jo.IO` *declares* them rather than
implementing them:

```jo
param stdout: String => Unit
```

`println` writes to standard output only because something outside the standard
library supplied that `stdout`. Code that is never given the capability cannot
reach standard output at all — not by convention, but because the compiler
rejects the attempt.

Confinement is **transitive**, and that is what makes it worth anything. A
confined library may depend only on other confined libraries, so the property
holds across the whole dependency graph rather than at its top. Import anything
that transitively reaches a trusted library and compilation fails with a type
error — so no dependency, however deep, can quietly widen what confined code is
able to do.

So every real side effect a Jo program performs originates in the trusted world,
where FFI is available, and crosses into confined code as a capability resolved
at link time. See
[Two-World Architecture](https://jo-lang.org/security/two-worlds) for how that
boundary is enforced.

## The runtime libraries are trusted

[`jo.py`](#/jo.py), [`jo.rb`](#/jo.rb) and [`jo.js`](#/jo.js) are the trusted
side of that boundary. *Trusted* is a classification, not a reassurance: it means
this code is able to break the guarantees the confined world rests on, so it has
to be code you are willing to vouch for.

They exist to *build* capabilities, by calling the host platform directly.
`py.module` imports any Python module, `subprocess` included; `rb.require` loads
any Ruby library; `js.global` reaches the whole JavaScript environment.

None of it is reachable unless the compilation asks for it:

```sh
jo compile --python app.jo --use-runtime-api python
```

Without that flag the names do not resolve — the guarantee is enforced by the
compiler, not by which pages you read. The flag names a single backend, so one
compilation can reach at most one of the three.

**Do not enable a runtime API when compiling untrusted code.** It hands that code
the host platform and voids the guarantees the rest of this documentation
describes.

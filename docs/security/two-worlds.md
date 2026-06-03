# Two-World Architecture

Jo's security guarantee rests on a hard separation between two compilation worlds.
Code in the **confined world** is type-checked against confined libraries only and
cannot access FFI. Code in the **trusted world** may use FFI and provides the
capability objects that cross the boundary.

## Structure

<svg viewBox="0 0 780 370" xmlns="http://www.w3.org/2000/svg" style="max-width:100%;font-family:system-ui,sans-serif">
  <defs>
    <marker id="arr" markerWidth="8" markerHeight="6" refX="7" refY="3" orient="auto">
      <path d="M0,0 L8,3 L0,6 Z" fill="#888"/>
    </marker>
    <marker id="arr-b" markerWidth="8" markerHeight="6" refX="7" refY="3" orient="auto">
      <path d="M0,0 L8,3 L0,6 Z" fill="#4a6cf7"/>
    </marker>
    <marker id="arr-o" markerWidth="8" markerHeight="6" refX="7" refY="3" orient="auto">
      <path d="M0,0 L8,3 L0,6 Z" fill="#c45b00"/>
    </marker>
  </defs>

  <!-- Confined World: x=10, width=305, center=162 -->
  <rect x="10" y="10" width="305" height="350" rx="10" fill="#eef2ff" stroke="#4a6cf7" stroke-width="2"/>
  <text x="162" y="38" text-anchor="middle" font-size="14" font-weight="700" fill="#4a6cf7">CONFINED WORLD</text>
  <text x="162" y="56" text-anchor="middle" font-size="11" fill="#6677bb">no FFI · confined libs only</text>

  <!-- Confined: Jo Stdlib -->
  <rect x="30" y="70" width="265" height="60" rx="7" fill="white" stroke="#b0bdf7" stroke-width="1.5"/>
  <text x="162" y="96" text-anchor="middle" font-size="13" font-weight="600" fill="#333">Jo Standard Library</text>
  <text x="162" y="116" text-anchor="middle" font-size="11" fill="#777">List · Map · Option · Result · …</text>

  <!-- Confined: Interface Library -->
  <rect x="30" y="170" width="265" height="72" rx="7" fill="white" stroke="#b0bdf7" stroke-width="1.5"/>
  <text x="162" y="196" text-anchor="middle" font-size="13" font-weight="600" fill="#333">Interface Library</text>
  <text x="162" y="214" text-anchor="middle" font-size="11" fill="#555" font-family="monospace">interface OrdersApi { … }</text>
  <text x="162" y="232" text-anchor="middle" font-size="11" fill="#555" font-family="monospace">defer def aiMain(): Unit</text>

  <!-- Confined: AI Code -->
  <rect x="30" y="283" width="265" height="58" rx="7" fill="white" stroke="#b0bdf7" stroke-width="1.5"/>
  <text x="162" y="308" text-anchor="middle" font-size="13" font-weight="600" fill="#333">AI-Generated Code</text>
  <text x="162" y="327" text-anchor="middle" font-size="11" fill="#555" font-family="monospace">aiMain() implements contract</text>

  <!-- Arrows within confined: upward (dependent → dependency) -->
  <line x1="162" y1="170" x2="162" y2="130" stroke="#4a6cf7" stroke-width="1.5" marker-end="url(#arr-b)"/>
  <text x="172" y="153" font-size="10" fill="#4a6cf7">depends on</text>
  <line x1="162" y1="283" x2="162" y2="242" stroke="#4a6cf7" stroke-width="1.5" marker-end="url(#arr-b)"/>
  <text x="172" y="265" font-size="10" fill="#4a6cf7">depends on</text>

  <!-- Trusted World: x=465, width=305, center=617 -->
  <rect x="465" y="10" width="305" height="350" rx="10" fill="#fff4ee" stroke="#c45b00" stroke-width="2"/>
  <text x="617" y="38" text-anchor="middle" font-size="14" font-weight="700" fill="#c45b00">TRUSTED WORLD</text>
  <text x="617" y="56" text-anchor="middle" font-size="11" fill="#b07050">FFI enabled · audited</text>

  <!-- Trusted: Platform Runtime -->
  <rect x="485" y="70" width="265" height="60" rx="7" fill="white" stroke="#f0b090" stroke-width="1.5"/>
  <text x="617" y="96" text-anchor="middle" font-size="13" font-weight="600" fill="#333">Platform Runtime</text>
  <text x="617" y="116" text-anchor="middle" font-size="11" fill="#777">FFI · syscalls · network · filesystem</text>

  <!-- Trusted: Harness -->
  <rect x="485" y="170" width="265" height="72" rx="7" fill="white" stroke="#f0b090" stroke-width="1.5"/>
  <text x="617" y="196" text-anchor="middle" font-size="13" font-weight="600" fill="#333">Harness</text>
  <text x="617" y="214" text-anchor="middle" font-size="11" fill="#555" font-family="monospace">UserScopedOrders(userId, db)</text>
  <text x="617" y="232" text-anchor="middle" font-size="11" fill="#555" font-family="monospace">frameworkMain()</text>

  <!-- Arrow within trusted: upward -->
  <line x1="617" y1="170" x2="617" y2="130" stroke="#c45b00" stroke-width="1.5" marker-end="url(#arr-o)"/>
  <text x="627" y="153" font-size="10" fill="#c45b00">depends on</text>

  <!-- Harness depends on Interface Library: box-to-box, orange -->
  <line x1="485" y1="206" x2="295" y2="206" stroke="#c45b00" stroke-width="1.5" marker-end="url(#arr-o)"/>
  <text x="390" y="199" text-anchor="middle" font-size="10" fill="#c45b00">depends on</text>
  <!-- --link: Harness → AI-Generated Code (diagonal, link time after type checking) -->
  <line x1="485" y1="235" x2="295" y2="290" stroke="#555" stroke-width="2" stroke-dasharray="6,3" marker-end="url(#arr)"/>
  <text x="390" y="282" text-anchor="middle" font-size="11" font-weight="700" fill="#555">--link</text>
</svg>

The `--link` connection is resolved at **link time, after type checking**. There are no
dynamic imports, no reflection, and no runtime class loading across the boundary.

## Information Flow at Runtime

<svg viewBox="0 0 780 280" xmlns="http://www.w3.org/2000/svg" style="max-width:100%;font-family:system-ui,sans-serif">
  <defs>
    <marker id="fw" markerWidth="8" markerHeight="6" refX="7" refY="3" orient="auto">
      <path d="M0,0 L8,3 L0,6 Z" fill="#c45b00"/>
    </marker>
    <marker id="bk" markerWidth="8" markerHeight="6" refX="7" refY="3" orient="auto">
      <path d="M0,0 L8,3 L0,6 Z" fill="#4a6cf7"/>
    </marker>
  </defs>

  <!-- Swimlane headers -->
  <rect x="0" y="0" width="260" height="40" fill="#fff4ee" rx="0"/>
  <text x="130" y="26" text-anchor="middle" font-size="13" font-weight="700" fill="#c45b00">Framework (Trusted)</text>

  <rect x="260" y="0" width="260" height="40" fill="#eef2ff" rx="0"/>
  <text x="390" y="26" text-anchor="middle" font-size="13" font-weight="700" fill="#4a6cf7">aiMain() (Confined)</text>

  <rect x="520" y="0" width="260" height="40" fill="#f5f5f5" rx="0"/>
  <text x="650" y="26" text-anchor="middle" font-size="13" font-weight="700" fill="#555">Database</text>

  <!-- Swimlane dividers -->
  <line x1="260" y1="0" x2="260" y2="280" stroke="#ddd" stroke-width="1"/>
  <line x1="520" y1="0" x2="520" y2="280" stroke="#ddd" stroke-width="1"/>

  <!-- Lifelines -->
  <line x1="130" y1="40" x2="130" y2="280" stroke="#c45b00" stroke-width="1" stroke-dasharray="4,4" opacity="0.5"/>
  <line x1="390" y1="40" x2="390" y2="280" stroke="#4a6cf7" stroke-width="1" stroke-dasharray="4,4" opacity="0.5"/>
  <line x1="650" y1="40" x2="650" y2="280" stroke="#999" stroke-width="1" stroke-dasharray="4,4" opacity="0.5"/>

  <!-- Step 1: Attenuate -->
  <rect x="30" y="55" width="200" height="32" rx="5" fill="white" stroke="#f0b090" stroke-width="1.5"/>
  <text x="130" y="76" text-anchor="middle" font-size="11" fill="#555">new UserScopedOrders(userId, db)</text>

  <!-- Step 2: Invoke aiMain -->
  <line x1="130" y1="110" x2="390" y2="110" stroke="#c45b00" stroke-width="1.5" marker-end="url(#fw)"/>
  <text x="260" y="105" text-anchor="middle" font-size="10" fill="#c45b00">aiMain(ordersApi=api, IO.stdout=buf)</text>
  <text x="260" y="123" text-anchor="middle" font-size="10" fill="#aaa">(allow none enforced)</text>

  <!-- Step 3: ordersApi.query -->
  <line x1="390" y1="150" x2="130" y2="150" stroke="#4a6cf7" stroke-width="1.5" marker-end="url(#bk)"/>
  <text x="260" y="144" text-anchor="middle" font-size="10" fill="#4a6cf7">ordersApi.query(30)</text>
  <text x="390" y="168" text-anchor="middle" font-size="10" fill="#999" font-style="italic">cannot see userId or db</text>

  <!-- Step 4: DB query -->
  <line x1="130" y1="190" x2="650" y2="190" stroke="#c45b00" stroke-width="1.5" marker-end="url(#fw)"/>
  <text x="390" y="184" text-anchor="middle" font-size="10" fill="#c45b00">SELECT … WHERE user_id = {userId}</text>

  <!-- Step 5: Return rows -->
  <line x1="650" y1="215" x2="130" y2="215" stroke="#888" stroke-width="1" stroke-dasharray="5,2" marker-end="url(#bk)"/>
  <text x="390" y="209" text-anchor="middle" font-size="10" fill="#888">rows</text>

  <!-- Step 6: Return to AI -->
  <line x1="130" y1="245" x2="390" y2="245" stroke="#888" stroke-width="1" stroke-dasharray="5,2" marker-end="url(#fw)"/>
  <text x="260" y="239" text-anchor="middle" font-size="10" fill="#888">List[Order] (this user's rows only)</text>
</svg>

## The Transitive Closure Property

A confined library may only depend on other confined libraries. This is enforced at
compile time: if `ai_code.jo` tries to import anything that transitively reaches a
trusted library, compilation fails with a type error.

This means the boundary is not just between the AI code and the framework — it extends
to every library the AI code uses. Adding a new confined library cannot accidentally
introduce a trusted dependency.

## The `defer def` Contract

The `defer def` declaration is the precise contract across the boundary:

```jo
// In the API library (confined)
defer def aiMain(): Unit receives ordersApi, IO.stdout
```

This declares:
- **What** untrusted code must implement (`aiMain`)
- **Which capabilities** the implementation may use (`ordersApi`, `IO.stdout`)
- **Nothing else** — the implementation cannot declare additional `receives`

The framework verifies this signature at link time. Any deviation is a compile error.

## What the Compiler Guarantees

| Property | Mechanism |
|---|---|
| Cannot access FFI | Confined compilation mode rejects FFI symbols |
| Cannot see security context | `userId`, `db` are in the trusted world — not reachable from confined code |
| Cannot amplify capabilities | No reflection, no downcasting across the boundary |
| Uses only declared capabilities | `defer def` signature + `allow none` at the call site |
| Cannot import trusted code | Transitive closure check at compile time |

No runtime sandboxing is required. The guarantees are structural.

## See Also

- [Jo's Solution](solution.md) — How the two worlds address each security challenge

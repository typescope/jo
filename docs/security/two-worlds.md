# Two-World Architecture

Jo's security guarantee rests on a hard separation between two compilation worlds.
Code in the **confined world** is type-checked against confined libraries only and
cannot access FFI. Code in the **trusted world** may use FFI and provides the
capability objects that cross the boundary.

## Structure

The confined world contains the libraries that AI code can depend on — no FFI, no system access. The trusted world contains platform code with full FFI access. Capabilities cross the boundary at link time, flowing from the trusted to the confined world.

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

::: info Transitive closure
A confined library may only depend on other confined libraries. If `ai_code.jo` tries to import anything that transitively reaches a trusted library, compilation fails with a type error. The boundary extends to every library the AI code uses — adding a new confined library cannot accidentally introduce a trusted dependency.
:::

## Information Flow at Runtime

At runtime the harness constructs an attenuated capability, invokes confined code with only what the `defer def` declared, and the user-scoping is applied transparently before any database access:

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

## Linking Mechanism

`defer def` and `--link` work together to cross the boundary at compile time.

**`defer def` — the contract (in the interface library, confined):**

```jo
defer def aiMain(): Unit receives ordersApi, IO.stdout
```

This fixes the contract: what the function is named, what capabilities it may use, and nothing more. AI-generated code cannot declare additional `receives` beyond what the `defer def` permits.

**AI-generated code fulfills the contract (also confined):**

```jo
def aiMain(): Unit receives ordersApi, IO.stdout =
  val data = ordersApi.query(30)
  summarize(data)
```

**`--link` wires the implementation at compile time (harness build step):**

```bash
bin/jo compile --python harness.jo --lib lib/ \
  --link Framework.aiMain=MyAI.aiMain \
  -o app.py
```

The compiler verifies that the linked function's signature exactly matches the `defer def` declaration — wrong type, wrong capabilities, or missing implementation are all compile errors. There is no runtime wiring, no dynamic dispatch, and no possibility of substituting a different implementation after the build.

## Complete Example

The following brings all three parts together in one listing, annotated to show which world each section belongs to:

```jo
//------------------ Interface Library (Confined) ---------------------------
class Order(...)

interface OrdersApi                        // (1)
  def query(lastDays: Int): List[Order]
end

param ordersApi: OrdersApi

defer def aiMain(): Unit receives ordersApi, IO.stdout  // (2)

//------------------ Harness (Trusted) --------------------------------------
class UserScopedOrders(userId: Int, db: Database)    // (3)
  def query(lastDays: Int): List[Order] =
    db.query("SELECT * FROM orders WHERE user_id = ? AND date > CURRENT_DATE - ?", userId, lastDays)

  view OrdersApi
end

def frameworkMain() =
  val db = connect("orders.db")
  val userId = currentUser()
  val restricted = new UserScopedOrders(userId, db)  // (4)

  val output: mutable.List[String] = []
  val buffer = (s: String) => output += s

  allow none in // (5)
    with ordersApi = restricted, IO.stdout = buffer in aiMain()

//------------------ AI-Generated Code (Confined) ---------------------------
def aiMain(): Unit receives ordersApi, IO.stdout =   // (6)
  val data = ordersApi.query(30)
  summarize(data)
```

1. The only capability interface visible to AI code. The interface library is compiled without FFI support.
2. The `defer def` contract: declares what AI code must implement and which capabilities it may use.
3. Trusted implementation captures `userId` — untrusted code cannot access or inspect it.
4. Capability attenuated: full DB access → user-scoped, read-only.
5. `allow none` proves at compile time that `aiMain()` uses no capabilities beyond `ordersApi` and `IO.stdout`.
6. AI-generated code is type-checked against the interface library only, then linked with the harness.

## See Also

- [The Security Problem](security-problem.md) — The three challenges this architecture addresses
- [Language Design](language-design.md) — The language facilities that operate within this architecture

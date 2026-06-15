---
title: Why Secure AI Needs Compile-Time Sandboxing
date: 2026-06-11
author: The Jo Team
description: Runtime sandboxes confine processes; compile-time sandboxing confines code. For AI-generated code, the boundary must understand business logic — and that is a job for the type checker.
---

# Why Secure AI Needs Compile-Time Sandboxing

*June 11, 2026 · The Jo Team*

AI agents are becoming more capable at automating tasks by generating and
executing code. But AI agents cannot be fully trusted. Earlier this year,
OpenClaw
[deleted hundreds of emails](https://x.com/summeryue0/status/2025774069124399363) from the inbox
of Meta Superintelligence Labs' alignment director.

Until an agent's actions are strictly bounded, we cannot use it on
critical infrastructure or with sensitive data. What exactly is the
untrusted code allowed to do — and who checked?

The standard answer is runtime sandboxing: containers, seccomp filters, VMs.
But they operate at the wrong level of
abstraction. A container can stop a process from opening a socket; it cannot
stop untrusted code from querying *another user's* rows through a database
connection it legitimately holds.

## What Is Compile-Time Sandboxing?

<img src="/img/compile-time-sandboxing.svg" alt="Compile-time sandboxing = API gating in the language. A confined function has no ambient authority — reflection, globals, network, files, type casts, and control effects are all rejected by the compiler — while its typed parameters are the only door to the outside world." style="display:block;margin:1.5rem auto;width:100%;height:auto" />

Function parameters (capabilities) are the only door to the outside world.

## Runtime vs. Compile-Time Sandboxing

<style>
.sbx-cmp { border: 1px solid var(--vp-c-divider); border-radius: 12px; overflow: hidden; margin: 24px 0; font-size: 14px; line-height: 1.5; }
.sbx-cmp .sbx-head, .sbx-cmp .sbx-row { display: grid; grid-template-columns: 1fr 1fr; }
.sbx-cmp .sbx-cell { padding: 16px 20px; }
.sbx-cmp .sbx-cell:first-child { border-right: 1px solid var(--vp-c-divider); }
.sbx-cmp .sbx-row .sbx-cell { border-top: 1px solid var(--vp-c-divider); }
.sbx-cmp .sbx-ct { background: var(--vp-c-brand-soft); }
.sbx-cmp .sbx-head .sbx-title { font-weight: 700; font-size: 16px; color: var(--vp-c-text-1); display: flex; align-items: center; gap: 8px; }
.sbx-cmp .sbx-head .sbx-ct .sbx-title { color: var(--vp-c-brand-1); }
.sbx-cmp .sbx-sub { font-size: 13px; color: var(--vp-c-text-2); margin-top: 2px; }
.sbx-cmp .sbx-item { font-weight: 600; color: var(--vp-c-text-1); display: flex; align-items: center; gap: 8px; }
.sbx-cmp .sbx-desc { color: var(--vp-c-text-2); margin-top: 4px; padding-left: 26px; }
.sbx-cmp .sbx-ico { flex: none; width: 18px; height: 18px; border-radius: 50%; display: inline-flex; align-items: center; justify-content: center; font-size: 11px; font-weight: 700; }
.sbx-cmp .sbx-no { color: var(--vp-c-red-1); background: var(--vp-c-red-soft); }
.sbx-cmp .sbx-yes { color: var(--vp-c-green-1); background: var(--vp-c-green-soft); }
.sbx-cmp .sbx-badge { font-size: 11px; font-weight: 600; padding: 1px 8px; border-radius: 4px; color: var(--vp-c-brand-1); border: 1px solid var(--vp-c-brand-1); margin-left: auto; }
@media (max-width: 599px) {
  .sbx-cmp .sbx-head, .sbx-cmp .sbx-row { grid-template-columns: 1fr; }
  .sbx-cmp .sbx-cell:first-child { border-right: none; }
  .sbx-cmp .sbx-head .sbx-cell + .sbx-cell { border-top: 1px solid var(--vp-c-divider); }
}
</style>

<div class="sbx-cmp">
  <div class="sbx-head">
    <div class="sbx-cell">
      <div class="sbx-title">Runtime sandboxing</div>
      <div class="sbx-sub">Enforced in the infrastructure, after deployment</div>
    </div>
    <div class="sbx-cell sbx-ct">
      <div class="sbx-title">Compile-time sandboxing <span class="sbx-badge">Jo</span></div>
      <div class="sbx-sub">Enforced by the compiler, before code runs</div>
    </div>
  </div>
  <div class="sbx-row">
    <div class="sbx-cell">
      <div class="sbx-item"><span class="sbx-ico sbx-no">✕</span> Blind to business logic</div>
      <div class="sbx-desc">Can block syscalls and files, but cannot express rules like "read only this user's rows" or "only these 2 narrowed REST APIs"</div>
    </div>
    <div class="sbx-cell sbx-ct">
      <div class="sbx-item"><span class="sbx-ico sbx-yes">✓</span> Aware of business logic</div>
      <div class="sbx-desc">Rules like "read only this user's rows" or "only these 5 narrowed REST APIs" are typed capabilities the compiler enforces.</div>
    </div>
  </div>
  <div class="sbx-row">
    <div class="sbx-cell">
      <div class="sbx-item"><span class="sbx-ico sbx-no">✕</span> Boundary buried in deployment stack</div>
      <div class="sbx-desc">Authority is scattered across configs and runtime parameters — auditing means digging through infrastructure.</div>
    </div>
    <div class="sbx-cell sbx-ct">
      <div class="sbx-item"><span class="sbx-ico sbx-yes">✓</span> Boundary is typed, versioned code</div>
      <div class="sbx-desc">Authority is declared in typed interfaces — auditing means reviewing code in version control.</div>
    </div>
  </div>
  <div class="sbx-row">
    <div class="sbx-cell">
      <div class="sbx-item"><span class="sbx-ico sbx-no">✕</span> Violations surface at runtime</div>
      <div class="sbx-desc">Escapes are discovered at runtime, after the code is already deployed.</div>
    </div>
    <div class="sbx-cell sbx-ct">
      <div class="sbx-item"><span class="sbx-ico sbx-yes">✓</span> Violations are compile errors</div>
      <div class="sbx-desc">The compiler pinpoints them in source, with detailed errors — avoids unnecessary deployment on infrastructure.</div>
    </div>
  </div>
</div>

A runtime sandbox speaks the operating system's language: processes, files, sockets.
The rules worth enforcing need to be written
in the application's language — which rows, which endpoints, which
user's data — and a boundary can only enforce what it can express. Types are
the only boundary that operates at the application level.

## Compile-Time Sandboxing, Applied to AI

In a recent ACM Queue article, [*Safe
Coding*](https://queue.acm.org/detail.cfm?id=3773098), Christoph Kern
distills decades of Google's security engineering into a principle of
rigorous modular reasoning:

> ... the safety of risky operations within an abstraction must rely solely
> on assumptions supported by the abstraction's APIs and type signatures.
> Conversely, the composition of safe abstractions with safe code (i.e., code
> free of risky operations, which constitutes the vast majority of a program)
> is automatically verified by the implementation language's type checker.

That is exactly what Jo's compile-time sandboxing is doing.

In Jo, risky operations — filesystem access, network calls, FFI, database
queries — need explicit permissions, visible in function type signatures as
capabilities.

In the following example, the AI-generated code is confined to the
capabilities it is given:

```jo
interface OrdersApi
  def query(lastDays: Int): List[Order]
end

param ordersApi: OrdersApi

// Untrusted code (AI-generated): can use only what it received
def aiMain(): Unit receives ordersApi, IO.stdout =
  val orders = ordersApi.query(30)
  printOrders: orders.select(o => o.state == "open")
```

If `aiMain` tries to reach the filesystem, the network, or an unscoped
query, the program does not misbehave at runtime — it fails to compile.

The `ordersApi` capability is provided by the trusted harness — for
example, as a user-scoped, read-only view over the real database. But the
implementation is opaque to the AI code: all it ever sees is the
`OrdersApi` interface.

## Alternatives

### Runtime Sandboxing + REST APIs

A popular middle ground is to sandbox the agent and let it reach the world
only through REST APIs. This helps, but it inherits a design mismatch:
**REST APIs were designed for trusted callers and they expose a large
capability surface**. A typical API token grants everything the user can do through the
web interface — read all orders, change the account, delete records — far
more than any single agent task needs.

An agent is not a trusted caller. What it may call depends on
the security context of the task at hand: one task should see only a small
subset of the API surface; another needs an endpoint, but with its scope
narrowed — this user's rows, read-only, the last 30 days. Existing REST APIs
were not designed to answer such needs, so the restrictions end up in
gateways and per-agent proxy policies — authority drifts back into
infrastructure configuration, with all the audit problems above.

Meanwhile, how to protect API credentials is also a headache: prompt
injection could easily steal the API keys!

With capabilities, the same narrowing is a few lines of trusted code: wrap
the API in a smaller interface.
Also, your API credentials are safe: they are invisible to untrusted code.

### MCPs

MCP gives LLMs a catalog of vetted tools that can restrict LLM capabilities
to application-defined security policies. If a problem can be solved this
way, it should be.

However, for complex use cases, the approach hits limits. Every tool
definition is loaded into the context window before any work happens. Every
intermediate result must round-trip through the model, burning tokens at
each step.

And a fixed tool set has limited flexibility: when a task calls for a
filter, a join, or a loop over results, the agent either needs yet another
tool or has to simulate it token by token. The research [*Executable Code
Actions Elicit Better LLM Agents*](https://arxiv.org/abs/2402.01030) shows
that agents acting through generated code outperform those restricted to
tool calls.

The natural fix — also advocated by [Anthropic's engineering
team](https://www.anthropic.com/engineering/code-execution-with-mcp) — is
to let the agent write code that calls the tools: filtering and composition
happen in the program, and only the final result returns to the model. But
that brings back the original question: what exactly may the generated code
do? Compile-time sandboxing is the answer.

## Learn More

Jo implements compile-time sandboxing through a hard separation between
trusted and confined compilation worlds — see the [Two-World
Architecture](../security/two-worlds.md). The capability mechanics —
context parameters, `receives` clauses, and authority attenuation — are
described in [Secure Language Design](../security/language-design.md), and
are grounded in the model of [contextual
capabilities](https://github.com/typescope/contextual-capability).

## References

1. Christoph Kern. [Safe Coding: Rigorous Modular Reasoning about Software
   Safety](https://queue.acm.org/detail.cfm?id=3773098). *ACM Queue* 23(5),
   November 2025.
2. Xingyao Wang, Yangyi Chen, Lifan Yuan, Yizhe Zhang, Yunzhu Li, Hao Peng,
   Heng Ji. [Executable Code Actions Elicit Better LLM
   Agents](https://arxiv.org/abs/2402.01030). *ICML* 2024.

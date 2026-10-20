---
layout: home
title: Home

hero:
  name: "Jo"
  text: "For the joy of secure programming"
  tagline: Compile-time sandboxing — capabilities are explicit, fine-grained, and enforced by the compiler, not bolted on at runtime.
  actions:
    - theme: brand
      text: Get Started
      link: /usage/install
    - theme: alt
      text: Language Tour
      link: /overview/language-tour
    - theme: alt
      text: Security Model
      link: /security/security-problem

features:
  - icon:
      src: /icons/lock.svg
    title: Compile-Time Sandboxing
    details: Code can only access what it declares and is granted. Anything beyond that is rejected at compile time, before the program runs.
    link: /overview/capabilities
    linkText: Learn more

  - icon:
      src: /icons/shield-check.svg
    title: Confine Untrusted Code
    details: Structurally prevent third-party code, plugins, or generated programs from accessing anything beyond what you explicitly grant.
    link: /security/security-problem
    linkText: See the problem

  - icon:
      src: /icons/arrows.svg
    title: Context Parameters
    details: Pass capabilities through the call stack implicitly — no boilerplate, no globals, no frameworks.
    link: /guides/context-parameters
    linkText: Learn more

  - icon:
      src: /icons/badge-check.svg
    title: Type-Safe by Design
    details: A sound type system is the foundation of Jo's security — types describe not just data, but also effects and capabilities.
    link: /language/design-principles
    linkText: Design principles

  - icon:
      src: /icons/sparkles.svg
    title: Pattern-Oriented Programming
    details: Compose reusable pattern predicates with logical operators. Exhaustive matching — the compiler guarantees no case is missed.
    link: /guides/patterns
    linkText: Explore patterns

  - icon:
      src: /icons/layers.svg
    title: Multiple Backends
    details: One language, multiple runtimes. Compile to Python or Ruby with the same type guarantees on every platform.
    link: /usage/getting-started
    linkText: Get started
---

<section class="integrations">
  <p class="integrations-eyebrow">Integrations</p>
  <h2 class="integrations-title">Integrate Jo with Agent Frameworks</h2>
  <p class="integrations-lede">Keep your model, agent loop, and tool calling code. Add one tool that compiles and runs Jo programs, and the compiler confines every generated program before it runs.</p>
  <ol class="integrations-flow">
    <li><span class="flow-step">1</span><div><strong>Agent writes Jo</strong><span>The model sends Jo source to your tool.</span></div></li>
    <li><span class="flow-step">2</span><div><strong>Compiler checks it</strong><span>Code outside the confined API is rejected and errors go back to the agent.</span></div></li>
    <li><span class="flow-step">3</span><div><strong>Host runs it</strong><span>The program runs with only the capabilities you grant.</span></div></li>
  </ol>
  <div class="integration-grid">
    <a class="integration-card" href="/integration/langchain">
      <div class="integration-head">
        <span class="integration-tag">Python</span>
      </div>
      <h3>LangChain</h3>
      <p>Give a LangChain agent one tool that compiles and runs Jo programs.</p>
      <pre class="integration-code" v-pre><span class="tk">@tool</span>
<span class="tk">def</span> run_jo_program(code: str) -> str:
    <span class="tk">return</span> run_jo(code, user_id)
agent = create_agent(
    model, tools=[run_jo_program])</pre>
      <span class="integration-link">Read the guide <span class="vpi-arrow-right" aria-hidden="true"></span></span>
    </a>
    <a class="integration-card" href="/integration/openai-agents-sdk">
      <div class="integration-head">
        <span class="integration-tag">Python</span>
      </div>
      <h3>OpenAI Agents SDK</h3>
      <p>Expose Jo as a function tool to an OpenAI agent.</p>
      <pre class="integration-code" v-pre><span class="tk">@function_tool</span>
<span class="tk">def</span> run_jo_program(code: str) -> str:
    <span class="tk">return</span> run_jo(code, user_id)
agent = Agent(name=<span class="ts">"Analyst"</span>,
    tools=[run_jo_program])</pre>
      <span class="integration-link">Read the guide <span class="vpi-arrow-right" aria-hidden="true"></span></span>
    </a>
    <a class="integration-card" href="/integration/harpe">
      <div class="integration-head">
        <span class="integration-tag">Jo native</span>
      </div>
      <h3>Harpe</h3>
      <p>Build the whole agent in Jo with a framework sandboxed at compile time.</p>
      <pre class="integration-code" v-pre><span class="tk">val</span> runCode = RunCodeTool(sandboxDir)
<span class="tk">val</span> tools = runCode.toolset(guestEnv)</pre>
      <span class="integration-link">Read the guide <span class="vpi-arrow-right" aria-hidden="true"></span></span>
    </a>
  </div>
  <p class="integrations-more"><a href="/integration/">How the integration works <span class="vpi-arrow-right" aria-hidden="true"></span></a></p>
</section>

## From the blog

- [Jo 0.14.0 Released](/blog/2026-10-14-jo-0-14-0) · October 14, 2026
- [Introducing Harpe — An Agent Framework with Compile-Time Sandboxing](/blog/2026-09-28-introducing-harpe) · September 28, 2026
- [The Standard Library, Documented](/blog/2026-08-22-standard-library-docs) · August 22, 2026
- [Why Secure AI Needs Compile-Time Sandboxing](/blog/2026-06-11-why-compile-time-sandboxing) · June 11, 2026
- [Introducing Jo — Secure Programming for the AI Era](/blog/2026-06-04-introducing-jo) · June 4, 2026

---

Jo is developed and maintained by [TypeScope](https://typescope.ai).

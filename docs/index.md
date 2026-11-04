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
      src: /icons/code.svg
    title: Expressive
    details: Functions, collections, loops, pattern matching and published libraries.
    link: /overview/language-tour
    linkText: Language tour

  - icon:
      src: /icons/lock.svg
    title: Secure
    details: "No ambient authority: no globals, FFI or reflection in confined code. Capabilities are checked at compile time."
    link: /overview/capabilities
    linkText: Learn more

  - icon:
      src: /icons/plug.svg
    title: Interoperable
    details: Compiles to Python and Ruby. Trusted code calls existing Python/Ruby libraries and services.
    link: /guides/python-interoperability
    linkText: Learn more
---

<section class="integrations">
  <p class="integrations-eyebrow">Integrations</p>
  <h2 class="integrations-title">Integrate Jo with Agent Frameworks</h2>
  <p class="integrations-lede">Keep your model, agent loop, and tool calling code. Add one tool that compiles and runs Jo programs, and the compiler confines every generated program before it runs.</p>
  <img class="integrations-diagram" src="/img/integration-flow.svg" width="960" height="440" alt="An agent sends Jo source to a tool that compiles against a confined API and returns compiler errors, links a trusted API implementation, runs the program, and returns its output or runtime error.">
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

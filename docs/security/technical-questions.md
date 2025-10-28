# Technical Questions

Common technical questions about Jo's approach to AI code security.

## Language Adoption

**Q: Is adopting a new programming language difficult for enterprises?**

Jo is not intended for human developers but as a secure interface language for LLMs to generate code.

- Compiles to standard languages (Java, JavaScript, Python, Ruby)
- Platforms keep their main tech stack unchanged
- Only wrap exposed APIs in Jo to establish security boundaries

## LLM Code Generation

**Q: Can LLMs effectively generate code in Jo?**

Our experience shows few-shot prompting is sufficient for LLMs to generate Jo code:

- Language design intentionally avoids LLM-unfriendly features like meta-programming
- Agentic workflows based on compiler feedback improve LLM performance

## Comparison with MCP

**Q: Is Model Context Protocol (MCP) sufficient for LLM integration in SaaS platforms?**

MCP excels at simple external tool integration but has limitations:

- Cannot handle large data responses (100k+ records) due to token limits
- Serves complex requests through fragmented API calls without code-based planning
- Limited to simple input/output patterns

Jo synthesizes complete programs using confined APIs, enabling:

- Complex business logic and large data processing
- Error handling, logging/auditing, and sophisticated agentic workflows

Research shows code-based planning improves task completion by 10-20% over text-based planning ([Wang et al., 2024](https://arxiv.org/abs/2402.01030)).

## Security Evolution

**Q: Can prompt injection attacks evolve faster than language security features?**

Language-level security is based on formal logic, providing mathematical certainty rather than probabilistic protection:

- Main concerns are implementation bugs, not theoretical vulnerabilities
- Strong type system prevents many runtime exploits at compile-time
- Implementation risks addressed through extensive testing and audits
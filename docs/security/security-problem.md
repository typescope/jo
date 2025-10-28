# The AI Security Problem

AI code generation introduces a fundamental security challenge: executing untrusted code safely.

## Current Approaches

Traditional security relies on external isolation:

- **Sandboxing** - Process-level isolation
- **Runtime monitoring** - Post-execution detection
- **Code review** - Manual inspection
- **OS permissions** - System-level access controls

## Limitations

**Coarse permissions** - OS-level controls are too broad. Database access means full database access.

**Runtime detection** - Security violations are detected after execution, not prevented.

**Complex deployment** - Effective sandboxing requires significant infrastructure setup.

**Limited static analysis** - Difficult to determine what resources code will access before execution.

## The Problem

AI-generated code is untrusted by definition. Current security measures either:

- Block execution entirely (defeating the purpose)
- Allow broad access (creating security risks)
- Require complex infrastructure (limiting adoption)

A language-level approach is needed to provide fine-grained, statically verifiable security controls.
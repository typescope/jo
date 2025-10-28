# Jo Programming Language

<div align="center">
  <h1>🛡 Secure Programming for the AI Era</h1>
  <p><strong>For the joy of secure programming</strong></p>
</div>

---

Jo is a secure programming language designed for securing LLM generated code.

## Why Jo?

- **API confinement** - LLM generated code is confined to custom-defined application-level APIs
- **Fine-grained control** - Precise control of authorization, e.g. only access certain rows of a data table  
- **Easy security auditing** - Statically checked authorities and clear security boundaries

## Key Features ✨

- **Extensible runtime** - Extend and customize the language runtime with a Jo library
- **No global variables** - Safe and easy to compose and for reuse
- **Context parameters** - Contextual abstraction, optional parameters, and implicit resolution
- **Effect system** - Fine-grained effect control with parametric effects
- **Algebraic data types** - Extensible ADTs with pattern matching
- **Pattern-oriented programming** - First-class patterns and higher-order patterns
- **Natural syntax** - Prefix, infix, and postfix operators; two call styles `f(x)` and `f x`; indentation-based
- **Multiple backends** - Interpreter, JavaScript, native x86 Linux, and more are coming

## Quick Example

```jo
def main = println "Hello world!"
```

## The Problem

As AI code generation becomes mainstream, we face a critical security challenge: **How do you safely execute untrusted AI-generated code?**

Traditional approaches rely on sandboxing and external security measures. Jo takes a different approach by building security directly into the language.

## The Solution

Jo uses **capability-based security** at the language level. AI-generated code can only access the specific resources and APIs that you explicitly authorize—nothing more.

```jo
// AI code is confined to authorized APIs
param database: Database  // Only this database access allowed
param userId: String      // User context automatically enforced

def getOrders(): List[Order] =
  // Runtime automatically adds: WHERE owner_id = userId
  database.query("SELECT * FROM orders")
```

## Why It Matters

- **Zero-trust by default** - AI code cannot access unauthorized resources
- **Fine-grained control** - Specify exactly what capabilities AI assistants can use  
- **Easy auditing** - Security boundaries are explicit and statically checkable
- **No performance overhead** - Security is enforced at compile time

**Ready to explore?** Use the navigation on the left to dive deeper into Jo's features and security model.
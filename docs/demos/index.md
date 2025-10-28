# Live Demos

Experience Jo's security features through real-world applications that demonstrate capability-based security in action.

## Available Demos

### 💻 [Process Monitor](process-monitor.md)
**Runtime Extension Demo**

A system monitoring application that extends Jo's JavaScript runtime with controlled system-level capabilities.

**Key Features:**
- Runtime extension using `-runtime` flag  
- Context parameters for capability provision
- Security confinement - user code analyzes processes without direct Node.js access

**Perfect for:** Understanding how to safely expose system APIs to untrusted code.

---

### 🔐 [Data Table Security](data-table.md)  
**Row-Level Access Control Demo**

A database application demonstrating automatic row-level security where users can only access their own data.

**Key Features:**
- User-aware runtime with automatic query filtering
- All SQL queries filtered by `WHERE owner_id = ?`
- Compiler-enforced security - impossible to bypass filtering

**Perfect for:** CTOs and security teams interested in database security.

---

### 🔍 [Query DSL](query-dsl.md)
**Flexible Security Demo**

An advanced database demo that adds flexible custom filtering while maintaining security guarantees.

**Key Features:**
- Type-safe expression builder with infix operators
- Context parameters with defaults for ordering/pagination  
- User conditions automatically ANDed with security constraints

**Perfect for:** Developers interested in building secure, expressive APIs.

## Running the Demos

All demos are included in the [preview release](../language/download.md). Each demo includes:

- **Source code** - Full Jo implementation
- **Runtime extensions** - Custom API definitions
- **Build instructions** - How to compile and run
- **Security analysis** - What guarantees are provided

## Demo Architecture

Each demo follows Jo's three-stage compilation model:

1. **API Definition** - Define the capabilities available to user code
2. **Runtime Implementation** - Implement security enforcement  
3. **User Code** - Write application logic within security constraints

This architecture ensures that security is enforced at the runtime level, making it impossible for malicious code to bypass restrictions.

**Ready to explore?** Start with the [Process Monitor demo](process-monitor.md) for a gentle introduction to Jo's security model.
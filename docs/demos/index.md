# Security Demos

Interactive demonstrations of Jo's security features.

## Available Demos

### Process Monitor
System monitoring with controlled resource access. Demonstrates runtime extension and capability confinement.

- Runtime extension using `-runtime` flag
- Context parameters for capability provision
- User code cannot access Node.js APIs directly

### Data Table Security
Database operations with automatic row-level security. Shows user-specific data access enforcement.

- Automatic query filtering with `WHERE owner_id = ?`
- Compiler-enforced security constraints
- No way to bypass filtering in user code

### Query DSL
Domain-specific query language with capability-based permissions. Advanced security with flexible query building.

- Type-safe expression builder
- Context parameters with defaults
- User conditions ANDed with security constraints

## Running Demos

Demos are included in the preview release. Each includes source code, runtime extensions, and build instructions.

## Architecture

Three-stage compilation model:

1. **API Definition** - Available capabilities
2. **Runtime Implementation** - Security enforcement
3. **User Code** - Application logic within constraints

Security is enforced at the runtime level through the type system.
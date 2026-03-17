# Comparison with Alternatives

This document compares Jo's compile-time capability approach with runtime isolation technologies commonly used for sandboxing untrusted code.

## Overview

| Technology | Isolation Level | Granularity | Enforcement | Overhead |
|------------|-----------------|-------------|-------------|----------|
| VMs | Hardware | Machine | Runtime | Medium-High |
| Containers/seccomp | OS/Kernel | Syscalls | Runtime | Low-Medium |
| WASM + WASI | Language runtime | Resources | Runtime | Low |
| Deno | Language runtime | Permissions | Runtime | Low |
| **Jo** | Type system | Arbitrary | Compile time | None |

## Virtual Machines

**How it works**: VMs provide hardware-level isolation through hypervisors. Each VM runs a complete OS with its own kernel.

Modern lightweight alternatives reduce overhead:

- **MicroVMs** (Firecracker, QEMU microvm): Minimal virtual machines with fast startup (~125ms) and low memory footprint (~5MB)
- **User-space VMs** (gVisor): Kernel interface reimplemented in user space, providing VM-like isolation without hardware virtualization

**Strengths**:

- Strongest isolation — hardware boundary (or equivalent for user-space VMs)
- Battle-tested (decades of production use)
- Can run any code in any language
- MicroVMs make per-request isolation feasible

**Weaknesses for AI code confinement**:

- No capability granularity — the VM has full access to everything inside it
- Security context must be managed at application level
- Cannot express "access only this user's data"
- Attack surface includes hypervisor or user-space kernel implementation

**Verdict**: VMs (including microVMs) provide strong isolation boundaries but cannot enforce fine-grained, user-scoped capabilities. They isolate *machines*, not *data access patterns*.

## Containers and seccomp

**How it works**: Containers use Linux namespaces and cgroups for isolation. seccomp filters restrict available syscalls.

**Strengths**:

- Lower overhead than VMs
- Can restrict syscalls (no network, no filesystem, etc.)
- Mature tooling (Docker, Kubernetes)

**Weaknesses for AI code confinement**:

- Granularity is at syscall level, not application level
- Cannot express "access only this user's data" — only "no database access"
- Security context must be managed at application level
- Attack surface is the shared host kernel — escape vulnerabilities have occurred
- Still runtime overhead for namespace/cgroup management

**Verdict**: Containers isolate processes from each other, but cannot enforce fine-grained, user-scoped capabilities within an application.

## WASM + WASI

**How it works**: WebAssembly provides a sandboxed execution environment. WASI (WebAssembly System Interface) provides capability handles for system resources.

**Strengths**:

- Language-agnostic — can sandbox code compiled from any language
- Low runtime overhead
- Capability-based design (WASI)
- Growing ecosystem and tooling

**Weaknesses for AI code confinement**:

- Capabilities are coarse-grained (filesystem, network, sockets)
- Cannot express "read only this user's orders" — only "database access"
- Security context is application-level, not enforced by WASI
- Capability violations discovered at runtime, not compile time
- Attack surface is the WASM runtime implementation
- Still requires runtime sandbox overhead

**Example limitation**:
```
// WASI grants "database access"
// But cannot prevent: SELECT * FROM orders (all users)
// Application must check user_id — WASI cannot enforce it
```

**Target language limitation**: WASM requires compiling source code to the WASM bytecode format. Languages like Python and Ruby do not have mature WASM compilation targets. If your platform runs on Python or Ruby, WASM is not a practical option.

**Verdict**: WASI capabilities are resource-level (files, network), not application-level (user data, business logic). For AI code that needs fine-grained, user-scoped access, WASI is insufficient. Additionally, WASM is impractical for Python/Ruby platforms.

## Deno

**How it works**: Deno is a JavaScript/TypeScript runtime with a permission system. Permissions are granted via command-line flags (`--allow-read`, `--allow-net`, etc.).

**Strengths**:

- Fine-grained permissions (read, write, net, env, etc.)
- Can scope file permissions to specific paths
- Built into the runtime — no extra tooling
- Good developer experience

**Weaknesses for AI code confinement**:

- Permissions are process-level, not code-level
- All code in a Deno process shares the same permissions
- Cannot express "this function can only access user X's data"
- Cannot statically verify that code respects permission boundaries
- Attack surface is V8 engine and Deno runtime
- JavaScript/TypeScript only

**Example limitation**:
```typescript
// Deno grants --allow-read=/data
// But cannot prevent reading /data/other_user/secrets.txt
// Application must check user_id — Deno cannot enforce it
```

**Verdict**: Deno permissions are process-scoped, not capability-scoped. Once a permission is granted, all code can use it. For AI-generated code that needs user-scoped capabilities, Deno is insufficient.

## Jo's Approach

**How it works**: Jo uses a sound static type system to enforce capabilities at compile time. Capabilities are context parameters that flow through the program and can be arbitrarily attenuated.

**Strengths**:

- Arbitrarily fine-grained capabilities (user-scoped, time-limited, read-only)
- Security context embedded in capability objects
- Compile-time enforcement — violations are type errors
- No runtime sandbox overhead
- Static verification enables security auditing
- `allow none` proves code uses only declared capabilities
- Compiles to Python, Ruby, and JavaScript — works on existing platforms

**Example**:
```jo
// Capability is user-scoped at creation
val orders = new UserScopedOrders(userId, db)

// Compiler proves: AI code cannot access other users' data
allow none in aiMain() with orders = orders
```

**Weaknesses**:

- Jo language only — cannot sandbox arbitrary code
- Attack surface is the compiler and type system soundness
- Does not protect against resource exhaustion (CPU, memory)
- The API implementation exposed to untrusted code requires security review

## Attack Surface Comparison

Each technology has a different trusted computing base (TCB):

| Technology | Attack Surface | Vulnerability Impact |
|------------|----------------|---------------------|
| VMs | Hypervisor (or user-space kernel for gVisor) | VM escape → host compromise |
| Containers | Host kernel | Container escape → host compromise |
| WASM | WASM runtime (Wasmtime, V8, etc.) | Sandbox escape → process compromise |
| Deno | V8 + Deno runtime | Sandbox escape → process compromise |
| **Jo** | Compiler + type system | Type soundness bug → capability bypass |

**Key insight**: Runtime technologies have *runtime* attack surfaces — bugs are exploited during execution. Jo's attack surface is the *compiler* — bugs would need to exist in the type checker, and exploitation requires crafting code that type-checks but violates security properties.

A compiler bug is generally harder to exploit than a runtime bug because:

1. The attacker must craft source code that passes type checking
2. The bug must be in the security-relevant parts of the type system
3. The exploit is visible in source code (auditable)

Note that untrusted Jo code cannot exploit bugs in the target runtime (Python/Ruby/JS) because it has no FFI access and can only use explicitly provided capabilities. A defense-in-depth approach can still combine Jo with runtime isolation for additional assurance.

## Defense in Depth

Jo's compile-time guarantees can be combined with runtime isolation for defense in depth:

```
┌─────────────────────────────────────┐
│  Container / VM                     │  ← Infrastructure isolation
│  ┌───────────────────────────────┐  │
│  │  Jo Runtime World (Harness)   │  │  ← Trusted code, audited
│  │  ┌─────────────────────────┐  │  │
│  │  │  Jo Pure World (AI Code)│  │  │  ← Untrusted, type-checked
│  │  └─────────────────────────┘  │  │
│  └───────────────────────────────┘  │
└─────────────────────────────────────┘
```

Jo provides the inner layer of fine-grained capability control. Containers or VMs can provide an outer layer of infrastructure isolation. This combination addresses both application-level and infrastructure-level threats.

## Summary

Runtime isolation technologies (VMs, containers, WASM, Deno) provide coarse-grained, resource-level sandboxing. They can restrict "network access" or "filesystem access" but cannot enforce "access only this user's data."

Jo provides fine-grained, application-level capability control with compile-time enforcement. For the specific problem of confining AI-generated code to user-scoped capabilities, Jo's approach is more precise and catches violations earlier (at compile time rather than runtime).

The trade-off is that Jo only works for code written in Jo, while runtime technologies can sandbox arbitrary code. However, Jo compiles to Python, Ruby, and JavaScript, making it practical for existing platforms.

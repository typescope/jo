# Contributing to Jo

Jo is an early-stage language project for secure programming. Contributions are
welcome, but the core language design, security model, runtime behavior, and
standard library APIs are still stabilizing.

## What to Contribute

Good first contributions include:

- Documentation fixes
- Examples and demos
- Tests
- Bug reports with small reproductions
- Small compiler, tooling, packaging, or CI fixes

Please open an issue or discussion before starting work on:

- Language syntax or semantics
- Type system changes
- Capability or authority model changes
- Runtime behavior
- Standard library APIs
- Dependency changes
- Security-sensitive behavior

Large pull requests may be declined or delayed if they do not match the current
design direction.

## Contribution Terms

By contributing to this project, you agree that your contributions are licensed
under the same license as the project: Apache License 2.0.

This project uses the Developer Certificate of Origin (DCO). Every commit must
include a `Signed-off-by` line certifying that you have the right to submit the
contribution under the project license.

To sign off a commit, use:

```bash
git commit -s
```

The sign-off line should look like this:

```text
Signed-off-by: Your Name <you@example.com>
```

See the DCO text at <https://developercertificate.org/>.

## Pull Requests

- Keep pull requests focused.
- Include tests for behavior changes when practical.
- Update documentation when user-visible behavior changes.
- Do not mix formatting-only changes with behavior changes.
- Do not add new dependencies without prior discussion.
- All pull requests require review before merging.

Contributors do not receive commit access by default.

## Security

Do not report security vulnerabilities in public issues. See
[`SECURITY.md`](SECURITY.md) for the vulnerability reporting process.

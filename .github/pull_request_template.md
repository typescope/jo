Fix #XXX: A short description

## Summary

<!-- What does this PR do and why? -->

## What has changed

## Checklist

- [ ] Added / updated tests under `tests/pos/` or `tests/warn/`
- [ ] Docs updated if the change affects user-visible behavior
- [ ] All commits are signed off ([why?](https://github.com/typescope/jo/blob/main/CONTRIBUTING.md#contribution-terms))

<details>
<summary>How to sign off commits</summary>

Use `git commit -s` to add the `Signed-off-by` line automatically:

To add a sign-off to the last commit retroactively:

```bash
git commit --amend -s --no-edit
```

To add sign-off to the last 3 commits:

```
git rebase --signoff HEAD~3
```

</details>

## Security impact

<!-- Does this touch capability checking, FFI boundaries, or auth paths? If so, describe. -->

## Compatibility impact

<!-- Does this affect compatibility? If so, describe. -->

- [ ] Source compatibility: existing code continue to compile
- [ ] SAST compatibility
  - [ ] forward compatibility: new libraries can be used by old compiler
  - [ ] backward comopatibility: old libraries can be used by new compiler
- [ ] Standard Library compatibility:
  - [ ] forward compatibility: new code can work with old stdlib
  - [ ] backward comopatibility: old code work with the new stdlib
- [ ] Build tool
  - [ ] Build spec compatibility: old projects continue to build
  - [ ] Joy package compatibility: old .joy can be consumed by new build tool

---

## Implementation checklist (for new language features)

<details>
<summary>Expand</summary>

- [ ] specify syntax (syntax-summary.md)
- [ ] token & scanner
- [ ] ast & parsing syntax
- sast
  - [ ] sast & sast operations
  - [ ] raw printer and pickling test
  - [ ] flags & symbols & name table
  - [ ] types & type operations
  - [ ] subtyping
  - [ ] encoding & decoding
- [ ] type checking
  - [ ] namer
  - [ ] expression typer
  - [ ] flow typer
  - [ ] view checker
  - [ ] adaptation
  - [ ] auto resolution
- [ ] context params
  - [ ] analysis and check
  - [ ] semantic transform
- pattern match
  - [ ] pattern typer
  - [ ] flow typing
  - [ ] determinism check
  - [ ] exhaustivity check
  - [ ] semantic transform
- [ ] phases
  - [ ] closure conversion
  - [ ] tail-rec optimization
  - [ ] erasure
- platforms
  - [ ] interpreter
  - [ ] js backend
  - [ ] ruby backend
  - [ ] python backend
  - [ ] stack machine
  - [ ] register machine
- tools
  - [ ] emacs
  - [ ] vscode
  - [ ] vim
  - [ ] js code highlight
- documentation
  - [ ] design doc
- tests
  - [ ] positive tests
  - [ ] negative tests

</details>

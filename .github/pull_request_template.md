Fix #XXX: A short description

## Summary

<!-- What does this PR do and why? -->

## What has changed

## Testing

- [ ] Added / updated tests under `tests/pos/` or `tests/warn/`
- [ ] Ran `./ci` locally and all tests pass
- [ ] Docs updated if the change affects user-visible behavior
- [ ] All commits are signed off ([why?](https://developercertificate.org/))

<details>
<summary>How to sign off commits</summary>

Use `git commit -s` to add the `Signed-off-by` line automatically:

```bash
git config --global user.name "Your Name"
git config --global user.email "you@example.com"
git commit -s -m "your message"
```

To add a sign-off to the last commit retroactively:

```bash
git commit --amend -s --no-edit
```

</details>

## Security impact

<!-- Does this touch capability checking, FFI boundaries, or auth paths? If so, describe. -->

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

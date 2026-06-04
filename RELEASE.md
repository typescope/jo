# Release Guide

Checklist for cutting a new Jo release.

## 1. Prepare

- [ ] All tests pass locally: `./ci`
- [ ] Docs build cleanly: `npm --prefix docs run build`
- [ ] `CHANGELOG.md` updated with notable changes under the new version heading

## 2. Bump Version

Update the version in one place:

- [ ] `stack-lang/tool/JoVersion.scala` — `val current: Version = Version(X, Y, Z)`

Verify:

```sh
bin/jo.dev --version   # should print X.Y.Z
```

## 3. Build Release Tarball

```sh
./build --fat --release
```

This produces:
- `jo-X.Y.Z.tar.gz` — the release archive
- `jo-X.Y.Z.tar.gz.sha256` — the checksum file

- [ ] Tarball and checksum created

## 4. Create GitHub Release

```sh
gh release create vX.Y.Z jo-X.Y.Z.tar.gz jo-X.Y.Z.tar.gz.sha256 \
  --repo typescope/jo \
  --title "Jo X.Y.Z" \
  --notes "See CHANGELOG.md for details."
```

- [ ] GitHub release published (not draft, not pre-release)

## 5. Update versions.jsonl

Append one line to `docs/public/versions.jsonl`:

```jsonl
{"version":"X.Y.Z","url":"https://github.com/typescope/jo/releases/download/vX.Y.Z/jo-X.Y.Z.tar.gz","sha256url":"https://github.com/typescope/jo/releases/download/vX.Y.Z/jo-X.Y.Z.tar.gz.sha256","date":"YYYY-MM-DD"}
```

- [ ] `docs/public/versions.jsonl` updated and committed to `main`

## 6. Tag the Commit

Tag after `versions.jsonl` is committed so the tag captures the complete release state.

```sh
git tag -a vX.Y.Z -m "Jo X.Y.Z"
git push origin vX.Y.Z
```

- [ ] Tag pushed to `origin`

## 7. Deploy Docs

```sh
gh workflow run docs.yml --repo typescope/jo --ref vX.Y.Z
```

- [ ] Docs deployed and `https://jo-lang.org` shows the new version

## 8. Verify

- [ ] `curl -sSf https://jo-lang.org/install.sh | sh` installs X.Y.Z
- [ ] `jo --version` prints X.Y.Z after install
- [ ] `https://jo-lang.org/versions.jsonl` contains the new entry

## 9. Clean Up

- [ ] Remove local `jo-X.Y.Z.tar.gz` and `jo-X.Y.Z.tar.gz.sha256`

# Release Guide

Checklist for cutting a new Jo release.

## 1. Source Changes (PR)

Bundle all repository changes for the release into a single PR and merge it to
`main`. Each PR is verified green before merge, so no re-verification is needed.

- [ ] Bump the version: `stack-lang/tool/JoVersion.scala` —
      `val current: Version = Version(X, Y, Z)`
- [ ] Update the release badge in `README.md` to `vX.Y.Z` (both the badge
      image URL and the release tag link)
- [ ] Update `CHANGELOG.md` with notable changes under the new version heading
- [ ] Append the new entry to `docs/public/versions.jsonl` (the release
      download/checksum URLs follow the `vX.Y.Z` naming, so they can be added
      before the release is published):

      {"version":"X.Y.Z","url":"https://github.com/typescope/jo/releases/download/vX.Y.Z/jo-X.Y.Z.tar.gz","sha256url":"https://github.com/typescope/jo/releases/download/vX.Y.Z/jo-X.Y.Z.tar.gz.sha256","date":"YYYY-MM-DD"}

Verify the version, then merge:

```sh
bin/jo.dev --version   # should print X.Y.Z
```

- [ ] PR merged to `main`

## 2. Build Artifacts

Build from the merged `main` commit:

```sh
./build --fat --release
```

This produces:
- `jo-X.Y.Z.tar.gz` — the release archive
- `jo-X.Y.Z.tar.gz.sha256` — the checksum file

- [ ] Tarball and checksum created

## 3. Tag the Commit

Tag the merged commit so the tag captures the complete release state.

```sh
git tag -a vX.Y.Z -m "Jo X.Y.Z"
git push origin vX.Y.Z
```

- [ ] Tag pushed to `origin`

## 4. Create GitHub Release

```sh
gh release create vX.Y.Z jo-X.Y.Z.tar.gz jo-X.Y.Z.tar.gz.sha256 \
  --repo typescope/jo \
  --title "Jo X.Y.Z" \
  --notes "See CHANGELOG.md for details."
```

- [ ] GitHub release published (not draft, not pre-release)

## 5. Deploy Docs

```sh
gh workflow run docs.yml --repo typescope/jo
```

- [ ] Docs deployed and `https://jo-lang.org` shows the new version

## 6. Verify

- [ ] `curl -sSf https://jo-lang.org/install.sh | sh` installs X.Y.Z
- [ ] `jo --version` prints X.Y.Z after install
- [ ] `https://jo-lang.org/versions.jsonl` contains the new entry

## 7. Clean Up

- [ ] Remove local `jo-X.Y.Z.tar.gz` and `jo-X.Y.Z.tar.gz.sha256`

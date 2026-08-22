#!/usr/bin/env bash
#
# Unpack the published stdlib API docs into docs/public/stdlib/<version>/.
#
# Each release may attach a jo-stdlib-docs-<version>.tar.gz asset (see RELEASE.md).
# Releases cut before that asset existed simply do not have one, so a missing
# download is not an error: the version is skipped and the archive begins at
# whichever release first shipped the asset. If no version has docs at all, the
# directory is left absent and the site drops the Standard Library nav entry.

set -uo pipefail

REPO="${JO_REPO:-typescope/jo}"
ASSET_BASE="${JO_DOCS_ASSET_BASE:-https://github.com/$REPO/releases/download}"
VERSIONS_FILE="docs/public/versions.jsonl"
OUT_DIR="docs/public/stdlib"

if [ ! -f "$VERSIONS_FILE" ]; then
  echo "no $VERSIONS_FILE — skipping stdlib API docs"
  exit 0
fi

rm -rf "$OUT_DIR"

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

# versions.jsonl is append-only and chronological, so the last entry is newest.
versions="$(sed -n 's/.*"version"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$VERSIONS_FILE")"
published=()

for version in $versions; do
  url="$ASSET_BASE/v$version/jo-stdlib-docs-$version.tar.gz"

  if ! curl -sSfL "$url" -o "$tmp/docs.tar.gz" 2>/dev/null; then
    echo "  $version — no stdlib docs asset, skipping"
    continue
  fi

  dest="$OUT_DIR/$version"
  mkdir -p "$dest"

  if ! tar xzf "$tmp/docs.tar.gz" -C "$dest" 2>/dev/null; then
    echo "  $version — asset is not a readable tarball, skipping"
    rm -rf "$dest"
    continue
  fi

  if [ ! -f "$dest/index.html" ] || [ ! -f "$dest/data.js" ]; then
    echo "  $version — asset is missing index.html or data.js, skipping"
    rm -rf "$dest"
    continue
  fi

  echo "  $version — published at /stdlib/$version/"
  published+=("$version")
done

if [ "${#published[@]}" -eq 0 ]; then
  echo "no stdlib docs assets found — /stdlib/ will not be published"
  rm -rf "$OUT_DIR"
  exit 0
fi

# Newest first: config.js builds the version dropdown from this, and the bare
# /stdlib/ URL redirects to the head of the list.
manifest=""
for (( i = ${#published[@]} - 1; i >= 0; i-- )); do
  [ -z "$manifest" ] || manifest="$manifest,"
  manifest="$manifest\"${published[$i]}\""
done
printf '[%s]\n' "$manifest" > "$OUT_DIR/versions.json"

newest="${published[-1]}"
cat > "$OUT_DIR/index.html" <<EOF
<!doctype html>
<meta charset="utf-8">
<title>Jo Standard Library</title>
<meta http-equiv="refresh" content="0; url=./$newest/">
<link rel="canonical" href="./$newest/">
<p>Redirecting to the <a href="./$newest/">Jo $newest standard library documentation</a>.</p>
EOF

echo "published ${#published[@]} version(s); /stdlib/ redirects to $newest"

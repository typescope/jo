#!/bin/sh
# Install the Jo compiler.
# Usage: curl -sSf https://jo-lang.org/install.sh | sh

set -eu

REPO="${JO_REPO:-typescope/jo}"
INSTALL_BASE="${JO_HOME:-$HOME/.jo}"
ACTIVE_BIN="${JO_INSTALL_BIN:-$HOME/.local/bin/jo}"
PINNED_VERSION="${JO_VERSION:-}"

# ── helpers ──────────────────────────────────────────────────────────────────

info()  { printf '\033[34m%s\033[0m\n' "$*"; }
ok()    { printf '\033[32m%s\033[0m\n' "$*"; }
warn()  { printf '\033[33m%s\033[0m\n' "$*" >&2; }
die()   { printf '\033[31merror:\033[0m %s\n' "$*" >&2; exit 1; }

need() {
  command -v "$1" >/dev/null 2>&1 || die "'$1' is required but not found"
}

# ── latest version ───────────────────────────────────────────────────────────

latest_version() {
  URL="https://api.github.com/repos/$REPO/releases/latest"
  VERSION="$(curl -sSf "$URL" | grep '"tag_name"' | sed 's/.*"tag_name": *"\(.*\)".*/\1/')"
  [ -n "$VERSION" ] || die "Could not determine latest release version"
  echo "$VERSION"
}

# ── download and install ─────────────────────────────────────────────────────

main() {
  need curl
  need java
  need tar

  if [ -n "$PINNED_VERSION" ]; then
    # Normalise: accept both "0.10.0" and "v0.10.0"
    BARE="${PINNED_VERSION#v}"
    VERSION="v$BARE"
  else
    info "Fetching latest release..."
    VERSION="$(latest_version)"
    BARE="${VERSION#v}"
  fi

  TARBALL="jo-${BARE}.tar.gz"
  DOWNLOAD_URL="https://github.com/$REPO/releases/download/$VERSION/$TARBALL"

  INSTALL_DIR="$INSTALL_BASE/compilers/$BARE"
  ACTIVE_BIN_DIR="$(dirname "$ACTIVE_BIN")"

  info "Installing Jo $VERSION to $INSTALL_DIR ..."

  TMP="$(mktemp -d)"
  trap 'rm -rf "$TMP"' EXIT

  info "Downloading $TARBALL ..."
  curl -sSfL "$DOWNLOAD_URL" -o "$TMP/$TARBALL" || \
    die "Download failed: $DOWNLOAD_URL"

  tar -xzf "$TMP/$TARBALL" -C "$TMP"

  if [ -e "$INSTALL_DIR" ]; then
    warn "Removing existing installation at $INSTALL_DIR"
    rm -rf "$INSTALL_DIR"
  fi

  mkdir -p "$INSTALL_DIR" "$ACTIVE_BIN_DIR"
  cp -r "$TMP/jo-$BARE/." "$INSTALL_DIR/"

  # Write the versioned launcher
  INSTALL_BIN="$INSTALL_DIR/bin/jo"
  mkdir -p "$INSTALL_DIR/bin"
  cat > "$INSTALL_BIN" << 'EOF'
#!/bin/sh
BIN_DIR="$(cd "$(dirname "$0")" && pwd)"
JO_HOME="$(cd "$BIN_DIR/.." && pwd)"
export JO_HOME
exec java -jar "$JO_HOME/jo.jar" "$@"
EOF
  chmod +x "$INSTALL_BIN"

  # Write the active launcher
  cat > "$ACTIVE_BIN" << EOF
#!/bin/bash
exec "$INSTALL_BIN" "\$@"
EOF
  chmod +x "$ACTIVE_BIN"

  ok ""
  ok "Jo $VERSION installed successfully."
  ok ""
  ok "  Compiler : $INSTALL_DIR"
  ok "  Command  : $ACTIVE_BIN"

  case ":${PATH}:" in
    *":$ACTIVE_BIN_DIR:"*) ;;
    *)
      printf '\n'
      warn "Add '$ACTIVE_BIN_DIR' to your PATH:"
      warn "  export PATH=\"\$HOME/.local/bin:\$PATH\""
      ;;
  esac
}

main "$@"

#!/bin/bash
# `jo run` must execute the app interactively — with a real terminal (TTY) — so
# readline history and other isatty-gated features work. We verify this by
# running the app under a pseudo-terminal and checking os.isatty(stdout).
# A non-TTY (piped) stdout would report false.

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TEST_NAME="$(basename "$DIR")"
PROJECT_ROOT="$(cd "$DIR/../../.." && pwd)"
JO="${JO_BIN:-$PROJECT_ROOT/bin/jo}"

echo "Testing $TEST_NAME"
rm -rf "$DIR/.build"

result=$(JO="$JO" DIR="$DIR" python3 - <<'PY'
import os, pty, select, time
jo, d = os.environ["JO"], os.environ["DIR"]
pid, fd = pty.fork()
if pid == 0:
    os.chdir(d)
    os.execvp(jo, [jo, "run"])
out = b""; t0 = time.time()
while time.time() - t0 < 180:
    r, _, _ = select.select([fd], [], [], 0.3)
    if r:
        try: chunk = os.read(fd, 4096)
        except OSError: break
        if not chunk: break
        out += chunk
    if b"stdout-isatty:" in out:
        break
try: os.waitpid(pid, 0)
except Exception: pass
print("TTY" if b"stdout-isatty:true" in out.lower() else "NOTTY")
PY
)

rm -rf "$DIR/.build"
if [ "$result" = "TTY" ]; then
    echo "  ✓ All tests passed for $TEST_NAME"
else
    echo "[error] $TEST_NAME: 'jo run' did not give the app a TTY (got: $result)"
    exit 1
fi

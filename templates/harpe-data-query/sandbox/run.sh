#!/usr/bin/env bash
set -euo pipefail

# Harpe invokes this with the compiled program path. The trusted driver supplies
# these values through runCode.toolset(guestEnv); the model cannot set them.
exec python3 "$1" "${HARPE_USER_ID:?}" "${HARPE_DB_PATH:?}"

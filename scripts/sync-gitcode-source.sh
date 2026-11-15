#!/usr/bin/env bash
set -euo pipefail

# Synchronize the current local main to both hosting services.
SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
exec python3 "$SCRIPT_DIR/publish-release.py" --sync-only "$@"

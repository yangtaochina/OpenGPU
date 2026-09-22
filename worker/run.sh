#!/usr/bin/env sh
# ---------------------------------------------------------------------------
# OpenGPU GPU Worker launcher (POSIX).
#
# Usage:
#   ./run.sh                 -> start the worker with the current environment
#   ./run.sh --help          -> extra args are forwarded to worker.py
#
# If a virtual environment exists at worker/.venv it is used automatically.
# ---------------------------------------------------------------------------
set -eu

cd "$(dirname "$0")"

PY="python3"
if [ -x ".venv/bin/python" ]; then
    PY=".venv/bin/python"
fi

exec "$PY" worker.py "$@"

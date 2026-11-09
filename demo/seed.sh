#!/usr/bin/env bash
# Seeds the demo tenants: downloads and converts the corpora, then creates the tenants and uploads everything.
# Needs the API and the worker running, and ADMIN_TOKEN (read from the repo-root .env when it exists).
#
#   ./demo/seed.sh            # the demo corpora: Kubernetes docs (en) and RAGTurk Wikipedia (tr)
#   ./demo/seed.sh --local    # also LaRA, for local evaluation only; never run this against a deployed stack
#
# RAG_API_URL overrides the API address (default http://localhost:8080).
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
if [ -f "$root/.env" ]; then
  set -a
  # shellcheck disable=SC1091
  . "$root/.env"
  set +a
fi

# the first one that really runs Python 3.9+: on Windows, python3 can be a Microsoft Store placeholder that only prints a hint
python=""
for candidate in python3 python; do
  if "$candidate" -c 'import sys; sys.exit(sys.version_info < (3, 9))' >/dev/null 2>&1; then
    python="$candidate"
    break
  fi
done
if [ -z "$python" ]; then
  echo "Python 3.9+ is needed" >&2
  exit 1
fi

local_flag=()
if [ "${1:-}" = "--local" ]; then
  local_flag=(--local)
fi

# -u: progress lines show up at once, even when the output goes to a file
# the ${a[@]+...} form keeps an empty array working under set -u on bash 3.2 (macOS)
"$python" -u -X utf8 "$root/demo/prepare.py" ${local_flag[@]+"${local_flag[@]}"}
"$python" -u -X utf8 "$root/demo/seed.py" ${local_flag[@]+"${local_flag[@]}"}

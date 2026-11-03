#!/usr/bin/env bash
# Fails if a language name appears in Java code. Language-specific values belong in
# common/src/main/resources/profiles/*.yaml; Java only knows profile ids.
set -euo pipefail
cd "$(dirname "$0")/.."

if grep -rniE --include='*.java' --exclude-dir=target 'turkish|english' api common worker; then
  echo "Language names found in Java code (above). Move them into a profile under profiles/." >&2
  exit 1
fi
echo "No language names in Java code."

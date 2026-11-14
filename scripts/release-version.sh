#!/usr/bin/env bash
# Emit "version versionCode" from the complete ancestry of HEAD.
set -euo pipefail

if [[ "$(git rev-parse --is-shallow-repository)" != false ]]; then
    echo 'A full Git checkout is required for release versioning.' >&2
    exit 1
fi

count=$(git rev-list --count HEAD)
if (( count < 1 )); then
    echo 'Release versioning requires at least one commit.' >&2
    exit 1
fi
printf '0.1.%s %s\n' "$count" "$count"

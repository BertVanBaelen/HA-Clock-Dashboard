#!/usr/bin/env bash
# Checks that every version in the add-on is the same (TR-QLT-07):
# config.yaml, server/src/version.ts and all package.json files.
# Usage: scripts/check-versions.sh [expected-version | vX.Y.Z tag]
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
addon="$root/alarm_actions"
expected="${1:-}"
expected="${expected#refs/tags/}"
expected="${expected#v}"

config=$(sed -nE "s/^version:[[:space:]]*['\"]?([^'\"[:space:]]+).*/\1/p" "$addon/config.yaml")
app=$(sed -nE "s/.*APP_VERSION = '([^']+)'.*/\1/p" "$addon/server/src/version.ts")
[ -n "$expected" ] || expected="$config"

status=0
check() {
  if [ "$2" != "$expected" ]; then
    echo "::error::$1 has version '$2', expected '$expected'"
    status=1
  fi
}
check "alarm_actions/config.yaml" "$config"
check "alarm_actions/server/src/version.ts" "$app"
for pkg in package.json shared/package.json server/package.json web/package.json; do
  version=$(sed -nE 's/^  "version": "([^"]+)".*/\1/p' "$addon/$pkg")
  check "alarm_actions/$pkg" "$version"
done

[ "$status" -eq 0 ] && echo "All versions are $expected"
exit "$status"

#!/usr/bin/env bash
# Sets the add-on version everywhere (used by the release workflow).
# Usage: scripts/set-version.sh 0.2.0
set -euo pipefail

version="${1:?usage: set-version.sh <version>}"
if ! [[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "::error::'$version' is not a version like 1.2.3"
  exit 1
fi
root="$(cd "$(dirname "$0")/.." && pwd)"
addon="$root/alarm_actions"

sed -i -E "s/^version:.*/version: \"$version\"/" "$addon/config.yaml" "$root/alarm_actions_app/config.yaml"
sed -i -E "s/APP_VERSION = '[^']+'/APP_VERSION = '$version'/" "$addon/server/src/version.ts"
for pkg in package.json shared/package.json server/package.json web/package.json; do
  sed -i -E "s/^  \"version\": \"[^\"]+\"/  \"version\": \"$version\"/" "$addon/$pkg"
done
bash "$root/scripts/check-versions.sh" "$version"

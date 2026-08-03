#!/usr/bin/env bash
# =============================================================================
# Vendor the pinned JSON Schema Test Suite (2020-12 dialect corpus)
# =============================================================================
# Reproducibly materialises the `tests/draft2020-12/` (plus `remotes/`) tree
# from the commit SHA recorded in ../jsts-exclusions.yaml (key: suiteCommit).
#
# NOTE (Wave-0 finding, recorded 2026-08-03): the upstream repo no longer has a
# branch literally named `2020-12`. The current suite carries every dialect's
# tests under `tests/<dialect>/` on `main` (and beta/rc dialect branches). The
# 2020-12 corpus is `tests/draft2020-12/`. So this script clones `main`, checks
# out the pinned SHA, and copies `tests/draft2020-12/` + `remotes/`. The SHA in
# jsts-exclusions.yaml is the source of truth and remains fully reproducible.
#
# Usage:
#   ./vendor.sh [<target-dir>]     # default: ../vendor relative to this script
#
# Exit codes: 0 = vendored & SHA verified; non-zero = failure.
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JSTS_DIR="${SCRIPT_DIR}/../"
EXCLUSIONS="${JSTS_DIR}/jsts-exclusions.yaml"
TARGET="${1:-${JSTS_DIR}/vendor}"

# ---- read suiteCommit from jsts-exclusions.yaml -----------------------------
if ! command -v yq >/dev/null 2>&1 && ! command -v python3 >/dev/null 2>&1; then
    echo "ERROR: need python3 or yq to read suiteCommit" >&2; exit 2
fi
if command -v yq >/dev/null 2>&1; then
    SHA="$(yq -r '.suiteCommit' "${EXCLUSIONS}")"
else
    SHA="$(python3 -c "import yaml;print(yaml.safe_load(open('${EXCLUSIONS}'))['suiteCommit'])")"
fi

if [[ ! "${SHA}" =~ ^[0-9a-f]{40}$ ]]; then
    echo "ERROR: suiteCommit in ${EXCLUSIONS} is not a real 40-hex SHA: '${SHA}'" >&2
    exit 2
fi

TMP="$(mktemp -d)"
trap 'rm -rf "${TMP}"' EXIT
echo "Cloning JSON-Schema-Test-Suite (main, pinned ${SHA}) ..."
git clone --depth 1 --no-single-branch https://github.com/json-schema-org/JSON-Schema-Test-Suite.git "${TMP}/clone" >/dev/null 2>&1
git -C "${TMP}/clone" fetch --depth 1 origin "${SHA}" >/dev/null 2>&1
git -C "${TMP}/clone" checkout --detach "${SHA}" >/dev/null 2>&1
if [[ "$(git -C "${TMP}/clone" rev-parse HEAD)" != "${SHA}" ]]; then
    echo "ERROR: checkout SHA mismatch" >&2; exit 2
fi

rm -rf "${TARGET}"
mkdir -p "${TARGET}"
cp -r "${TMP}/clone/tests/draft2020-12" "${TARGET}/tests"
cp -r "${TMP}/clone/remotes" "${TARGET}/remotes"

echo "Vendored 2020-12 corpus to ${TARGET} at ${SHA}"
echo "  test file: $(find "${TARGET}/tests" -name '*.json' | wc -l | tr -d ' ') json files"

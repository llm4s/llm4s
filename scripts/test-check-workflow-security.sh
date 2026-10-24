#!/usr/bin/env bash
# Tests for scripts/check-workflow-security.sh: the repository's own workflows pass, and each kind of regression
# (a workflow with no top-level permissions, a top-level write grant, an action pinned by tag or branch or a short
# SHA, a pin with no version comment, an unpinned reusable workflow or docker image) fails with a message naming it,
# while the allowed forms (a local action, a SHA pin with its comment, a docker digest, job-level write) pass.
# No network. Usage: scripts/test-check-workflow-security.sh
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CHECK="${WORKFLOW_SECURITY_CHECK:-$REPO_ROOT/scripts/check-workflow-security.sh}"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

SHA=3d3c42e5aac5ba805825da76410c181273ba90b1
PASSED=0
FAILED=0
pass() { echo "ok   [$1]"; PASSED=$((PASSED + 1)); }
fail() { echo "FAIL [$1]: $2"; FAILED=$((FAILED + 1)); }

# Writes one workflow into a fresh directory: $1 = top-level permissions block (empty for none), $2 = job body.
workflow() {
  rm -rf "$WORK/wf"
  mkdir -p "$WORK/wf"
  {
    printf 'name: T\non:\n  push:\n'
    [ -n "$1" ] && printf '%s\n' "$1"
    printf 'jobs:\n  j:\n%s\n' "$2"
  } > "$WORK/wf/t.yml"
}

STEPS_OK="    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@$SHA # v7.0.1"
READ='permissions:
  contents: read'

expect_pass() {
  local label="$1" out
  if out="$("$CHECK" "$WORK/wf" 2>&1)"; then pass "$label"; else fail "$label" "expected a pass, got: $out"; fi
}
expect_fail() {
  local label="$1" needle="$2" out
  if out="$("$CHECK" "$WORK/wf" 2>&1)"; then
    fail "$label" "expected a failure, got a pass"
  elif ! grep -qF -- "$needle" <<<"$out"; then
    fail "$label" "failed without naming '$needle': $out"
  else
    pass "$label"
  fi
}

# The real workflows.
if out="$("$CHECK" 2>&1)"; then pass "the repository's workflows pass"; else fail "the repository's workflows pass" "$out"; fi

workflow "$READ" "$STEPS_OK"
expect_pass "read-only top level, a SHA pin with its version comment"

workflow "permissions: {}" "$STEPS_OK"
expect_pass "an empty top-level permissions block"

workflow "permissions: read-all" "$STEPS_OK"
expect_pass "read-all at the top level"

workflow "$READ" "    permissions:
      contents: write
      id-token: write
$STEPS_OK"
expect_pass "write access granted on the job"

workflow "$READ" "    runs-on: ubuntu-latest
    steps:
      - uses: ./.github/actions/local
      - uses: docker://alpine@sha256:$(printf 'a%.0s' $(seq 64))
      - uses: \"actions/checkout@$SHA\" # v7.0.1
      - name: path
        uses: github/codeql-action/upload-sarif@$SHA # v4.39.1"
expect_pass "local action, docker digest, quoted pin, action in a sub-path"

workflow "$READ" "    uses: ./.github/workflows/ci.yml"
expect_pass "a local reusable workflow"

workflow "" "$STEPS_OK"
expect_fail "no top-level permissions" "has no top-level \`permissions:\` block"

workflow "permissions: write-all" "$STEPS_OK"
expect_fail "write-all at the top level" "grants write access (write-all)"

workflow "permissions:
  contents: write" "$STEPS_OK"
expect_fail "a write scope at the top level" "grants write access (contents)"

workflow "$READ" "    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v7"
expect_fail "an action pinned by tag" "\`uses: actions/checkout@v7\` is not pinned by a full commit SHA"

workflow "$READ" "    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@main"
expect_fail "an action pinned by branch" "\`uses: actions/checkout@main\` is not pinned"

workflow "$READ" "    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@3d3c42e"
expect_fail "an action pinned by a short SHA" "\`uses: actions/checkout@3d3c42e\` is not pinned"

workflow "$READ" "    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout"
expect_fail "an action with no ref" "\`uses: actions/checkout\` is not pinned"

workflow "$READ" "    uses: someorg/repo/.github/workflows/build.yml@v1"
expect_fail "a third-party reusable workflow pinned by tag" "(job j): \`uses: someorg/repo/.github/workflows/build.yml@v1\` is not pinned"

workflow "$READ" "    runs-on: ubuntu-latest
    steps:
      - uses: docker://alpine:3.20"
expect_fail "a docker image pinned by tag" "\`uses: docker://alpine:3.20\` is not pinned"

workflow "$READ" "    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@$SHA"
expect_fail "a SHA pin with no version comment" "t.yml:10: \`uses: actions/checkout@$SHA\` has no version comment"

workflow "$READ" "    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v7 # v7.0.1"
expect_fail "a tag with a comment is still a tag" "is not pinned by a full commit SHA"

# A .yaml file is checked too.
workflow "" "$STEPS_OK"
mv "$WORK/wf/t.yml" "$WORK/wf/t.yaml"
expect_fail "a .yaml workflow is checked" "t.yaml: has no top-level"

echo
echo "$PASSED passed, $FAILED failed"
[ "$FAILED" -eq 0 ]

#!/usr/bin/env bash
# Tests for scripts/check-notebooks.sh, with no network and no Jupyter: the real repository passes, and each way a
# notebook can fall out of step fails with a message naming it. Every case copies the notebooks and
# docs/_data/project.yml into a scratch directory, changes one thing, and runs the check there.
#
# Usage: scripts/test-check-notebooks.sh
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CHECK="${NOTEBOOKS_CHECK:-$REPO_ROOT/scripts/check-notebooks.sh}"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
PASSED=0
FAILED=0
NB="$WORK/repo/modules/samples/notebooks"

fresh() {
  rm -rf "$WORK/repo"
  mkdir -p "$NB" "$WORK/repo/docs/_data"
  cp "$REPO_ROOT"/modules/samples/notebooks/*.ipynb "$NB/"
  cp "$REPO_ROOT/docs/_data/project.yml" "$WORK/repo/docs/_data/project.yml"
}

run() { NOTEBOOKS_ROOT="$WORK/repo" "$CHECK" > "$WORK/out" 2>&1; }

expect_pass() {
  if run; then PASSED=$((PASSED + 1)); else FAILED=$((FAILED + 1)); echo "FAIL: $1: expected success"; cat "$WORK/out"; fi
}

expect_fail() {
  local name="$1" message="$2"
  if run; then
    FAILED=$((FAILED + 1)); echo "FAIL: $name: expected a failure"; cat "$WORK/out"
  elif ! grep -qF -- "$message" "$WORK/out"; then
    FAILED=$((FAILED + 1)); echo "FAIL: $name: expected '$message' in:"; cat "$WORK/out"
  else
    PASSED=$((PASSED + 1))
  fi
}

# Edits a notebook's JSON with a Python expression over `nb`.
edit() {
  python3 - "$NB/$1" "$2" <<'PY'
import json, re, sys
path, expr = sys.argv[1], sys.argv[2]
nb = json.load(open(path, encoding="utf-8"))
code = [c for c in nb["cells"] if c["cell_type"] == "code"]
exec(expr)
json.dump(nb, open(path, "w", encoding="utf-8"), indent=1)
PY
}

fresh
expect_pass "the repository's notebooks"

fresh; edit rag.ipynb 'code[0]["source"] = [re.sub(r"(llm4s-[a-z0-9-]+:)\S+", r"\g<1>0.0.1", l) for l in code[0]["source"]]'
expect_fail "notebook behind latest_release" "rag.ipynb uses llm4s 0.0.1 but docs/_data/project.yml's latest_release is"

fresh; edit getting-started.ipynb 'code[0]["source"][0] = re.sub(r"(llm4s-core:)\S+", r"\g<1>0.0.2", code[0]["source"][0])'
expect_fail "mixed llm4s versions" "getting-started.ipynb pins more than one llm4s version"

fresh; edit rag.ipynb 'code[0]["source"] = ["// no dependencies\n"]'
expect_fail "no llm4s dependency" "rag.ipynb has no \`//> using dep org.llm4s::llm4s-...:<version>\` line"

fresh; edit getting-started.ipynb 'code[1]["outputs"] = [{"output_type": "stream", "name": "stdout", "text": ["hi\n"]}]'
expect_fail "outputs committed" "has outputs or an execution count"

fresh; edit rag.ipynb 'code[2]["execution_count"] = 3'
expect_fail "execution count committed" "has outputs or an execution count"

fresh; rm "$NB/rag.ipynb"
expect_fail "a notebook missing" "rag.ipynb not found"

fresh; sed -i.bak '/^latest_release:/d' "$WORK/repo/docs/_data/project.yml"
expect_fail "no latest_release" "has no latest_release"

echo "check-notebooks tests: $PASSED passed, $FAILED failed"
[ "$FAILED" -eq 0 ]

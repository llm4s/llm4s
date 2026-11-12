#!/usr/bin/env bash
# Keeps the Almond notebooks (modules/samples/notebooks, docs/guide/notebooks.md, #2296) reviewable and on the
# latest release. sbt never compiles them: each loads published llm4s artifacts with `//> using dep` lines, and
# .github/workflows/notebooks.yml executes them in the Almond kernel. This offline check fails when:
#   1. a notebook has no `//> using dep org.llm4s::llm4s-*:<version>` line, or names more than one version;
#   2. that version is not `latest_release` in docs/_data/project.yml, the version the docs site shows;
#   3. a code cell carries outputs or an execution count: commit notebooks with their outputs stripped, so a diff
#      shows only the code (`jupyter nbconvert --clear-output --inplace modules/samples/notebooks/*.ipynb`).
#
# Usage: scripts/check-notebooks.sh
# Environment (for scripts/test-check-notebooks.sh): NOTEBOOKS_ROOT (default: the repository).
# Exit code 0 = in step. Non-zero = a message naming the notebook and what to change.
set -euo pipefail

ROOT="${NOTEBOOKS_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"

python3 - "$ROOT" <<'PY'
import json, pathlib, re, sys

root = pathlib.Path(sys.argv[1])
project_yml = root / "docs/_data/project.yml"
notebooks_dir = root / "modules/samples/notebooks"
expected = ["getting-started.ipynb", "rag.ipynb"]

if not project_yml.is_file():
    sys.exit(f"FAIL: {project_yml} not found")
m = re.search(r'^latest_release:\s*"?([^"\s]+)"?', project_yml.read_text(encoding="utf-8"), re.M)
if not m:
    sys.exit("FAIL: docs/_data/project.yml has no latest_release")
latest = m.group(1)

errors = []
for name in expected:
    if not (notebooks_dir / name).is_file():
        errors.append(f"{name} not found in modules/samples/notebooks")

dep = re.compile(r"^//>\s*using\s+dep\s+org\.llm4s::llm4s-[a-z0-9-]+:(\S+)", re.M)
for path in sorted(notebooks_dir.glob("*.ipynb")):
    name = path.name
    try:
        nb = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as e:
        errors.append(f"{name} is not valid JSON: {e}")
        continue
    cells = nb.get("cells", [])
    code = ["".join(c.get("source", [])) for c in cells if c.get("cell_type") == "code"]
    versions = sorted({v for src in code for v in dep.findall(src)})
    if not versions:
        errors.append(f"{name} has no `//> using dep org.llm4s::llm4s-...:<version>` line")
    elif len(versions) > 1:
        errors.append(f"{name} pins more than one llm4s version: {' '.join(versions)} - use one version for every llm4s module")
    elif versions[0] != latest:
        errors.append(f"{name} uses llm4s {versions[0]} but docs/_data/project.yml's latest_release is {latest} - update the //> using dep lines")
    for i, c in enumerate(cells):
        if c.get("cell_type") != "code":
            continue
        if c.get("outputs") or c.get("execution_count") is not None:
            errors.append(f"{name} cell {i} has outputs or an execution count - strip them: "
                          "jupyter nbconvert --clear-output --inplace modules/samples/notebooks/*.ipynb")

if errors:
    for e in errors:
        print(f"FAIL: {e}")
    sys.exit(1)
print(f"ok: the notebooks pin llm4s {latest} (docs/_data/project.yml's latest_release) and carry no outputs")
PY

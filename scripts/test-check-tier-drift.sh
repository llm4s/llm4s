#!/usr/bin/env bash
# Tests for scripts/check-tier-drift.sh: the real repository passes, each kind of drift fails with a message
# naming it, and each harmless change passes. No sbt is needed. Every case copies the four files the check
# reads (build.sbt and three pages under docs/reference) into a scratch directory, changes one thing, and runs
# the check there. What is changed is chosen from the real files (the first frozen module in build.sbt, a
# module the Package Map marks Beta, ...), so a module rename or a new module does not touch this test.
#
# Usage: scripts/test-check-tier-drift.sh
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CHECK="${TIER_DRIFT_CHECK:-$REPO_ROOT/scripts/check-tier-drift.sh}"   # TIER_DRIFT_CHECK: run the tests against another copy (mutation checks)
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# The pickers read build.sbt the way the check does: comments are not live code, so a block-commented
# mimaFrozen call must never be picked as a mutation target. The check's own stripper is extracted and
# imported - always from the repository's script, never from $CHECK, so a mutated copy under test
# cannot sabotage the pickers themselves.
sed -n '/^def strip_scala_comments/,/^def read(/p' "$REPO_ROOT/scripts/check-tier-drift.sh" | sed '$d' > "$WORK/striputil.py"
grep -q "def strip_scala_comments" "$WORK/striputil.py" || { echo "FAIL: could not extract strip_scala_comments from check-tier-drift.sh"; exit 2; }
FILES="build.sbt docs/reference/v1-scope.md docs/reference/compatibility-policy.md docs/reference/api-stability.md"
PASSED=0
FAILED=0

fresh() {
  rm -rf "$WORK/repo"
  for f in $FILES; do
    mkdir -p "$WORK/repo/$(dirname "$f")"
    cp "$REPO_ROOT/$f" "$WORK/repo/$f"
  done
}

# edit FILE <<'PY' ... PY : run Python with `path`, `text` (the file's content) and `write(new)` defined.
edit() {
  local file="$1"
  python3 - "$WORK/repo/$file" <<PY
import sys, re
path = sys.argv[1]
text = open(path, encoding="utf-8").read()
def write(new):
    if new == text:
        sys.exit("test setup: the edit changed nothing in " + path)
    open(path, "w", encoding="utf-8").write(new)
$(cat)
PY
}

# pick KEY: print a value chosen from the real files (never hard-coded).
pick() {
  python3 - "$REPO_ROOT" "$1" "$WORK" <<'PY'
import re, sys
root, key = sys.argv[1], sys.argv[2]
sys.path.insert(0, sys.argv[3])
from striputil import strip_scala_comments
build = strip_scala_comments(open(root + "/build.sbt", encoding="utf-8").read())
scope = open(root + "/docs/reference/v1-scope.md", encoding="utf-8").read()
frozen = re.findall(r'^\s*mimaFrozen\("(llm4s-[a-z0-9-]+)"\)', build, re.M)
names = re.findall(r'^\s*name\s*:=\s*"(llm4s-[a-z0-9-]+)"', build, re.M)
table = scope.split("## Package Map", 1)[1].split("\n## ", 1)[0]
listed = re.findall(r'`(llm4s-[a-z0-9-]+)`', "\n".join(l.split("|")[2] for l in table.split("\n") if l.count("|") >= 4))
nonfrozen_listed = [n for n in dict.fromkeys(listed) if n not in frozen and "Frozen" not in
                    "".join(l.split("|")[3] for l in table.split("\n") if l.count("|") >= 4 and ("`%s`" % n) in l.split("|")[2])]
proj = None
projname = {}
skipped = set()
for l in build.split("\n"):
    m = re.match(r'^lazy val\s+(\w+)\s*=\s*\(project\s+in\s+file\(', l)
    if m:
        proj = m.group(1)
    m = re.search(r'name\s*:=\s*"(llm4s-[a-z0-9-]+)"', l)
    if m and proj:
        projname[proj] = m.group(1)
    if "publish / skip := true" in l and proj:
        skipped.add(proj)
published = {projname[p] for p in projname if p not in skipped}
rows_of = {}
for l in table.split("\n"):
    if l.count("|") >= 4:
        toks = re.findall(r'`(llm4s-[a-z0-9-]+)`', l.split("|")[2])
        for tok in toks:
            rows_of.setdefault(tok, []).append(toks)
pub_single = [n for n in sorted(published) if n not in frozen and n in rows_of
              and all(set(ts) == {n} for ts in rows_of[n])]
out = {
    "frozen":        [m for m in frozen if m != "llm4s-agent"][0],
    "frozen_last":   [m for m in frozen if m != "llm4s-agent"][-1],
    "nonfrozen":     nonfrozen_listed[0],
    "nonfrozen_dir": None,
    "pubmod":        pub_single[0] if pub_single else "",
}
print(out[key])
PY
}

run() { "$CHECK" "$WORK/repo" >"$WORK/out" 2>&1 && RC=0 || RC=$?; }

pass() { PASSED=$((PASSED + 1)); echo "ok   [$1]"; }
fail() { FAILED=$((FAILED + 1)); echo "FAIL [$1] $2"; echo "---- output (exit $RC):"; sed 's/^/     /' "$WORK/out"; echo "----"; }

# expect_fail NAME PATTERN : the check exits 1 and its output contains PATTERN (fixed string)
expect_fail() {
  run
  if [ "$RC" -ne 1 ]; then fail "$1" "expected exit 1"; return; fi
  if ! grep -qF -- "$2" "$WORK/out"; then fail "$1" "output does not contain: $2"; return; fi
  pass "$1"
}
expect_pass() {
  run
  if [ "$RC" -ne 0 ]; then fail "$1" "expected exit 0"; return; fi
  pass "$1"
}
expect_exit2() {
  run
  if [ "$RC" -ne 2 ]; then fail "$1" "expected exit 2 (a page the check cannot read must not pass)"; return; fi
  if ! grep -qF -- "$2" "$WORK/out"; then fail "$1" "output does not contain: $2"; return; fi
  pass "$1"
}

FROZEN="$(pick frozen)"
FROZEN_LAST="$(pick frozen_last)"
NONFROZEN="$(pick nonfrozen)"
[ -n "$FROZEN" ] && [ -n "$NONFROZEN" ] || { echo "test setup: could not pick modules from the real files" >&2; exit 2; }
DIR_OF() { python3 - "$REPO_ROOT" "$1" "$WORK" <<'PY'
import re, sys
sys.path.insert(0, sys.argv[3])
from striputil import strip_scala_comments
build = strip_scala_comments(open(sys.argv[1] + "/build.sbt", encoding="utf-8").read()).split("\n")
path = None
for l in build:
    m = re.match(r'^lazy val\s+\w+\s*=\s*\(project\s+in\s+file\("modules/+([^"]+)"\)\)', l)
    if m: path = m.group(1)
    n = re.search(r'name\s*:=\s*"%s"' % re.escape(sys.argv[2]), l)
    if n and path: print(path); break
PY
}
FROZEN_DIR="$(DIR_OF "$FROZEN")"
NONFROZEN_DIR="$(DIR_OF "$NONFROZEN")"
PUBMOD="$(pick pubmod)"
[ -n "$PUBMOD" ] || { echo "test setup: no published non-frozen module with single-target Package Map rows" >&2; exit 2; }
echo "== using: frozen=$FROZEN ($FROZEN_DIR), last frozen=$FROZEN_LAST, non-frozen=$NONFROZEN ($NONFROZEN_DIR), published=$PUBMOD"

# ---------------------------------------------------------------- the real repository
fresh
expect_pass "the repository passes"

# ---------------------------------------------------------------- the Package Map against the build
fresh
edit docs/reference/v1-scope.md <<PY
# the Package Map's Frozen rows for one frozen module become Beta
out = []
for l in text.split("\n"):
    cells = l.split("|")
    if len(cells) >= 5 and "\`$FROZEN\`" in cells[2] and "Frozen" in cells[3]:
        cells[3] = cells[3].replace("Frozen at 1.0", "Beta")
        l = "|".join(cells)
    out.append(l)
write("\n".join(out))
PY
expect_fail "a frozen module the Package Map calls Beta" "$FROZEN is frozen in build.sbt (mimaFrozen) but no Package Map row marks it Frozen"

fresh
edit docs/reference/v1-scope.md <<PY
out = []
for l in text.split("\n"):
    cells = l.split("|")
    if len(cells) >= 5 and "\`$NONFROZEN\`" in cells[2]:
        cells[3] = " Frozen at 1.0 "
        l = "|".join(cells)
    out.append(l)
write("\n".join(out))
PY
expect_fail "a module the Package Map freezes but the build does not" "the Package Map marks $NONFROZEN Frozen at 1.0"

fresh
edit docs/reference/v1-scope.md <<PY
# the first Package Map row that names the module (not a mention in the prose above the table)
out = []
inside = False
done = False
for l in text.split("\n"):
    if l.startswith("## "):
        inside = l.startswith("## Package Map")
    if inside and not done and l.count("|") >= 4 and "\`$NONFROZEN\`" in l.split("|")[2]:
        l = l.replace("\`$NONFROZEN\`", "\`llm4s-no-such-module\`", 1)
        done = True
    out.append(l)
write("\n".join(out))
PY
expect_fail "the Package Map names a module the build does not have" "the Package Map names llm4s-no-such-module, which is not a project"

fresh
edit docs/reference/v1-scope.md <<PY
# delete every Package Map row that names one non-frozen module: it is published but has no row
out = []
inside = False
for l in text.split("\n"):
    if l.startswith("## "):
        inside = l.startswith("## Package Map")
    if inside and l.count("|") >= 4 and "\`$NONFROZEN\`" in l.split("|")[2]:
        continue
    out.append(l)
write("\n".join(out))
PY
expect_fail "a published module with no Package Map row" "$NONFROZEN is published by build.sbt but has no row in the Package Map"

# ---------------------------------------------------------------- the build against itself
fresh
edit build.sbt <<PY
write(re.sub(r'(val stabilityTierModules\s*=\s*Seq\()([^)]*)(\))',
             lambda m: m.group(1) + ", ".join(x for x in m.group(2).split(",") if '"$FROZEN_DIR"' not in x) + m.group(3), text))
PY
expect_fail "stabilityTierModules misses a frozen module" "stabilityTierModules misses $FROZEN"

fresh
edit build.sbt <<PY
write(re.sub(r'(val stabilityTierModules\s*=\s*Seq\()([^)]*)(\))',
             lambda m: m.group(1) + m.group(2).rstrip() + ', "$NONFROZEN_DIR"' + m.group(3), text))
PY
expect_fail "stabilityTierModules names a module that is not frozen" "stabilityTierModules names $NONFROZEN"

fresh
edit build.sbt <<PY
# drop one module from frozenDependencyCheck's list
write(re.sub(r'^\s*"$FROZEN_LAST"\s*->[^\n]*\n', '', text, count=1, flags=re.M))
PY
expect_fail "frozenDependencyCheck misses a frozen module" "frozenDependencyCheck is not called with $FROZEN_LAST"

fresh
edit build.sbt <<PY
# a new mimaFrozen call for a module the docs call Beta
write(text.replace('mimaFrozen("$FROZEN")', 'mimaFrozen("$FROZEN"),\n    mimaFrozen("$NONFROZEN")', 1))
PY
expect_fail "the build freezes a module the Package Map calls Beta" "$NONFROZEN is frozen in build.sbt (mimaFrozen) but no Package Map row marks it Frozen"

fresh
edit build.sbt <<PY
write(text.replace('name := "$NONFROZEN"', 'name := "$NONFROZEN-renamed"', 1))
PY
expect_fail "a module renamed in the build but not in the docs" "the Package Map names $NONFROZEN, which is not a project in build.sbt"

# ---------------------------------------------------------------- the prose lists
fresh
edit docs/reference/compatibility-policy.md <<PY
write(text.replace("\`$FROZEN\`", "", 1))
PY
expect_fail "compatibility-policy.md drops a frozen module" "\"The Frozen modules are\" does not list $FROZEN"

fresh
edit docs/reference/compatibility-policy.md <<PY
write(text.replace("The Frozen modules are ", "The Frozen modules are \`$NONFROZEN\`, ", 1))
PY
expect_fail "compatibility-policy.md lists a module the build does not freeze" "\"The Frozen modules are\" lists $NONFROZEN"

fresh
edit docs/reference/api-stability.md <<PY
# the 'It covers ...' list loses a module
i = text.index("It covers")
j = text.index("**", i)
seg = text[i:j].replace("\`$FROZEN\`", "", 1)
write(text[:i] + seg + text[j:])
PY
expect_fail "api-stability.md's stabilityTierCheck list misses a module" "\"It covers\" does not list $FROZEN"

fresh
edit docs/reference/api-stability.md <<PY
# the MiMa table loses a module's row
write("\n".join(l for l in text.split("\n") if not (l.startswith("|") and "\`$FROZEN\`" in l)))
PY
expect_fail "api-stability.md's MiMa table misses a module" "the \"What MiMa Covers\" table does not list $FROZEN"

# ---------------------------------------------------------------- harmless changes pass
fresh
edit docs/reference/v1-scope.md <<PY
# extra spaces around every cell of the Package Map, and a prose sentence outside the table that names a
# module the build does not have (history is allowed)
out = []
inside = False
for l in text.split("\n"):
    if l.startswith("## Package Map"): inside = True
    elif l.startswith("## "): inside = False
    if inside and l.startswith("|"):
        l = "|".join("  " + c.strip() + "   " if c.strip() else c for c in l.split("|"))
    out.append(l)
write("\n".join(out) + "\nThe old \`llm4s-long-gone-module\` was folded into core.\n")
PY
expect_pass "extra spaces in the table and a historical module name in prose"

fresh
edit docs/reference/v1-scope.md <<PY
# every Package Map row of a published module is opted out: the marker silences tier comparisons,
# it must not turn the artifact "undocumented" for the published-coverage check (Codex review, iter 2)
out = []
marked = 0
for l in text.split("\n"):
    cells = l.split("|")
    if l.lstrip().startswith("|") and len(cells) >= 4 and "\`$PUBMOD\`" in cells[2]:
        l = l.rstrip() + " <!-- tier-drift: ignore -->"
        marked += 1
    out.append(l)
if not marked:
    sys.exit("test setup: no Package Map row targets $PUBMOD")
write("\n".join(out))
PY
expect_pass "opted-out rows still cover their published artifact"

fresh
edit docs/reference/v1-scope.md <<PY
# a row marked tier-drift: ignore may name anything
write(text.replace("## Package Map", "## Package Map", 1).replace("\n| \`types\` | \`llm4s-core\` | Frozen at 1.0 |",
      "\n| \`types\` | \`llm4s-core\` | Frozen at 1.0 |\n| \`scratch\` | \`llm4s-scratch-only\` | Beta | <!-- tier-drift: ignore -->", 1))
PY
if grep -qF "tier-drift: ignore" "$WORK/repo/docs/reference/v1-scope.md"; then
  expect_pass "a row marked tier-drift: ignore"
else
  echo "FAIL [a row marked tier-drift: ignore] test setup: the marker row was not inserted (the 'types' row changed shape)"; FAILED=$((FAILED + 1))
fi

# ---------------------------------------------------------------- one case per direction of each comparison
fresh
edit build.sbt <<PY
# frozenDependencyCheck is called with a module that is not frozen
m = re.search(r'^(\s*)"llm4s-core"\s*->', text, re.M)
write(text[:m.start()] + m.group(1) + '"$NONFROZEN" -> resolved((core / update).value, (core / libraryDependencies).value),\n' + text[m.start():])
PY
expect_fail "frozenDependencyCheck is called with a module that is not frozen" "frozenDependencyCheck is called with $NONFROZEN, which is not frozen"

fresh
edit docs/reference/api-stability.md <<PY
# the What MiMa Covers table lists a module that is not frozen
lines = text.split("\n")
start = next(i for i, l in enumerate(lines) if re.match(r"^##\s+What MiMa Covers\s*\$", l))
end = next((i for i in range(start + 1, len(lines)) if re.match(r"^##\s", lines[i])), len(lines))
last = max(i for i in range(start + 1, end) if lines[i].lstrip().startswith("|"))
lines.insert(last + 1, "| \`modules/$NONFROZEN_DIR\` | \`$NONFROZEN\` |")
write("\n".join(lines))
PY
expect_fail "the MiMa table lists a module that is not frozen" "the \"What MiMa Covers\" table lists $NONFROZEN, which build.sbt does not freeze"

fresh
edit docs/reference/v1-scope.md <<PY
# a non-frozen module's tier is a word that is no tier
out = []
for l in text.split("\n"):
    cells = l.split("|")
    if len(cells) >= 5 and "\`$NONFROZEN\`" in cells[2]:
        cells[3] = " Gamma "
        l = "|".join(cells)
    out.append(l)
write("\n".join(out))
PY
expect_fail "a non-frozen module with an unknown tier" "has no tier"

fresh
edit docs/reference/v1-scope.md <<PY
# "Not Frozen yet" must not be read as Frozen: the tier word has to start the cell
out = []
for l in text.split("\n"):
    cells = l.split("|")
    if len(cells) >= 5 and "\`$NONFROZEN\`" in cells[2]:
        cells[3] = " Not Frozen yet "
        l = "|".join(cells)
    out.append(l)
write("\n".join(out))
PY
expect_fail "a tier cell that only mentions Frozen" "has no tier"

fresh
edit docs/reference/v1-scope.md <<PY
# "Frozen at 2.0" is not the promised label "Frozen at 1.0": it must be reported as an unknown tier,
# never read as Frozen - with the old prefix match a frozen module's rows so rewritten still passed
# (Codex review, iter 3)
out = []
changed = 0
for l in text.split("\n"):
    cells = l.split("|")
    if len(cells) >= 5 and "\`$FROZEN\`" in cells[2] and "Frozen at 1.0" in cells[3]:
        cells[3] = cells[3].replace("Frozen at 1.0", "Frozen at 2.0")
        l = "|".join(cells)
        changed += 1
    out.append(l)
if not changed:
    sys.exit("test setup: no Frozen at 1.0 row targets $FROZEN")
write("\n".join(out))
PY
expect_fail "a tier cell saying Frozen at 2.0 is an unknown tier, not Frozen" "has no tier"

# ---------------------------------------------------------------- a commented-out entry is not live (Codex review)
fresh
edit build.sbt <<PY
# a block-commented mimaFrozen call must leave the frozen set
write(text.replace('mimaFrozen("$FROZEN")', '/* mimaFrozen("$FROZEN") */', 1))
PY
expect_fail "a block-commented mimaFrozen call is not frozen any more" "which build.sbt does not freeze"

fresh
edit build.sbt <<PY
# Scala block comments nest: the first */ closes only the inner comment, so a mimaFrozen call after it
# is still commented out. A stripper that stops at the first */ would keep the call live and pass here.
write(text.replace('mimaFrozen("$FROZEN")', '/* outer /* inner */ mimaFrozen("$FROZEN") still comment */', 1))
PY
expect_fail "a mimaFrozen call inside a nested block comment is not frozen any more" "which build.sbt does not freeze"

fresh
edit build.sbt <<PY
# a line-commented frozenDependencyCheck entry must leave D
m = re.search(r'^(\s*)("$FROZEN"\s*->)', text, re.M)
write(text[:m.start(2)] + "// " + text[m.start(2):])
PY
expect_fail "a line-commented frozenDependencyCheck entry is not checked any more" "frozenDependencyCheck is not called with $FROZEN"

fresh
edit build.sbt <<PY
# a trailing comment on a live call changes nothing
write(text.replace('mimaFrozen("$FROZEN")', 'mimaFrozen("$FROZEN") // the frozen set, see docs/reference/v1-scope.md', 1))
PY
expect_pass "a trailing comment on a live mimaFrozen call still counts"

# ---------------------------------------------------------------- a page the check cannot read must not pass
fresh
edit docs/reference/v1-scope.md <<PY
write(text.replace("## Package Map", "## Packages"))
PY
expect_exit2 "the Package Map heading is gone" "Package Map"

fresh
edit docs/reference/compatibility-policy.md <<PY
write(text.replace("The Frozen modules are", "The following modules are frozen:"))
PY
expect_exit2 "the compatibility policy's list changed shape" "compatibility-policy.md"

fresh
rm "$WORK/repo/docs/reference/api-stability.md"
expect_exit2 "a page is missing" "api-stability.md"

echo
echo "$PASSED passed, $FAILED failed"
[ "$FAILED" -eq 0 ]

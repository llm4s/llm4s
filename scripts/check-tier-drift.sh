#!/usr/bin/env bash
# Fails when the stability tiers the docs state have drifted from the build (#1281).
#
# The release slice of the modularisation programme (#1126) records a module's tier "in code, and not only in
# docs/reference/v1-scope.md, where it drifts". The code side is enforced by sbt: `stabilityTierCheck` needs
# every top-level public type of a frozen module to carry @Stable or @Experimental, `frozenDependencyCheck`
# keeps a frozen module off the heavy dependencies, and `publishedArtifactsCheck` needs every published
# artifact to be *mentioned* in v1-scope.md and installation.md. None of them reads the tier a page states,
# and the frozen set is written down in seven places, only some of which are derived from another. This check
# compares them. It reads text only (no sbt, no compilation), so it runs in the `quick-checks` job first.
#
# The build's frozen set is the `mimaFrozen("llm4s-...")` call sites in build.sbt: the modules whose binary
# compatibility MiMa will enforce once the 0.5.0 baseline is set. Everything else is compared with it:
#
#   1. build.sbt, against itself
#        - a frozen module is a published project (not `publish / skip`);
#        - `stabilityTierModules` is the frozen set minus the modules the comment above `stabilityTierCheck`
#          names after "minus" (today `llm4s-agent`, whose tier waits on #1266);
#        - the modules `frozenDependencyCheck` is called with are exactly the frozen set.
#   2. docs/reference/v1-scope.md, the Package Map table (package | target module | tier)
#        - every `llm4s-*` it names as a target module is a project in build.sbt;
#        - a frozen module has at least one row whose tier is "Frozen at 1.0", and a module that is not
#          frozen has none (a module may host rows of several tiers: `llm4s-agent` has a Frozen `agent` row
#          and a Beta `assistant` row, `llm4s-openai-compatible` Frozen clients and Beta dialects);
#        - every published `llm4s-*` artifact has a row in the table, not just a mention somewhere on the page.
#   3. the prose that repeats the frozen set
#        - docs/reference/compatibility-policy.md, the sentence "The Frozen modules are ...";
#        - docs/reference/api-stability.md, the "What MiMa Covers" table (the frozen set) and the sentence
#          "It covers ..." about `stabilityTierCheck` (the set `stabilityTierModules` names).
#
# A check that cannot find what it compares fails with exit 2 rather than passing: if a page changes shape
# (a renamed heading, a reworded sentence), update this script in the same change.
#
# Not checked, on purpose: the tier of a module from anywhere but the Package Map; module names that prose
# merely mentions; which package a row covers (that is `stabilityTierCheck`'s job); installation.md.
#
# Opt out of a Package Map row by putting `tier-drift: ignore` on its line (an HTML comment is enough).
#
# Usage: scripts/check-tier-drift.sh [REPO_ROOT]     (REPO_ROOT defaults to this script's repository)
# Exit 0 = consistent. 1 = drift: `file:line: what is wrong`, one per line. 2 = a page could not be read.
set -euo pipefail

REPO_ROOT=""
while [ $# -gt 0 ]; do
  case "$1" in
    -h|--help) sed -n '2,/^set -euo/p' "${BASH_SOURCE[0]}" | sed '$d'; exit 0 ;;
    -*) echo "unknown option: $1" >&2; exit 2 ;;
    *) REPO_ROOT="$1"; shift ;;
  esac
done
[ -n "$REPO_ROOT" ] || REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
command -v python3 >/dev/null 2>&1 || { echo "check-tier-drift.sh needs python3" >&2; exit 2; }

exec python3 - "$REPO_ROOT" <<'PY'
import os
import re
import sys

root = sys.argv[1]
IGNORE = "tier-drift: ignore"
BUILD = "build.sbt"
SCOPE = "docs/reference/v1-scope.md"
POLICY = "docs/reference/compatibility-policy.md"
STABILITY = "docs/reference/api-stability.md"

drift = []   # (file, line, message)
fatal = []   # messages: something the check relies on is missing, so it cannot say the tiers agree


def strip_scala_comments(text):
    # Blank out `//` line comments and `/* */` block comments (nested, as Scala allows), outside
    # double-quoted strings, keeping every newline, so a commented-out `mimaFrozen(...)` or
    # `"llm4s-..." ->` entry is not read as live and line numbers in messages stay right.
    out = []
    i, n = 0, len(text)
    in_str = in_line = in_triple = False
    depth = 0
    while i < n:
        c = text[i]
        nxt = text[i + 1] if i + 1 < n else ""
        if in_triple:
            out.append(c)
            if c == '"' and text[i:i + 3] == '"""':
                out.append('""')
                in_triple = False
                i += 2
            i += 1
            continue
        if in_line:
            if c == "\n":
                in_line = False
                out.append(c)
            else:
                out.append(" ")
        elif depth:
            if c == "\n":
                out.append(c)
            elif c == "*" and nxt == "/":
                depth -= 1
                out.append("  ")
                i += 1
            elif c == "/" and nxt == "*":
                depth += 1
                out.append("  ")
                i += 1
            else:
                out.append(" ")
        elif in_str:
            out.append(c)
            if c == "\\":
                out.append(nxt)
                i += 1
            elif c == '"':
                in_str = False
        else:
            if c == '"' and text[i:i + 3] == '"""':
                in_triple = True
                out.append('"""')
                i += 2
            elif c == '"':
                in_str = True
                out.append(c)
            elif c == "/" and nxt == "/":
                in_line = True
                out.append("  ")
                i += 1
            elif c == "/" and nxt == "*":
                depth = 1
                out.append("  ")
                i += 1
            else:
                out.append(c)
        i += 1
    return "".join(out)


def read(rel, scala=False):
    try:
        with open(os.path.join(root, rel), encoding="utf-8") as f:
            text = f.read()
        if scala:
            text = strip_scala_comments(text)
        return text.split("\n")
    except OSError as e:
        fatal.append(f"{rel}: cannot read ({e.strerror or e})")
        return []


def tokens(text):
    return re.findall(r"`(llm4s-[A-Za-z0-9-]+)`", text)


# ------------------------------------------------------------------------------------------ build.sbt
build_raw = read(BUILD)              # with comments: the exemption list lives in a comment above stabilityTierCheck
build = strip_scala_comments("\n".join(build_raw)).split("\n") if build_raw else []  # without comments: a commented-out entry is not live

proj_re = re.compile(r'^lazy val\s+(\w+)\s*=\s*\(project\s+in\s+file\("([^"]+)"\)\)')
projects = []
cur = None
for i, l in enumerate(build, 1):
    m = proj_re.match(l)
    if m:
        cur = {"val": m.group(1), "path": re.sub("/+", "/", m.group(2)), "line": i, "name": None, "skip": False}
        projects.append(cur)
        continue
    if re.match(r"^(lazy val|val|def|object|class)\s", l):
        cur = None
    if cur is not None:
        nm = re.search(r'\bname\s*:=\s*"([^"]+)"', l)
        if nm and cur["name"] is None:
            cur["name"] = nm.group(1)
        if re.search(r"publish\s*/\s*skip\s*:=\s*true", l):
            cur["skip"] = True

if build and len(projects) != len(re.findall(r"\(project\s+in\s+file\(", "\n".join(build))):
    fatal.append(f"{BUILD}: found {len(projects)} projects but the file defines more: a project definition changed shape")

names = {p["name"] for p in projects if p["name"] and p["name"].startswith("llm4s-")}
published = {p["name"] for p in projects if p["name"] and p["name"].startswith("llm4s-") and not p["skip"]}
by_dir = {p["path"][len("modules/"):]: p["name"] for p in projects if p["path"].startswith("modules/") and p["name"]}

frozen = {}
for i, l in enumerate(build, 1):
    if l.lstrip().startswith("//"):
        continue
    for m in re.findall(r'mimaFrozen\("([^"]+)"\)', l):
        frozen.setdefault(m, i)
if build and not frozen:
    fatal.append(f"{BUILD}: no mimaFrozen(\"llm4s-...\") call found: the frozen set cannot be read")
F = set(frozen)

# stabilityTierModules (module directories) and the exceptions the comment above stabilityTierCheck names
tier_line = next((i for i, l in enumerate(build, 1) if re.match(r"^val stabilityTierModules\s*=", l)), None)
T = None
if tier_line is not None:
    text = " ".join(build[tier_line - 1:tier_line + 3])
    m = re.search(r"Seq\(([^)]*)\)", text)
    dirs = re.findall(r'"([^"]+)"', m.group(1)) if m else []
    T = set()
    for d in dirs:
        if d in by_dir:
            T.add(by_dir[d])
        else:
            drift.append((BUILD, tier_line, f"stabilityTierModules names \"{d}\", which is not a project under modules/ in this file"))
elif build:
    fatal.append(f"{BUILD}: `val stabilityTierModules = Seq(...)` not found")

check_line = next((i for i, l in enumerate(build, 1) if re.match(r"^lazy val stabilityTierCheck\b", l)), None)
exceptions = set()
if check_line is not None:
    j = check_line - 1
    comment = []
    # the exemption list is itself a comment, so it is read from the unstripped text (same line numbers)
    while j >= 1 and build_raw[j - 1].lstrip().startswith("//"):
        comment.insert(0, build_raw[j - 1].lstrip()[2:].strip())
        j -= 1
    m = re.search(r"minus\s+((?:`llm4s-[a-z0-9-]+`(?:\s*(?:,|and)\s*)?)+)", " ".join(comment))
    if m:
        exceptions = set(tokens(m.group(1)))
elif build:
    fatal.append(f"{BUILD}: `lazy val stabilityTierCheck` not found")

# the modules frozenDependencyCheck is called with
fd_line = next((i for i, l in enumerate(build, 1) if "FrozenDependencies.check(" in l), None)
D = set()
if fd_line is not None:
    k = fd_line
    while k <= len(build) and "streams.value.log" not in build[k - 1]:
        D.update(re.findall(r'"(llm4s-[a-z0-9-]+)"\s*->', build[k - 1]))
        k += 1
else:
    if build:
        fatal.append(f"{BUILD}: `FrozenDependencies.check(` not found")

for m in sorted(F):
    if m not in names:
        drift.append((BUILD, frozen[m], f"mimaFrozen(\"{m}\") names no project: no `name := \"{m}\"` in this file"))
    elif m not in published:
        drift.append((BUILD, frozen[m], f"{m} is frozen but its project sets `publish / skip := true`"))

if T is not None:
    expected_T = F - exceptions
    for m in sorted(expected_T - T):
        drift.append((BUILD, tier_line, f"stabilityTierModules misses {m}, which is frozen (mimaFrozen) and is not one of the "
                                         f"modules the comment above stabilityTierCheck exempts"))
    for m in sorted(T - expected_T):
        drift.append((BUILD, tier_line, f"stabilityTierModules names {m}, which is not frozen (no mimaFrozen call) or is exempted "
                                         f"by the comment above stabilityTierCheck"))
    for m in sorted(exceptions - F):
        drift.append((BUILD, check_line, f"the comment above stabilityTierCheck exempts {m}, which is not frozen"))

if fd_line is not None:
    for m in sorted(F - D):
        drift.append((BUILD, fd_line, f"frozenDependencyCheck is not called with {m}, which is frozen (mimaFrozen)"))
    for m in sorted(D - F):
        drift.append((BUILD, fd_line, f"frozenDependencyCheck is called with {m}, which is not frozen (no mimaFrozen call)"))

# ------------------------------------------------------------------------------------------ v1-scope.md
scope = read(SCOPE)
rows = []   # (line, target modules, tier)
ignored = set()  # modules named by rows opted out with the IGNORE marker
start = next((i for i, l in enumerate(scope) if re.match(r"^##\s+Package Map\s*$", l)), None)
if start is None:
    if scope:
        fatal.append(f"{SCOPE}: no `## Package Map` heading: the page changed shape")
else:
    for j in range(start + 1, len(scope)):
        l = scope[j]
        if re.match(r"^##\s", l):
            break
        if not l.lstrip().startswith("|"):
            continue
        cells = [c.strip() for c in l.strip().strip("|").split("|")]
        if len(cells) < 3 or set(cells[0]) <= set("-: ") or cells[0].lower() == "package":
            continue
        if IGNORE in l:
            # An opted-out row is silent for every tier comparison, but its artifacts still count as
            # having a row: the marker must not turn a published artifact "undocumented" (it still
            # cannot stand in for a frozen module's Frozen row; that comparison stays strict).
            ignored.update(tokens(cells[1]))
            continue
        t = re.match(r"\W*(Frozen|Beta|Experimental)\b", cells[2])  # the tier word starts the cell: "Not Frozen yet" is no tier
        if not t:
            drift.append((SCOPE, j + 1, f"Package Map row for {cells[0].strip('`')[:40]} has no tier (Frozen at 1.0, Beta or Experimental)"))
            continue
        rows.append((j + 1, tokens(cells[1]), t.group(1)))
    if not rows:
        fatal.append(f"{SCOPE}: the Package Map has no readable rows")

tiers = {}      # module -> set of tiers
first_row = {}  # module -> line of its first row
frozen_rows = {}
for line, mods, tier in rows:
    for m in mods:
        tiers.setdefault(m, set()).add(tier)
        first_row.setdefault(m, line)
        if tier == "Frozen":
            frozen_rows.setdefault(m, line)
        if build and m not in names:
            drift.append((SCOPE, line, f"the Package Map names {m}, which is not a project in {BUILD}"))

if rows and build:
    for m in sorted(F):
        if "Frozen" not in tiers.get(m, set()):
            where = f"its rows say {'/'.join(sorted(tiers[m]))}" if m in tiers else "it has no row"
            drift.append((SCOPE, first_row.get(m, start + 1 if start is not None else 1),
                          f"{m} is frozen in {BUILD} (mimaFrozen) but no Package Map row marks it Frozen at 1.0: {where}"))
    for m in sorted(frozen_rows):
        if m not in F and m in names:
            drift.append((SCOPE, frozen_rows[m], f"the Package Map marks {m} Frozen at 1.0 but {BUILD} has no mimaFrozen call for it"))
    for m in sorted(published - set(tiers) - ignored):
        drift.append((SCOPE, start + 1 if start is not None else 1,
                      f"{m} is published by {BUILD} but has no row in the Package Map (a mention elsewhere on the page is not a tier)"))

# ------------------------------------------------------------------------------------------ compatibility-policy.md
policy = read(POLICY)
pol_line = next((i for i, l in enumerate(policy) if "The Frozen modules are" in l), None)
if pol_line is None:
    if policy:
        fatal.append(f"{POLICY}: no sentence starting \"The Frozen modules are\": the page changed shape")
else:
    para = []
    for l in policy[pol_line:]:
        if not l.strip():
            break
        para.append(l)
    text = " ".join(para)
    text = text[text.index("The Frozen modules are") + len("The Frozen modules are"):]
    text = text.split(";")[0]
    listed = set(tokens(text))
    if not listed:
        fatal.append(f"{POLICY}:{pol_line + 1}: \"The Frozen modules are\" is followed by no `llm4s-*` module")
    elif F:
        for m in sorted(F - listed):
            drift.append((POLICY, pol_line + 1, f"\"The Frozen modules are\" does not list {m}, which is frozen (mimaFrozen in {BUILD})"))
        for m in sorted(listed - F):
            drift.append((POLICY, pol_line + 1, f"\"The Frozen modules are\" lists {m}, which {BUILD} does not freeze (no mimaFrozen call)"))

# ------------------------------------------------------------------------------------------ api-stability.md
stab = read(STABILITY)
if stab:
    cov = next((i for i, l in enumerate(stab) if "It covers" in l), None)
    if cov is None:
        fatal.append(f"{STABILITY}: no sentence starting \"It covers\" (the modules stabilityTierCheck covers): the page changed shape")
    else:
        text = " ".join(stab[cov:cov + 6])
        text = text[text.index("It covers") + len("It covers"):]
        cut = [text.find(x) for x in ("**", ". ") if text.find(x) >= 0]
        text = text[:min(cut)] if cut else text
        listed = set(tokens(text))
        if not listed:
            fatal.append(f"{STABILITY}:{cov + 1}: \"It covers\" is followed by no `llm4s-*` module")
        elif T is not None:
            for m in sorted(T - listed):
                drift.append((STABILITY, cov + 1, f"\"It covers\" does not list {m}, which stabilityTierModules covers in {BUILD}"))
            for m in sorted(listed - T):
                drift.append((STABILITY, cov + 1, f"\"It covers\" lists {m}, which stabilityTierModules does not cover in {BUILD}"))

    mima = next((i for i, l in enumerate(stab) if re.match(r"^##\s+What MiMa Covers\s*$", l)), None)
    if mima is None:
        fatal.append(f"{STABILITY}: no `## What MiMa Covers` heading: the page changed shape")
    else:
        listed = set()
        for l in stab[mima + 1:]:
            if re.match(r"^##\s", l):
                break
            if l.lstrip().startswith("|"):
                cells = [c.strip() for c in l.strip().strip("|").split("|")]
                if len(cells) >= 2:
                    listed.update(tokens(cells[1]))
        if not listed:
            fatal.append(f"{STABILITY}:{mima + 1}: the \"What MiMa Covers\" table names no `llm4s-*` artifact")
        elif F:
            for m in sorted(F - listed):
                drift.append((STABILITY, mima + 1, f"the \"What MiMa Covers\" table does not list {m}, which is frozen (mimaFrozen in {BUILD})"))
            for m in sorted(listed - F):
                drift.append((STABILITY, mima + 1, f"the \"What MiMa Covers\" table lists {m}, which {BUILD} does not freeze (no mimaFrozen call)"))

# ------------------------------------------------------------------------------------------ result
if fatal:
    for f in fatal:
        print(f, file=sys.stderr)
    print("check-tier-drift.sh cannot compare the tiers: fix the page or update scripts/check-tier-drift.sh to read it.",
          file=sys.stderr)
    sys.exit(2)

if drift:
    for f, line, msg in sorted(set(drift)):
        print(f"{f}:{line}: {msg}", file=sys.stderr)
    print(f"\n{len(set(drift))} stability tier mismatch(es). The build is the source of truth for which modules are\n"
          f"frozen: the `mimaFrozen(\"llm4s-...\")` call sites in {BUILD}. Fix the page, or - if the build is what is\n"
          f"wrong - change the build and every page together. A Package Map row may opt out with `{IGNORE}`\n"
          f"(an HTML comment on its line is enough). See docs/reference/api-stability.md.", file=sys.stderr)
    sys.exit(1)

print(f"Stability tiers consistent: {len(F)} frozen modules ({', '.join(sorted(F))}); "
      f"{len(rows)} Package Map rows; {len(published)} published artifacts all have a row.")
PY

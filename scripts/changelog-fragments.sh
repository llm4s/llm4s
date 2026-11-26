#!/usr/bin/env bash
# Changelog fragments (#3121): each pull request writes its CHANGELOG entry as its own file,
# changelog.d/<area>/<issue>.md, so two pull requests in one area never conflict on CHANGELOG.md. At release,
# `assemble` folds every fragment into CHANGELOG.md's [Unreleased] section and deletes it.
#
# A fragment is one or more entries exactly as they read in CHANGELOG.md: each starts `- **Added**`, `**Changed**`,
# `**Removed**` or `**Fixed**`, is at most 2 lines (a continuation line indented two spaces) and 900 characters, and
# links its issue, [#N](https://github.com/llm4s/llm4s/issues/N). Optional front matter adds a *Breaking changes* line,
# which also links its migration note (docs/reference/migration.md#...):
#
#   ---
#   breaking: `llm4s-foo` (Experimental): ... ([migration](docs/reference/migration.md#...)) [#N](https://github.com/llm4s/llm4s/issues/N).
#   ---
#   - **Changed** ... [#N](https://github.com/llm4s/llm4s/issues/N).
#
# A follow-up to unreleased work edits that issue's fragment; it never adds another. Relative links are written from
# the repository root, as in CHANGELOG.md, and must name a file that exists.
#
# `check` validates every fragment: Quick Checks and scripts/check-docs.sh run it. `assemble` appends each area's
# fragments, in issue order, to the end of that area's list under [Unreleased] (adding the heading in its place if
# the list is missing), and each breaking line to `### Breaking changes`, then deletes the fragments; the release
# checklist (docs/reference/release.md) runs it before renaming [Unreleased]. scripts/test-changelog-fragments.sh
# tests both on sample fragments.
#
# `areas` prints each area's directory and heading, tab-separated, in CHANGELOG order (changelog.d/README.md's table
# must agree; the tests check it).
#
# Usage: scripts/changelog-fragments.sh check|assemble|areas [--root DIR]
#   --root DIR  the repository root holding CHANGELOG.md and changelog.d/ (default: this script's)
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODE="${1:-}"
[ $# -gt 0 ] && shift
while [ $# -gt 0 ]; do
  case "$1" in
    --root) ROOT="${2:?--root needs a directory}"; shift 2 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done
case "$MODE" in
  check|assemble|areas) ;;
  *) echo "usage: $0 check|assemble|areas [--root DIR]" >&2; exit 2 ;;
esac

export ROOT MODE
exec python3 - <<'PYEOF'
import os, pathlib, re, sys

root = pathlib.Path(os.environ["ROOT"])
mode = os.environ["MODE"]
frag_dir = root / "changelog.d"

# Directory -> the [Unreleased] heading it folds under, in CHANGELOG.md's order.
AREAS = [
    ("core", "Core"),
    ("capabilities", "Capabilities"),
    ("providers", "Providers"),
    ("observability", "Observability"),
    ("agent-runtime", "Agent runtime"),
    ("agent-harness", "Agent harness"),
    ("memory", "Memory"),
    ("tools", "Tools"),
    ("mcp", "MCP"),
    ("embeddings-rag", "Embeddings & RAG"),
    ("speech-image", "Speech & image"),
    ("configuration", "Configuration"),
    ("java-kotlin", "Java & Kotlin APIs"),
    ("streaming-integrations", "Streaming integrations"),
    ("security", "Security"),
    ("testkits", "Testkits"),
    ("docs", "Docs"),
    ("build-tooling", "Build & tooling"),
]
AREA_DIRS = dict(AREAS)
BREAKING = "Breaking changes"
MAX_LINES = 2
MAX_CHARS = 900
ENTRY_START = re.compile(r"^- \*\*(Added|Changed|Removed|Fixed)\*\* \S")
REL_LINK = re.compile(r"\]\(([^)#\s]+)(?:#[^)\s]*)?\)")

if mode == "areas":
    for d, title in AREAS:
        print(f"{d}\t{title}")
    sys.exit(0)

errors = []


def fail(path, message):
    errors.append(f"{path.relative_to(root).as_posix()}: {message}")


def issue_link(n):
    return f"[#{n}](https://github.com/llm4s/llm4s/issues/{n})"


def check_links(path, text):
    for target in REL_LINK.findall(text):
        if re.match(r"^[a-z]+:", target):
            continue
        if not (root / target).exists():
            fail(path, f"links {target}, which does not exist (relative links are written from the repository root)")


def check_entry(path, issue, entry, what):
    lines = entry.split("\n")
    if len(lines) > MAX_LINES:
        fail(path, f"{what} is {len(lines)} lines; an entry is at most {MAX_LINES} (detail goes in the docs)")
    if len(entry) > MAX_CHARS:
        fail(path, f"{what} is {len(entry)} characters; an entry is at most {MAX_CHARS}")
    if issue_link(issue) not in entry:
        fail(path, f"{what} does not link its issue as {issue_link(issue)}")
    check_links(path, entry)


def parse(path, issue):
    """Returns (breaking line or None, [entries]), recording any error."""
    text = path.read_text(encoding="utf-8")
    lines = text.rstrip("\n").split("\n")
    breaking = None
    if lines and lines[0] == "---":
        try:
            end = lines.index("---", 1)
        except ValueError:
            fail(path, "front matter opened with --- is never closed")
            return None, []
        fm, lines = lines[1:end], lines[end + 1:]
        for i, line in enumerate(fm):
            if line.startswith("breaking:") and breaking is None:
                breaking = line[len("breaking:"):].strip()
            elif line.startswith("  ") and breaking is not None:
                breaking += "\n  " + line.strip()
            else:
                fail(path, f"front matter line {i + 2} is not `breaking: <line>` or its indented continuation")
        if breaking is not None:
            if not breaking:
                fail(path, "`breaking:` is empty")
            else:
                check_entry(path, issue, breaking, "the breaking line")
                if "docs/reference/migration.md#" not in breaking:
                    fail(path, "the breaking line does not link its note in docs/reference/migration.md")
    while lines and lines[0] == "":
        lines.pop(0)
    entries = []
    for i, line in enumerate(lines):
        if line.startswith("- "):
            if not ENTRY_START.match(line):
                fail(path, f"an entry starts `- **Added**`, `**Changed**`, `**Removed**` or `**Fixed**`: {line[:60]}")
            entries.append(line)
        elif line.startswith("  ") and line.strip() and entries:
            entries[-1] += "\n  " + line.strip()
        else:
            fail(path, f"line {i + 1} is neither an entry (`- **Verb** ...`) nor an entry's continuation (two spaces)")
    if not entries:
        fail(path, "has no entry")
    for n, entry in enumerate(entries, 1):
        check_entry(path, issue, entry, f"entry {n}")
    return breaking, entries


# ---- collect and validate
fragments = {d: [] for d in AREA_DIRS}
breaking_lines = []
if frag_dir.is_dir():
    for path in sorted(frag_dir.rglob("*")):
        rel = path.relative_to(frag_dir)
        if path.is_dir() or rel.as_posix() == "README.md":
            continue
        parts = rel.parts
        if len(parts) != 2 or parts[0] not in AREA_DIRS:
            fail(path, "a fragment is changelog.d/<area>/<issue>.md, with <area> one of " + ", ".join(AREA_DIRS))
            continue
        m = re.fullmatch(r"([1-9][0-9]*)\.md", parts[1])
        if not m:
            fail(path, "a fragment is named for its issue: <issue number>.md")
            continue
        issue = int(m.group(1))
        breaking, entries = parse(path, issue)
        fragments[parts[0]].append((issue, path, entries))
        if breaking:
            breaking_lines.append((issue, "- " + breaking))

if errors:
    for e in errors:
        print(f"ERROR: {e}", file=sys.stderr)
    print(f"{len(errors)} changelog fragment error(s); the format is in scripts/changelog-fragments.sh", file=sys.stderr)
    sys.exit(1)

count = sum(len(v) for v in fragments.values())
if mode == "check":
    print(f"{count} changelog fragment(s) OK")
    sys.exit(0)

# ---- assemble into [Unreleased]
if count == 0:
    print("no changelog fragments to fold")
    sys.exit(0)
changelog = root / "CHANGELOG.md"
text = changelog.read_text(encoding="utf-8")
m = re.search(r"^## \[Unreleased\][^\n]*\n", text, re.M)
if not m:
    print("ERROR: CHANGELOG.md has no ## [Unreleased] section", file=sys.stderr)
    sys.exit(1)
start = m.end()
nxt = re.search(r"^## ", text[start:], re.M)
end = start + nxt.start() if nxt else len(text)
head, section, tail = text[:start], text[start:end], text[end:]

# The section is a preamble (the comment) and then `### Heading` blocks, each a list of entries.
blocks = re.split(r"(?m)^(?=### )", section)
preamble, blocks = blocks[0], blocks[1:]
by_title = {}
for b in blocks:
    title = b.split("\n", 1)[0][4:].strip()
    by_title[title] = b.strip("\n").split("\n", 1)[1].strip("\n") if "\n" in b.strip("\n") else ""

additions = {BREAKING: [line for _, line in sorted(breaking_lines)]}
for d, title in AREAS:
    additions[title] = [e for _, _, entries in sorted(fragments[d], key=lambda f: f[0]) for e in entries]

# Existing headings keep their order (a `### Highlights` stays first); a missing one with entries goes after the
# nearest heading that precedes it in `order`, or before the first one that follows it.
order = [BREAKING] + [t for _, t in AREAS]
titles = list(by_title)
for i, t in enumerate(order):
    if t in titles or not additions.get(t):
        continue
    before = [titles.index(p) for p in order[:i] if p in titles]
    after = [titles.index(p) for p in order[i + 1:] if p in titles]
    titles.insert(max(before) + 1 if before else (min(after) if after else len(titles)), t)
out = []
for t in titles:
    body = "\n".join(x for x in [by_title.get(t, "")] + additions.get(t, []) if x)
    out.append(f"### {t}\n\n{body}\n")
new_section = (preamble.rstrip("\n") + "\n\n" if preamble.strip() else "\n") + "\n".join(out) + "\n"
changelog.write_text(head + new_section + tail, encoding="utf-8")

for d in fragments:
    for _, path, _ in fragments[d]:
        path.unlink()
print(f"{count} changelog fragment(s) folded into CHANGELOG.md [Unreleased] and deleted")
PYEOF

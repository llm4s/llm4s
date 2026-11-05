#!/usr/bin/env bash
# Drafts the llm4s.org News post for a release from its CHANGELOG section (#2319), for a maintainer to edit.
#
#   scripts/new-release-post.sh <version>            write docs/_posts/<date>-llm4s-<version>.md
#   scripts/new-release-post.sh --check <version>    the release workflow's check: print a GitHub Actions warning
#                                                    when no post for <version> exists; always exits 0
#
# The draft is built from `## [<version>] - <date>` in CHANGELOG.md, and the post is dated with that date:
#   - front matter: title, description, image (the share image), tags: [release] and version;
#   - the section's `### Highlights` paragraph as the summary, or else its entries under "What is new";
#   - its `### Breaking changes` entries under "Upgrading", with the migration guide;
#   - the dependency line, from `maven_group` in docs/_data/project.yml and <version>;
#   - links to the CHANGELOG section and the GitHub release of the tag.
# Relative links in the CHANGELOG point at repository files; the draft links them on GitHub at the tag instead.
# Rewrite the summary and trim the lists before committing: a post says what is new and why it matters, briefly.
#
# Options (for the tests): --changelog FILE, --project FILE (docs/_data/project.yml), --out DIR (docs/_posts).
# An existing post is never overwritten. Exit code 0 = draft written (or --check done); 1 = no such CHANGELOG section,
# or the post exists; 2 = a usage error.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CHANGELOG="$REPO_ROOT/CHANGELOG.md"
PROJECT="$REPO_ROOT/docs/_data/project.yml"
OUT="$REPO_ROOT/docs/_posts"
MODE=draft
VERSION=""

usage() {
  echo "usage: new-release-post.sh [--check] [--changelog FILE] [--project FILE] [--out DIR] <version>" >&2
  exit 2
}

while [ $# -gt 0 ]; do
  case "$1" in
    --check) MODE=check; shift ;;
    --changelog) [ $# -ge 2 ] || usage; CHANGELOG="$2"; shift 2 ;;
    --project) [ $# -ge 2 ] || usage; PROJECT="$2"; shift 2 ;;
    --out) [ $# -ge 2 ] || usage; OUT="$2"; shift 2 ;;
    -*) usage ;;
    *) [ -z "$VERSION" ] || usage; VERSION="${1#v}"; shift ;;
  esac
done
[ -n "$VERSION" ] || usage
if ! [[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?$ ]]; then
  echo "new-release-post: not a release version: $VERSION" >&2
  exit 2
fi

if [ "$MODE" = check ]; then
  shopt -s nullglob
  posts=("$OUT"/*-llm4s-"$VERSION".md)
  if [ ${#posts[@]} -eq 0 ]; then
    echo "::warning::No News post for $VERSION in docs/_posts. Draft one with scripts/new-release-post.sh $VERSION, edit it and merge it to main (docs/reference/release.md)."
  else
    echo "News post for $VERSION: ${posts[0]#"$REPO_ROOT"/}"
  fi
  exit 0
fi

export CHANGELOG PROJECT OUT VERSION
exec python3 - <<'PYEOF'
import os, re, sys

changelog, project, out, version = (os.environ[k] for k in ("CHANGELOG", "PROJECT", "OUT", "VERSION"))
REPO = "https://github.com/llm4s/llm4s"

text = open(changelog, encoding="utf-8").read()
head = re.search(r"^## \[" + re.escape(version) + r"\] - (\d{4}-\d{2}-\d{2})[ \t]*$", text, re.M)
if not head:
    sys.stderr.write("new-release-post: CHANGELOG has no `## [%s] - YYYY-MM-DD` section\n" % version)
    sys.exit(1)
date = head.group(1)
nxt = re.search(r"^## ", text[head.end():], re.M)
section = text[head.end(): head.end() + nxt.start() if nxt else len(text)]

group = "org.llm4s"
for line in open(project, encoding="utf-8"):
    m = re.match(r'^maven_group:\s*"?([^"#\s]+)"?', line)
    if m:
        group = m.group(1)


def github_links(md):
    """A relative link names a repository file: link it on GitHub at the release tag."""
    def fix(m):
        target = m.group(1)
        if re.match(r"^(https?:|mailto:|#)", target):
            return m.group(0)
        return "](%s/blob/v%s/%s)" % (REPO, version, re.sub(r"^(\./)+", "", target))
    return re.sub(r"\]\(([^)\s]+)\)", fix, md)


areas, current = {}, None
order = []
for line in section.splitlines():
    m = re.match(r"^### (.+?)\s*$", line)
    if m:
        current = m.group(1)
        order.append(current)
        areas[current] = []
    elif current:
        areas[current].append(line)


def body(name):
    return "\n".join(areas.get(name, [])).strip()


summary = body("Highlights")
breaking = body("Breaking changes")
anchor = re.sub(r"[^a-z0-9 -]", "", ("%s - %s" % (version, date)).lower()).replace(" ", "-")

lines = [
    "---",
    'title: "LLM4S %s"' % version,
    'description: "What is new in LLM4S %s, what breaks and how to upgrade, with the dependency line for the release."'
    % version,
    "image: /image_assets/llm4s_logo.png",
    "tags: [release]",
    "version: %s" % version,
    "---",
    "",
    "<!-- DRAFT from CHANGELOG.md by scripts/new-release-post.sh: rewrite the summary, keep the lists short. -->",
    "",
]
if summary:
    lines += [github_links(summary), ""]
else:
    lines += ["<!-- One or two sentences: what is new in %s and why it matters. -->" % version, "", "## What is new", ""]
    for name in order:
        if name in ("Highlights", "Breaking changes") or not body(name):
            continue
        lines += ["### " + name, "", github_links(body(name)), ""]
lines += ["## Upgrading", ""]
if breaking:
    lines += [github_links(breaking), ""]
lines += [
    "Until the 1.0 binary-compatibility baseline is set, any release may break, patch releases included "
    "([compatibility policy](/reference/compatibility-policy#versions)). Every change to act on is in the "
    "[migration guide](/reference/migration).",
    "",
    "## Install",
    "",
    "```sbt",
    "libraryDependencies ++= Seq(",
    '  "%s" %%%% "llm4s-core"   %% "%s",' % (group, version),
    '  "%s" %%%% "llm4s-openai" %% "%s" // or the provider module you use' % (group, version),
    ")",
    "```",
    "",
    "Every artifact, with its tier: [installation guide](/getting-started/installation).",
    "",
    "Full list of changes: [CHANGELOG](%s/blob/v%s/CHANGELOG.md#%s) &middot; "
    "[GitHub release](%s/releases/tag/v%s)" % (REPO, version, anchor, REPO, version),
    "",
]

os.makedirs(out, exist_ok=True)
path = os.path.join(out, "%s-llm4s-%s.md" % (date, version))
if os.path.exists(path):
    sys.stderr.write("new-release-post: %s exists; edit it instead\n" % path)
    sys.exit(1)
with open(path, "w", encoding="utf-8") as f:
    f.write("\n".join(lines))
print("Drafted %s: edit it, then check it with scripts/check-docs-site-links.sh." % path)
PYEOF

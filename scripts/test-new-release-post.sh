#!/usr/bin/env bash
# Tests for scripts/new-release-post.sh (#2319): it drafts the 0.5.1 post from the real CHANGELOG section, with the
# front matter, the dependency line and the links a release post needs; it refuses a version with no CHANGELOG section
# and an existing post; and --check only warns. No network, no Jekyll.
set -uo pipefail
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SCRIPT="$REPO_ROOT/scripts/new-release-post.sh"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
fails=0
expect() { # name expected-exit actual-exit
  if [ "$2" = "$3" ]; then echo "ok: $1"; else echo "FAIL: $1 (exit $3, wanted $2)"; fails=$((fails + 1)); fi
}
has() { # name file fixed-string
  if grep -qF -- "$3" "$2"; then echo "ok: $1"; else echo "FAIL: $1: $2 lacks: $3"; fails=$((fails + 1)); fi
}

"$SCRIPT" --out "$WORK/posts" 0.5.1 >/dev/null; expect "drafts the 0.5.1 post" 0 $?
POST="$WORK/posts/2026-10-15-llm4s-0.5.1.md"
[ -f "$POST" ]; expect "named after the CHANGELOG date and the version" 0 $?

# The front matter: the block between the first two `---` lines.
sed -n '2,/^---$/p' "$POST" | sed '$d' > "$WORK/fm"
has "front matter: title" "$WORK/fm" 'title: "LLM4S 0.5.1"'
has "front matter: description" "$WORK/fm" 'description: "What is new in LLM4S 0.5.1'
has "front matter: share image" "$WORK/fm" 'image: /image_assets/llm4s_logo.png'
has "front matter: release tag" "$WORK/fm" 'tags: [release]'
has "front matter: version" "$WORK/fm" 'version: 0.5.1'
[ "$(head -1 "$POST")" = "---" ]; expect "front matter opens the file" 0 $?

has "dependency line for llm4s-core" "$POST" '"org.llm4s" %% "llm4s-core"   % "0.5.1",'
has "dependency line for a provider" "$POST" '"org.llm4s" %% "llm4s-openai" % "0.5.1"'
has "the CHANGELOG section's entries" "$POST" '`FileTools(backend)`'
has "its breaking changes under Upgrading" "$POST" '`ToolOutcome.Success` takes a `ToolContent`'
has "link to the CHANGELOG section" "$POST" 'https://github.com/llm4s/llm4s/blob/v0.5.1/CHANGELOG.md#051---2026-10-15'
has "link to the GitHub release" "$POST" 'https://github.com/llm4s/llm4s/releases/tag/v0.5.1'
has "relative CHANGELOG links point at GitHub at the tag" "$POST" '](https://github.com/llm4s/llm4s/blob/v0.5.1/docs/reference/migration.md#multimodal-content-parts-1913)'
! grep -qE '\]\(docs/' "$POST"; expect "no repository-relative link is left" 0 $?
! grep -q '^```scala' "$POST"; expect "no Scala block: the dependency line is fenced as sbt" 0 $?

# The maven group comes from docs/_data/project.yml.
printf 'maven_group: "com.example"\n' > "$WORK/project.yml"
"$SCRIPT" --out "$WORK/other" --project "$WORK/project.yml" 0.5.1 >/dev/null
has "group from project.yml" "$WORK/other/2026-10-15-llm4s-0.5.1.md" '"com.example" %% "llm4s-core"   % "0.5.1",'

# A section with highlights uses them as the summary.
printf '# Changelog\n\n## [9.0.0] - 2030-01-02\n\n### Highlights\n\nBig news, see [x](docs/a.md).\n\n### Core\n\n- **Added** y\n\n## [8.0.0] - 2029-01-01\n' > "$WORK/CHANGELOG.md"
"$SCRIPT" --out "$WORK/hl" --changelog "$WORK/CHANGELOG.md" v9.0.0 >/dev/null; expect "a leading v is accepted" 0 $?
has "highlights are the summary" "$WORK/hl/2030-01-02-llm4s-9.0.0.md" 'Big news, see [x](https://github.com/llm4s/llm4s/blob/v9.0.0/docs/a.md).'
! grep -qF '**Added** y' "$WORK/hl/2030-01-02-llm4s-9.0.0.md"; expect "with highlights, the entries are left out" 0 $?

"$SCRIPT" --out "$WORK/posts" 0.5.1 >/dev/null 2>&1; expect "an existing post is not overwritten" 1 $?
"$SCRIPT" --out "$WORK/posts" 9.9.9 >/dev/null 2>&1; expect "a version without a CHANGELOG section fails" 1 $?
"$SCRIPT" --out "$WORK/posts" not-a-version >/dev/null 2>&1; expect "a malformed version is a usage error" 2 $?
"$SCRIPT" >/dev/null 2>&1; expect "no version is a usage error" 2 $?

out="$("$SCRIPT" --check --out "$WORK/posts" 0.5.1)"; expect "--check passes when the post exists" 0 $?
[[ "$out" != *"::warning::"* ]]; expect "--check does not warn when the post exists" 0 $?
out="$("$SCRIPT" --check --out "$WORK/posts" v0.4.1)"; expect "--check never fails the release" 0 $?
[[ "$out" == "::warning::No News post for 0.4.1"* ]]; expect "--check warns when the post is missing" 0 $?

# The site's own posts: one per back-filled release, found by --check.
for v in 0.5.0 0.5.1; do
  out="$("$SCRIPT" --check "$v")"
  [[ "$out" != *"::warning::"* ]]; expect "docs/_posts has the $v post" 0 $?
done

if [ "$fails" -eq 0 ]; then echo "All new-release-post cases passed"; else echo "$fails failure(s)"; exit 1; fi

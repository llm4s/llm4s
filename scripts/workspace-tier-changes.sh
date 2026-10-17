#!/usr/bin/env bash
# Decides whether a change touches the workspace, so CI runs the @Workspace tier on the pull request (#1982).
#
# The tier builds the workspace-runner image and runs the live suites against it: too slow for every PR, so it runs on
# a PR only when the diff touches something those suites exercise. That is the runner and its shared protocol
# (modules/workspace), the whole agent harness package (the suites import it by wildcard and drive its backends, tools
# and middleware against the container), the agent-testkit contracts they run, the @Workspace suites and their tag,
# the sample they drive, the image build, this script, and the CI jobs that decide and run the tier or require it (All
# Tests Pass). A change to ci.yml counts only when it changes one of those jobs (compared as parsed YAML), since
# ci.yml changes for many other reasons. Likewise build.sbt counts only when a changed line, or the project definition
# git names in its hunk header, mentions the workspace (the workspace projects, the `testWorkspace` alias, the image
# tag the suites are given), and project/Dependencies.scala only when a changed line names cask or Java-WebSocket,
# the libraries only the workspace projects use. Renames are not folded: a watched file moved elsewhere counts as a change to its old
# path.
#
# Usage: scripts/workspace-tier-changes.sh BASE HEAD      (CI passes HEAD^1 HEAD on the PR's merge commit)
# Prints `changed=true` or `changed=false` on stdout, for $GITHUB_OUTPUT, and why on stderr. Needs git and ruby.
set -euo pipefail

BASE="${1:?usage: $0 BASE HEAD}"
HEAD="${2:?usage: $0 BASE HEAD}"

# Paths whose change runs the tier. Extended regular expressions, matched against repo-relative paths.
PATTERNS=(
  '^modules/workspace/'
  '^modules/agent/src/main/scala/org/llm4s/agent/harness/'
  '^modules/agent-testkit/src/main/scala/org/llm4s/agent/testkit/(FileBackend|FileTools|Replay)Contract\.scala$'
  '^modules/it/src/test/scala/org/llm4s/workspace/'
  '^modules/it/src/test/java/org/llm4s/it/tags/Workspace\.java$'
  '^modules/samples/src/main/scala/org/llm4s/samples/harness/CodingWorkspace[A-Za-z]*\.scala$'
  '^project/WorkspaceRunnerDocker\.scala$'
  '^project/ItTiers\.scala$'
  '^scripts/workspace-tier-changes\.sh$'
)
CI_FILE='.github/workflows/ci.yml'
CI_JOBS='workspace-changes workspace-integration all-tests-pass'

files="$(git diff --name-only --no-renames "$BASE" "$HEAD")"
matched="$(printf '%s\n' "$files" | grep -E "$(IFS='|'; echo "${PATTERNS[*]}")" || true)"
if [ -n "$matched" ]; then
  echo "workspace tier: the change touches" >&2
  printf '%s\n' "$matched" | sed 's/^/  /' >&2
  echo "changed=true"
  exit 0
fi

# build.sbt: the changed lines (-U0) and their hunk headers, where git names the enclosing column-0 definition
if printf '%s\n' "$files" | grep -qxF build.sbt &&
  git diff -U0 --no-renames "$BASE" "$HEAD" -- build.sbt | grep -E '^(@@|[+-])' | grep -vE '^(\+\+\+|---) ' |
  grep -qi workspace; then
  echo "workspace tier: the change edits workspace wiring in build.sbt" >&2
  echo "changed=true"
  exit 0
fi

# project/Dependencies.scala: a changed line naming a library only the workspace projects use (the runner's cask
# server, the client's Java-WebSocket). Libraries every module shares are left to the other tiers.
if printf '%s\n' "$files" | grep -qxF project/Dependencies.scala &&
  git diff -U0 --no-renames "$BASE" "$HEAD" -- project/Dependencies.scala | grep -E '^[+-]' | grep -vE '^(\+\+\+|---) ' |
  grep -qiE 'cask|websocket'; then
  echo "workspace tier: the change edits a workspace dependency in project/Dependencies.scala" >&2
  echo "changed=true"
  exit 0
fi

if printf '%s\n' "$files" | grep -qxF "$CI_FILE"; then
  # A job missing on one side (added or removed) compares as nil against its definition, so it counts as changed.
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' EXIT
  git show "$BASE:$CI_FILE" > "$tmp/base.yml" 2>/dev/null || : > "$tmp/base.yml"
  git show "$HEAD:$CI_FILE" > "$tmp/head.yml" 2>/dev/null || : > "$tmp/head.yml"
  # shellcheck disable=SC2086 # CI_JOBS is a list of job ids
  if ! ruby -ryaml -e '
    base, head, *ids = ARGV
    pick = ->(f) { ((YAML.safe_load(File.read(f), aliases: true) || {})["jobs"] || {}).slice(*ids) }
    exit(pick.(base) == pick.(head) ? 0 : 1)
  ' "$tmp/base.yml" "$tmp/head.yml" $CI_JOBS; then
    echo "workspace tier: the change edits the $CI_JOBS job(s) in $CI_FILE" >&2
    echo "changed=true"
    exit 0
  fi
fi

echo "workspace tier: the change touches nothing the @Workspace suites exercise" >&2
echo "changed=false"
